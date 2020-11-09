// Copyright 2020 Oath Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.controller;

import com.google.common.base.Suppliers;
import com.yahoo.component.Version;
import com.yahoo.config.application.api.DeploymentInstanceSpec;
import com.yahoo.config.provision.ApplicationId;
import com.yahoo.config.provision.ClusterSpec;
import com.yahoo.config.provision.Environment;
import com.yahoo.config.provision.InstanceName;
import com.yahoo.config.provision.zone.RoutingMethod;
import com.yahoo.config.provision.zone.ZoneId;
import com.yahoo.vespa.flags.BooleanFlag;
import com.yahoo.vespa.flags.FetchVector;
import com.yahoo.vespa.flags.Flags;
import com.yahoo.vespa.hosted.controller.api.application.v4.model.EndpointStatus;
import com.yahoo.vespa.hosted.controller.api.identifiers.DeploymentId;
import com.yahoo.vespa.hosted.controller.api.integration.configserver.ContainerEndpoint;
import com.yahoo.vespa.hosted.controller.api.integration.deployment.ApplicationVersion;
import com.yahoo.vespa.hosted.controller.api.integration.dns.Record;
import com.yahoo.vespa.hosted.controller.api.integration.dns.RecordData;
import com.yahoo.vespa.hosted.controller.api.integration.dns.RecordName;
import com.yahoo.vespa.hosted.controller.application.Endpoint;
import com.yahoo.vespa.hosted.controller.application.Endpoint.Port;
import com.yahoo.vespa.hosted.controller.application.EndpointList;
import com.yahoo.vespa.hosted.controller.application.SystemApplication;
import com.yahoo.vespa.hosted.controller.application.TenantAndApplicationId;
import com.yahoo.vespa.hosted.controller.dns.NameServiceQueue.Priority;
import com.yahoo.vespa.hosted.controller.rotation.RotationLock;
import com.yahoo.vespa.hosted.controller.rotation.RotationRepository;
import com.yahoo.vespa.hosted.controller.routing.RoutingId;
import com.yahoo.vespa.hosted.controller.routing.RoutingPolicies;
import com.yahoo.vespa.hosted.rotation.config.RotationsConfig;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * The routing controller encapsulates state and methods for inspecting and manipulating deployment endpoints in a
 * hosted Vespa system.
 *
 * The one stop shop for all your routing needs!
 *
 * @author mpolden
 */
public class RoutingController {

    /** The minimum Vespa version that supports directly routed endpoints */
    public static final Version DIRECT_ROUTING_MIN_VERSION = new Version(7, 196, 4);

    private final Controller controller;
    private final RoutingPolicies routingPolicies;
    private final RotationRepository rotationRepository;
    private final BooleanFlag hideSharedRoutingEndpoint;

    public RoutingController(Controller controller, RotationsConfig rotationsConfig) {
        this.controller = Objects.requireNonNull(controller, "controller must be non-null");
        this.routingPolicies = new RoutingPolicies(controller);
        this.rotationRepository = new RotationRepository(rotationsConfig, controller.applications(),
                                                         controller.curator());
        this.hideSharedRoutingEndpoint = Flags.HIDE_SHARED_ROUTING_ENDPOINT.bindTo(controller.flagSource());
    }

    public RoutingPolicies policies() {
        return routingPolicies;
    }

    public RotationRepository rotations() {
        return rotationRepository;
    }

    /** Returns endpoints for given deployment */
    public EndpointList endpointsOf(DeploymentId deployment) {
        var endpoints = new LinkedHashSet<Endpoint>();
        boolean isSystemApplication = SystemApplication.matching(deployment.applicationId()).isPresent();
        // Avoid reading application more than once per call to this
        var application = Suppliers.memoize(() -> controller.applications().requireApplication(TenantAndApplicationId.from(deployment.applicationId())));
        for (var policy : routingPolicies.get(deployment).values()) {
            if (!policy.status().isActive()) continue;
            for (var routingMethod :  controller.zoneRegistry().routingMethods(policy.id().zone())) {
                if (routingMethod.isDirect() && !isSystemApplication && !canRouteDirectlyTo(deployment, application.get())) continue;
                endpoints.add(policy.endpointIn(controller.system(), routingMethod, controller.zoneRegistry()));
                endpoints.add(policy.regionEndpointIn(controller.system(), routingMethod));
            }
        }
        return EndpointList.copyOf(endpoints);
    }

