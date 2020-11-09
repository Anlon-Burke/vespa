// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.orchestrator.policy;

import com.yahoo.vespa.orchestrator.model.ClusterApi;
import com.yahoo.vespa.orchestrator.model.VespaModelUtil;

import java.util.Optional;

import static com.yahoo.vespa.orchestrator.policy.HostedVespaPolicy.ENOUGH_SERVICES_UP_CONSTRAINT;

public class HostedVespaClusterPolicy implements ClusterPolicy {

    @Override
    public SuspensionReasons verifyGroupGoingDownIsFine(ClusterApi clusterApi) throws HostStateChangeDeniedException {
        if (clusterApi.noServicesOutsideGroupIsDown()) {
            return SuspensionReasons.nothingNoteworthy();
        }

        int percentageOfServicesAllowedToBeDown = getConcurrentSuspensionLimit(clusterApi).asPercentage();
        if (clusterApi.percentageOfServicesDownIfGroupIsAllowedToBeDown() <= percentageOfServicesAllowedToBeDown) {
            return SuspensionReasons.nothingNoteworthy();
        }

        Optional<SuspensionReasons> suspensionReasons = clusterApi.reasonsForNoServicesInGroupIsUp();
        if (suspensionReasons.isPresent()) {
            return suspensionReasons.get();
        }

        throw new HostStateChangeDeniedException(
                clusterApi.getNodeGroup(),
                ENOUGH_SERVICES_UP_CONSTRAINT,
                "Suspension for service type " + clusterApi.serviceType()
                        + " would increase from " + clusterApi.percentageOfServicesDown()
                        + "% to " + clusterApi.percentageOfServicesDownIfGroupIsAllowedToBeDown()
                        + "%, over the limit of " + percentageOfServicesAllowedToBeDown + "%."
                        + clusterApi.downDescription());
    }

    @Override
    public void verifyGroupGoingDownPermanentlyIsFine(ClusterApi clusterApi)
            throws HostStateChangeDeniedException {
        // This policy is similar to verifyGroupGoingDownIsFine, except that services being down in the group
        // is no excuse to allow suspension (like it is for verifyGroupGoingDownIsFine), since if we grant
        // suspension in this case they will permanently be down/removed.

        if (clusterApi.noServicesOutsideGroupIsDown()) {
            return;
        }

        int percentageOfServicesAllowedToBeDown = getConcurrentSuspensionLimit(clusterApi).asPercentage();
        if (clusterApi.percentageOfServicesDownIfGroupIsAllowedToBeDown() <= percentageOfServicesAllowedToBeDown) {
            return;
        }

        throw new HostStateChangeDeniedException(
                clusterApi.getNodeGroup(),
                ENOUGH_SERVICES_UP_CONSTRAINT,
                "Down percentage for service type " + clusterApi.serviceType()
                        + " would increase to " + clusterApi.percentageOfServicesDownIfGroupIsAllowedToBeDown()
                        + "%, over the limit of " + percentageOfServicesAllowedToBeDown + "%."
                        + clusterApi.downDescription());
    }

    // Non-private for testing purposes
    ConcurrentSuspensionLimitForCluster getConcurrentSuspensionLimit(ClusterApi clusterApi) {
        if (clusterApi.isStorageCluster()) {
            return ConcurrentSuspensionLimitForCluster.ONE_NODE;
        }

        if (VespaModelUtil.CLUSTER_CONTROLLER_SERVICE_TYPE.equals(clusterApi.serviceType())) {
            // All nodes have all state and we need to be able to remove the half that are retired on cluster migration
            return ConcurrentSuspensionLimitForCluster.FIFTY_PERCENT;
        }

        if (VespaModelUtil.METRICS_PROXY_SERVICE_TYPE.equals(clusterApi.serviceType())) {
            return ConcurrentSuspensionLimitForCluster.ALL_NODES;
        }

        if (VespaModelUtil.ADMIN_CLUSTER_ID.equals(clusterApi.clusterId())) {
            if (VespaModelUtil.SLOBROK_SERVICE_TYPE.equals(clusterApi.serviceType())) {
                return ConcurrentSuspensionLimitForCluster.ONE_NODE;
            }

            return ConcurrentSuspensionLimitForCluster.ALL_NODES;
        }

        if (clusterApi.getApplication().applicationId().equals(VespaModelUtil.TENANT_HOST_APPLICATION_ID)) {
            return ConcurrentSuspensionLimitForCluster.TWENTY_PERCENT;
        }

        return ConcurrentSuspensionLimitForCluster.TEN_PERCENT;
    }
}
