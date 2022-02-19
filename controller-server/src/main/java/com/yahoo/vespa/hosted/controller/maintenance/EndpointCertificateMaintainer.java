// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.controller.maintenance;

import com.google.common.collect.Sets;
import com.google.inject.Inject;
import com.yahoo.config.provision.ApplicationId;
import com.yahoo.container.jdisc.secretstore.SecretNotFoundException;
import com.yahoo.container.jdisc.secretstore.SecretStore;
import com.yahoo.vespa.curator.Lock;
import com.yahoo.vespa.flags.BooleanFlag;
import com.yahoo.vespa.flags.Flags;
import com.yahoo.vespa.hosted.controller.Controller;
import com.yahoo.vespa.hosted.controller.Instance;
import com.yahoo.vespa.hosted.controller.api.integration.certificates.EndpointCertificateMetadata;
import com.yahoo.vespa.hosted.controller.api.integration.certificates.EndpointCertificateProvider;
import com.yahoo.vespa.hosted.controller.api.integration.certificates.EndpointCertificateRequestMetadata;
import com.yahoo.vespa.hosted.controller.api.integration.deployment.JobType;
import com.yahoo.vespa.hosted.controller.application.TenantAndApplicationId;
import com.yahoo.vespa.hosted.controller.deployment.DeploymentTrigger;
import com.yahoo.vespa.hosted.controller.persistence.CuratorDb;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Updates refreshed endpoint certificates and triggers redeployment, and deletes unused certificates.
 * <p>
 * See also EndpointCertificateManager, which provisions, reprovisions and validates certificates on deploy
 *
 * @author andreer
 */
public class EndpointCertificateMaintainer extends ControllerMaintainer {

    private static final Logger log = Logger.getLogger(EndpointCertificateMaintainer.class.getName());

    private final DeploymentTrigger deploymentTrigger;
    private final Clock clock;
    private final CuratorDb curator;
    private final SecretStore secretStore;
    private final EndpointCertificateProvider endpointCertificateProvider;
    private final BooleanFlag deleteUnmaintainedCertificates = Flags.DELETE_UNMAINTAINED_CERTIFICATES.bindTo(controller().flagSource());

    @Inject
    public EndpointCertificateMaintainer(Controller controller, Duration interval) {
        super(controller, interval);
        this.deploymentTrigger = controller.applications().deploymentTrigger();
        this.clock = controller.clock();
        this.secretStore = controller.secretStore();
        this.curator = controller().curator();
        this.endpointCertificateProvider = controller.serviceRegistry().endpointCertificateProvider();
    }

    @Override
    protected double maintain() {
        try {
            // In order of importance
            deployRefreshedCertificates();
            updateRefreshedCertificates();
            deleteUnusedCertificates();
            deleteOrReportUnmanagedCertificates();
        } catch (Exception e) {
            log.log(Level.SEVERE, "Exception caught while maintaining endpoint certificates", e);
            return 0.0;
        }

        return 1.0;
    }

    private void updateRefreshedCertificates() {
        curator.readAllEndpointCertificateMetadata().forEach(((applicationId, endpointCertificateMetadata) -> {
            // Look for and use refreshed certificate
            var latestAvailableVersion = latestVersionInSecretStore(endpointCertificateMetadata);
            if (latestAvailableVersion.isPresent() && latestAvailableVersion.getAsInt() > endpointCertificateMetadata.version()) {
                var refreshedCertificateMetadata = endpointCertificateMetadata
                        .withVersion(latestAvailableVersion.getAsInt())
                        .withLastRefreshed(clock.instant().getEpochSecond());
                try (Lock lock = lock(applicationId)) {
                    if (Optional.of(endpointCertificateMetadata).equals(curator.readEndpointCertificateMetadata(applicationId))) {
                        curator.writeEndpointCertificateMetadata(applicationId, refreshedCertificateMetadata); // Certificate not validated here, but on deploy.
                    }
                }
            }
        }));
    }

