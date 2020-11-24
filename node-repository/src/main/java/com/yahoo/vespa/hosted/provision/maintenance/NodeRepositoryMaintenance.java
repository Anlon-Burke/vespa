// Copyright Verizon Media. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.provision.maintenance;

import com.google.inject.Inject;
import com.yahoo.component.AbstractComponent;
import com.yahoo.config.provision.Deployer;
import com.yahoo.config.provision.Environment;
import com.yahoo.config.provision.HostLivenessTracker;
import com.yahoo.config.provision.InfraDeployer;
import com.yahoo.config.provision.Zone;
import com.yahoo.jdisc.Metric;
import com.yahoo.vespa.flags.FlagSource;
import com.yahoo.vespa.hosted.provision.NodeRepository;
import com.yahoo.vespa.hosted.provision.autoscale.MetricsFetcher;
import com.yahoo.vespa.hosted.provision.autoscale.MetricsDb;
import com.yahoo.vespa.hosted.provision.provisioning.ProvisionServiceProvider;
import com.yahoo.vespa.orchestrator.Orchestrator;
import com.yahoo.vespa.service.monitor.ServiceMonitor;

import java.time.Duration;
import java.util.Optional;

/**
 * A component which sets up all the node repo maintenance jobs.
 *
 * @author bratseth
 */
public class NodeRepositoryMaintenance extends AbstractComponent {

    private final NodeFailer nodeFailer;
    private final NodeHealthTracker nodeHealthTracker;
    private final PeriodicApplicationMaintainer periodicApplicationMaintainer;
    private final OperatorChangeApplicationMaintainer operatorChangeApplicationMaintainer;
    private final ReservationExpirer reservationExpirer;
    private final InactiveExpirer inactiveExpirer;
    private final RetiredExpirer retiredExpirer;
    private final FailedExpirer failedExpirer;
    private final DirtyExpirer dirtyExpirer;
    private final ProvisionedExpirer provisionedExpirer;
    private final NodeRebooter nodeRebooter;
    private final MetricsReporter metricsReporter;
    private final InfrastructureProvisioner infrastructureProvisioner;
    private final Optional<LoadBalancerExpirer> loadBalancerExpirer;
    private final Optional<DynamicProvisioningMaintainer> dynamicProvisioningMaintainer;
    private final SpareCapacityMaintainer spareCapacityMaintainer;
    private final OsUpgradeActivator osUpgradeActivator;
    private final Rebalancer rebalancer;
    private final NodeMetricsDbMaintainer nodeMetricsDbMaintainer;
    private final AutoscalingMaintainer autoscalingMaintainer;
    private final ScalingSuggestionsMaintainer scalingSuggestionsMaintainer;
    private final SwitchRebalancer switchRebalancer;