    /** Returns global-scoped endpoints for given instance */
    public EndpointList endpointsOf(ApplicationId instance) {
        if (SystemApplication.matching(instance).isPresent()) return EndpointList.copyOf(List.of());
        return endpointsOf(controller.applications().requireApplication(TenantAndApplicationId.from(instance)),
                                                                        instance.instance());
    }

    /** Returns global-scoped endpoints for given instance */
    public EndpointList endpointsOf(Application application, InstanceName instanceName) {
        var endpoints = new LinkedHashSet<Endpoint>();
        // Add global endpoints provided by rotations
        var instance = application.require(instanceName);
        for (var rotation : instance.rotations()) {
            var deployments = rotation.regions().stream()
                                      .map(region -> new DeploymentId(instance.id(), ZoneId.from(Environment.prod, region)))
                                      .collect(Collectors.toList());
            computeGlobalEndpoints(RoutingId.of(instance.id(), rotation.endpointId()), rotation.clusterId(),
                                   application, deployments).requiresRotation()
                                                            .forEach(endpoints::add);
        }
        // Add global endpoints provided by routing policies
        var deploymentsByEndpointKey = new LinkedHashMap<EndpointKey, List<DeploymentId>>();
        for (var policy : routingPolicies.get(instance.id()).values()) {
            if (!policy.status().isActive()) continue;
            for (var endpointId : policy.endpoints()) {
                var endpointKey = new EndpointKey(RoutingId.of(instance.id(), endpointId), policy.id().cluster());
                deploymentsByEndpointKey.computeIfAbsent(endpointKey, (k) -> new ArrayList<>())
                                        .add(new DeploymentId(instance.id(), policy.id().zone()));
            }
        }
        deploymentsByEndpointKey.forEach((endpointKey, deployments) -> {
            computeGlobalEndpoints(endpointKey.routingId, endpointKey.cluster, application,
                                   deployments).not().requiresRotation().forEach(endpoints::add);
        });
        return EndpointList.copyOf(endpoints);
    }

    /** Returns all zone-scoped endpoints and corresponding cluster IDs for given deployments, grouped by their zone */
    public Map<ZoneId, List<Endpoint>> zoneEndpointsOf(Collection<DeploymentId> deployments) {
        var endpoints = new TreeMap<ZoneId, List<Endpoint>>(Comparator.comparing(ZoneId::value));
        for (var deployment : deployments) {
            EndpointList zoneEndpoints = endpointsOf(deployment).scope(Endpoint.Scope.zone);
            zoneEndpoints = directEndpoints(zoneEndpoints, deployment.applicationId());
            if  ( ! zoneEndpoints.isEmpty()) {
                endpoints.put(deployment.zoneId(), zoneEndpoints.asList());
            }
        }
        return Collections.unmodifiableMap(endpoints);
    }

    /** Change status of all global endpoints for given deployment */
    public void setGlobalRotationStatus(DeploymentId deployment, EndpointStatus status) {
        endpointsOf(deployment.applicationId()).requiresRotation().primary().ifPresent(endpoint -> {
            try {
                controller.serviceRegistry().configServer().setGlobalRotationStatus(deployment, endpoint.upstreamIdOf(deployment), status);
            } catch (Exception e) {
                throw new RuntimeException("Failed to set rotation status of " + endpoint + " in " + deployment, e);
            }
        });
    }

    /** Get global endpoint status for given deployment */
    public Map<Endpoint, EndpointStatus> globalRotationStatus(DeploymentId deployment) {
        var routingEndpoints = new LinkedHashMap<Endpoint, EndpointStatus>();
        endpointsOf(deployment.applicationId()).requiresRotation().primary().ifPresent(endpoint -> {
            var upstreamName = endpoint.upstreamIdOf(deployment);
            var status = controller.serviceRegistry().configServer().getGlobalRotationStatus(deployment, upstreamName);
            routingEndpoints.put(endpoint, status);
        });
        return Collections.unmodifiableMap(routingEndpoints);
    }