    /**
     * If it's been four days since the cert has been refreshed, re-trigger all prod deployment jobs.
     */
    private void deployRefreshedCertificates() {
        var now = clock.instant();
        curator.readAllEndpointCertificateMetadata().forEach((applicationId, endpointCertificateMetadata) ->
                endpointCertificateMetadata.lastRefreshed().ifPresent(lastRefreshTime -> {
                    Instant refreshTime = Instant.ofEpochSecond(lastRefreshTime);
                    if (now.isAfter(refreshTime.plus(4, ChronoUnit.DAYS))) {
                        controller().applications().getInstance(applicationId)
                                .ifPresent(instance -> instance.productionDeployments().forEach((zone, deployment) -> {
                                    if (deployment.at().isBefore(refreshTime)) {
                                        JobType job = JobType.from(controller().system(), zone).orElseThrow();
                                        deploymentTrigger.reTrigger(applicationId, job);
                                        log.info("Re-triggering deployment job " + job.jobName() + " for instance " +
                                                applicationId.serializedForm() + " to roll out refreshed endpoint certificate");
                                    }
                                }));
                    }
                }));
    }

    private OptionalInt latestVersionInSecretStore(EndpointCertificateMetadata originalCertificateMetadata) {
        try {
            var certVersions = new HashSet<>(secretStore.listSecretVersions(originalCertificateMetadata.certName()));
            var keyVersions = new HashSet<>(secretStore.listSecretVersions(originalCertificateMetadata.keyName()));
            return Sets.intersection(certVersions, keyVersions).stream().mapToInt(Integer::intValue).max();
        } catch (SecretNotFoundException s) {
            return OptionalInt.empty(); // Likely because the certificate is very recently provisioned - keep current version
        }
    }

    private void deleteUnusedCertificates() {
        var oneMonthAgo = clock.instant().minus(30, ChronoUnit.DAYS);
        curator.readAllEndpointCertificateMetadata().forEach((applicationId, storedMetaData) -> {
            var lastRequested = Instant.ofEpochSecond(storedMetaData.lastRequested());
            if (lastRequested.isBefore(oneMonthAgo) && hasNoDeployments(applicationId)) {
                try (Lock lock = lock(applicationId)) {
                    if (Optional.of(storedMetaData).equals(curator.readEndpointCertificateMetadata(applicationId))) {
                        log.log(Level.INFO, "Cert for app " + applicationId.serializedForm()
                                + " has not been requested in a month and app has no deployments, deleting from provider and ZK");
                        endpointCertificateProvider.deleteCertificate(applicationId, storedMetaData.requestId());
                        curator.deleteEndpointCertificateMetadata(applicationId);
                    }
                }
            }
        });
    }

    private Lock lock(ApplicationId applicationId) {
        return curator.lock(TenantAndApplicationId.from(applicationId));
    }

    private boolean hasNoDeployments(ApplicationId applicationId) {
        return controller().applications().getInstance(applicationId)
                           .map(Instance::deployments)
                           .orElseGet(Map::of)
                           .isEmpty();
    }

    private void deleteOrReportUnmanagedCertificates() {
        List<EndpointCertificateRequestMetadata> endpointCertificateMetadata = endpointCertificateProvider.listCertificates();
        Set<String> managedRequestIds = curator.readAllEndpointCertificateMetadata().values().stream().map(EndpointCertificateMetadata::requestId).collect(Collectors.toSet());

        for (var providerCertificateMetadata : endpointCertificateMetadata) {
            if (!managedRequestIds.contains(providerCertificateMetadata.requestId())) {
                if (deleteUnmaintainedCertificates.value()) {
                    // The certificate is not known - however it could be in the process of being requested by us or another controller.
                    // So we only delete if it was requested more than 7 days ago.
                    if (Instant.parse(providerCertificateMetadata.createTime()).isBefore(Instant.now().minus(7, ChronoUnit.DAYS))) {
                        log.log(Level.INFO, String.format("Deleting unmaintained certificate with request_id %s and SANs %s",
                                providerCertificateMetadata.requestId(),
                                providerCertificateMetadata.dnsNames().stream().map(d -> d.dnsName).collect(Collectors.joining(", "))));
                        endpointCertificateProvider.deleteCertificate(ApplicationId.fromSerializedForm("applicationid:is:unknown"), providerCertificateMetadata.requestId());
                    }
                } else {
                    log.log(Level.FINE, () -> String.format("Found unmaintained certificate with request_id %s and SANs %s",
                            providerCertificateMetadata.requestId(),
                            providerCertificateMetadata.dnsNames().stream().map(d -> d.dnsName).collect(Collectors.joining(", "))));
                }
            }
        }
    }
}