    @SuppressWarnings("unused")
    @Inject
    public NodeRepositoryMaintenance(NodeRepository nodeRepository, Deployer deployer, InfraDeployer infraDeployer,
                                     HostLivenessTracker hostLivenessTracker, ServiceMonitor serviceMonitor,
                                     Zone zone, Orchestrator orchestrator, Metric metric,
                                     ProvisionServiceProvider provisionServiceProvider, FlagSource flagSource,
                                     MetricsFetcher metricsFetcher, MetricsDb metricsDb) {
        DefaultTimes defaults = new DefaultTimes(zone, deployer);

        nodeFailer = new NodeFailer(deployer, nodeRepository, defaults.failGrace, defaults.nodeFailerInterval, orchestrator, defaults.throttlePolicy, metric);
        nodeHealthTracker = new NodeHealthTracker(hostLivenessTracker, serviceMonitor, nodeRepository, defaults.nodeFailureStatusUpdateInterval, metric);
        periodicApplicationMaintainer = new PeriodicApplicationMaintainer(deployer, metric, nodeRepository,
                                                                          defaults.redeployMaintainerInterval, defaults.periodicRedeployInterval, flagSource);
        operatorChangeApplicationMaintainer = new OperatorChangeApplicationMaintainer(deployer, metric, nodeRepository, defaults.operatorChangeRedeployInterval);
        reservationExpirer = new ReservationExpirer(nodeRepository, defaults.reservationExpiry, metric);
        retiredExpirer = new RetiredExpirer(nodeRepository, orchestrator, deployer, metric, defaults.retiredInterval, defaults.retiredExpiry);
        inactiveExpirer = new InactiveExpirer(nodeRepository, defaults.inactiveExpiry, metric);
        failedExpirer = new FailedExpirer(nodeRepository, zone, defaults.failedExpirerInterval, metric);
        dirtyExpirer = new DirtyExpirer(nodeRepository, defaults.dirtyExpiry, metric);
        provisionedExpirer = new ProvisionedExpirer(nodeRepository, defaults.provisionedExpiry, metric);
        nodeRebooter = new NodeRebooter(nodeRepository, flagSource, metric);
        metricsReporter = new MetricsReporter(nodeRepository, metric, orchestrator, serviceMonitor, periodicApplicationMaintainer::pendingDeployments, defaults.metricsInterval);
        infrastructureProvisioner = new InfrastructureProvisioner(nodeRepository, infraDeployer, defaults.infrastructureProvisionInterval, metric);
        loadBalancerExpirer = provisionServiceProvider.getLoadBalancerService(nodeRepository).map(lbService ->
                new LoadBalancerExpirer(nodeRepository, defaults.loadBalancerExpirerInterval, lbService, metric));
        dynamicProvisioningMaintainer = provisionServiceProvider.getHostProvisioner().map(hostProvisioner ->
                new DynamicProvisioningMaintainer(nodeRepository, defaults.dynamicProvisionerInterval, hostProvisioner, flagSource, metric));
        spareCapacityMaintainer = new SpareCapacityMaintainer(deployer, nodeRepository, metric, defaults.spareCapacityMaintenanceInterval);
        osUpgradeActivator = new OsUpgradeActivator(nodeRepository, defaults.osUpgradeActivatorInterval, metric);
        rebalancer = new Rebalancer(deployer, nodeRepository, metric, defaults.rebalancerInterval);
        nodeMetricsDbMaintainer = new NodeMetricsDbMaintainer(nodeRepository, metricsFetcher, metricsDb, defaults.nodeMetricsCollectionInterval, metric);
        autoscalingMaintainer = new AutoscalingMaintainer(nodeRepository, metricsDb, deployer, metric, defaults.autoscalingInterval);
        scalingSuggestionsMaintainer = new ScalingSuggestionsMaintainer(nodeRepository, metricsDb, defaults.scalingSuggestionsInterval, metric);
        switchRebalancer = new SwitchRebalancer(nodeRepository, defaults.switchRebalancerInterval, metric, deployer);

        // The DuperModel is filled with infrastructure applications by the infrastructure provisioner, so explicitly run that now
        infrastructureProvisioner.maintainButThrowOnException();
    }

    @Override
    public void deconstruct() {
        nodeFailer.close();
        nodeHealthTracker.close();
        periodicApplicationMaintainer.close();
        operatorChangeApplicationMaintainer.close();
        reservationExpirer.close();
        inactiveExpirer.close();
        retiredExpirer.close();
        failedExpirer.close();
        dirtyExpirer.close();
        nodeRebooter.close();
        spareCapacityMaintainer.close();
        provisionedExpirer.close();
        metricsReporter.close();
        infrastructureProvisioner.close();
        loadBalancerExpirer.ifPresent(NodeRepositoryMaintainer::close);
        dynamicProvisioningMaintainer.ifPresent(NodeRepositoryMaintainer::close);
        osUpgradeActivator.close();
        rebalancer.close();
        nodeMetricsDbMaintainer.close();
        autoscalingMaintainer.close();
        scalingSuggestionsMaintainer.close();
        switchRebalancer.close();
    }