    /**
     * Assigns one or more global rotations to given application, if eligible. The given application is implicitly
     * stored, ensuring that the assigned rotation(s) are persisted when this returns.
     */
    public LockedApplication assignRotations(LockedApplication application, InstanceName instanceName) {
        try (RotationLock rotationLock = rotationRepository.lock()) {
            var rotations = rotationRepository.getOrAssignRotations(application.get().deploymentSpec(),
                                                                    application.get().require(instanceName),
                                                                    rotationLock);
            application = application.with(instanceName, instance -> instance.with(rotations));
            controller.applications().store(application); // store assigned rotation even if deployment fails
        }
        return application;
    }

    /**
     * Register endpoints for rotations assigned to given application and zone in DNS.
     *
     * @return the registered endpoints
     */
    public Set<ContainerEndpoint> registerEndpointsInDns(Application application, InstanceName instanceName, ZoneId zone) {
        var instance = application.require(instanceName);
        var containerEndpoints = new HashSet<ContainerEndpoint>();
        boolean registerLegacyNames = application.deploymentSpec().instance(instanceName)
                                                 .flatMap(DeploymentInstanceSpec::globalServiceId)
                                                 .isPresent();
        for (var assignedRotation : instance.rotations()) {
            var names = new ArrayList<String>();
            var endpoints = endpointsOf(application, instanceName).named(assignedRotation.endpointId())
                                                                  .requiresRotation();

            // Skip rotations which do not apply to this zone. Legacy names always point to all zones
            if (!registerLegacyNames && !assignedRotation.regions().contains(zone.region())) {
                continue;
            }

            // Omit legacy DNS names when assigning rotations using <endpoints/> syntax
            if (!registerLegacyNames) {
                endpoints = endpoints.not().legacy();
            }

            // Register names in DNS
            var rotation = rotationRepository.getRotation(assignedRotation.rotationId());
            if (rotation.isPresent()) {
                endpoints.forEach(endpoint -> {
                    controller.nameServiceForwarder().createCname(RecordName.from(endpoint.dnsName()),
                                                                  RecordData.fqdn(rotation.get().name()),
                                                                  Priority.normal);
                    names.add(endpoint.dnsName());
                });
            }

            // Include rotation ID as a valid name of this container endpoint (required by global routing health checks)
            names.add(assignedRotation.rotationId().asString());
            containerEndpoints.add(new ContainerEndpoint(assignedRotation.clusterId().value(), names));
        }
        return Collections.unmodifiableSet(containerEndpoints);
    }

    /** Remove endpoints in DNS for all rotations assigned to given instance */
    public void removeEndpointsInDns(Application application, InstanceName instanceName) {
        endpointsOf(application, instanceName).requiresRotation()
                                              .forEach(endpoint -> controller.nameServiceForwarder()
                                                                             .removeRecords(Record.Type.CNAME,
                                                                                            RecordName.from(endpoint.dnsName()),
                                                                                            Priority.normal));
    }

    /** Returns the routing methods that are available across all given deployments */
    private List<RoutingMethod> routingMethodsOfAll(List<DeploymentId> deployments, Application application) {
        var deploymentsByMethod = new HashMap<RoutingMethod, Set<DeploymentId>>();
        for (var deployment : deployments) {
            for (var method : controller.zoneRegistry().routingMethods(deployment.zoneId())) {
                deploymentsByMethod.putIfAbsent(method, new LinkedHashSet<>());
                deploymentsByMethod.get(method).add(deployment);
            }
        }
        var routingMethods = new ArrayList<RoutingMethod>();
        deploymentsByMethod.forEach((method, supportedDeployments) -> {
            if (supportedDeployments.containsAll(deployments)) {
                if (method.isDirect() && !canRouteDirectlyTo(deployments, application)) return;
                routingMethods.add(method);
            }
        });
        return Collections.unmodifiableList(routingMethods);
    }