    private static class DefaultTimes {

        /** Minimum time to wait between deployments by periodic application maintainer*/
        private final Duration periodicRedeployInterval;
        /** Time between each run of maintainer that does periodic redeployment */
        private final Duration redeployMaintainerInterval;
        /** Applications are redeployed after manual operator changes within this time period */
        private final Duration operatorChangeRedeployInterval;

        /** The time a node must be continuously unresponsive before it is failed */
        private final Duration failGrace;
        
        private final Duration reservationExpiry;
        private final Duration inactiveExpiry;
        private final Duration retiredExpiry;
        private final Duration failedExpirerInterval;
        private final Duration dirtyExpiry;
        private final Duration provisionedExpiry;
        private final Duration spareCapacityMaintenanceInterval;
        private final Duration metricsInterval;
        private final Duration nodeFailerInterval;
        private final Duration nodeFailureStatusUpdateInterval;
        private final Duration retiredInterval;
        private final Duration infrastructureProvisionInterval;
        private final Duration loadBalancerExpirerInterval;
        private final Duration dynamicProvisionerInterval;
        private final Duration osUpgradeActivatorInterval;
        private final Duration rebalancerInterval;
        private final Duration nodeMetricsCollectionInterval;
        private final Duration autoscalingInterval;
        private final Duration scalingSuggestionsInterval;
        private final Duration switchRebalancerInterval;

        private final NodeFailer.ThrottlePolicy throttlePolicy;

        DefaultTimes(Zone zone, Deployer deployer) {
            autoscalingInterval = Duration.ofMinutes(15);
            dynamicProvisionerInterval = Duration.ofMinutes(5);
            failedExpirerInterval = Duration.ofMinutes(10);
            failGrace = Duration.ofMinutes(30);
            infrastructureProvisionInterval = Duration.ofMinutes(1);
            loadBalancerExpirerInterval = Duration.ofMinutes(5);
            metricsInterval = Duration.ofMinutes(1);
            nodeFailerInterval = Duration.ofMinutes(15);
            nodeFailureStatusUpdateInterval = Duration.ofMinutes(2);
            nodeMetricsCollectionInterval = Duration.ofMinutes(1);
            operatorChangeRedeployInterval = Duration.ofMinutes(3);
            // Vespa upgrade frequency is higher in CD so (de)activate OS upgrades more frequently as well
            osUpgradeActivatorInterval = zone.system().isCd() ? Duration.ofSeconds(30) : Duration.ofMinutes(5);
            periodicRedeployInterval = Duration.ofMinutes(30);
            provisionedExpiry = Duration.ofHours(4);
            rebalancerInterval = Duration.ofMinutes(120);
            redeployMaintainerInterval = Duration.ofMinutes(1);
            // Need to be long enough for deployment to be finished for all config model versions
            reservationExpiry = deployer.serverDeployTimeout();
            scalingSuggestionsInterval = Duration.ofMinutes(31);
            spareCapacityMaintenanceInterval = Duration.ofMinutes(30);
            switchRebalancerInterval = Duration.ofHours(1);
            throttlePolicy = NodeFailer.ThrottlePolicy.hosted;

            if (zone.environment().equals(Environment.prod) && ! zone.system().isCd()) {
                inactiveExpiry = Duration.ofHours(4); // enough time for the application owner to discover and redeploy
                retiredInterval = Duration.ofMinutes(30);
                dirtyExpiry = Duration.ofHours(2); // enough time to clean the node
                retiredExpiry = Duration.ofDays(4); // give up migrating data after 4 days
            } else {
                inactiveExpiry = Duration.ofSeconds(2); // support interactive wipe start over
                retiredInterval = Duration.ofMinutes(1);
                dirtyExpiry = Duration.ofMinutes(30);
                retiredExpiry = Duration.ofMinutes(20);
            }
        }

    }

}