    /** Returns whether traffic can be directly routed to all given deployments */
    private boolean canRouteDirectlyTo(List<DeploymentId> deployments, Application application) {
        return deployments.stream().allMatch(deployment -> canRouteDirectlyTo(deployment, application));
    }

    /** Returns whether traffic can be directly routed to given deployment */
    private boolean canRouteDirectlyTo(DeploymentId deploymentId, Application application) {
        if (controller.system().isPublic()) return true; // Public always supports direct routing
        if (controller.system().isCd()) return true; // CD deploys directly so we cannot enforce all requirements below
        if (deploymentId.zoneId().environment().isManuallyDeployed()) return true; // Manually deployed zones always support direct routing

        // Check Athenz service presence. The test framework uses this identity when sending requests to the
        // deployment's container(s).
        var athenzService = application.deploymentSpec().instance(deploymentId.applicationId().instance())
                                       .flatMap(instance -> instance.athenzService(deploymentId.zoneId().environment(),
                                                                                   deploymentId.zoneId().region()));
        if (athenzService.isEmpty()) return false;

        // Check minimum required compile-version
        var compileVersion = application.latestVersion().flatMap(ApplicationVersion::compileVersion);
        if (compileVersion.isEmpty()) return false;
        if (compileVersion.get().isBefore(DIRECT_ROUTING_MIN_VERSION)) return false;
        return true;
    }

    /** Compute global endpoints for given routing ID, application and deployments */
    private EndpointList computeGlobalEndpoints(RoutingId routingId, ClusterSpec.Id cluster, Application application, List<DeploymentId> deployments) {
        var endpoints = new ArrayList<Endpoint>();
        var directMethods = 0;
        var zones = deployments.stream().map(DeploymentId::zoneId).collect(Collectors.toList());
        var availableRoutingMethods = routingMethodsOfAll(deployments, application);

        for (var method : availableRoutingMethods) {
            if (method.isDirect() && ++directMethods > 1) {
                throw new IllegalArgumentException("Invalid routing methods for " + routingId + ": Exceeded maximum " +
                                                   "direct methods");
            }
            endpoints.add(Endpoint.of(routingId.application())
                                  .target(routingId.endpointId(), cluster, zones)
                                  .on(Port.fromRoutingMethod(method))
                                  .routingMethod(method)
                                  .in(controller.system()));
            // TODO(mpolden): Remove this once all applications have migrated away from legacy endpoints
            if (method == RoutingMethod.shared) {
                endpoints.add(Endpoint.of(routingId.application())
                                      .target(routingId.endpointId(), cluster, zones)
                                      .on(Port.plain(4080))
                                      .legacy()
                                      .routingMethod(method)
                                      .in(controller.system()));
                endpoints.add(Endpoint.of(routingId.application())
                                      .target(routingId.endpointId(), cluster, zones)
                                      .on(Port.tls(4443))
                                      .legacy()
                                      .routingMethod(method)
                                      .in(controller.system()));
            }
        }
        return EndpointList.copyOf(endpoints);
    }

    private static class EndpointKey {

        private final RoutingId routingId;
        private final ClusterSpec.Id cluster;

        public EndpointKey(RoutingId routingId, ClusterSpec.Id cluster) {
            this.routingId = routingId;
            this.cluster = cluster;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            EndpointKey that = (EndpointKey) o;
            return routingId.equals(that.routingId) &&
                   cluster.equals(that.cluster);
        }

        @Override
        public int hashCode() {
            return Objects.hash(routingId, cluster);
        }

    }

    /** Returns direct routing endpoints if any exist and feature flag is set for given application */
    // TODO: Remove this when feature flag is removed, and in-line .direct() filter where relevant
    public EndpointList directEndpoints(EndpointList endpoints, ApplicationId application) {
        boolean hideSharedEndpoint = hideSharedRoutingEndpoint.with(FetchVector.Dimension.APPLICATION_ID, application.serializedForm()).value();
        EndpointList directEndpoints = endpoints.direct();
        if (hideSharedEndpoint && !directEndpoints.isEmpty()) {
            return directEndpoints;
        }
        return endpoints;
    }


}
