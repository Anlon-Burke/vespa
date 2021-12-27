// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.provision.provisioning;

import com.yahoo.config.provision.ApplicationId;
import com.yahoo.config.provision.Capacity;
import com.yahoo.config.provision.ClusterResources;
import com.yahoo.config.provision.ClusterSpec;
import com.yahoo.config.provision.Environment;
import com.yahoo.config.provision.NodeResources;
import com.yahoo.config.provision.SystemName;
import com.yahoo.config.provision.Zone;
import com.yahoo.vespa.flags.PermanentFlags;
import com.yahoo.vespa.hosted.provision.NodeRepository;

import java.util.function.Function;

/**
 * Defines the policies for assigning cluster capacity in various environments
 *
 * @author bratseth
 * @see NodeResourceLimits
 */
public class CapacityPolicies {

    private final Zone zone;
    private final Function<ClusterSpec.Type, Boolean> sharedHosts;

    public CapacityPolicies(NodeRepository nodeRepository) {
        this.zone = nodeRepository.zone();
        this.sharedHosts = type -> PermanentFlags.SHARED_HOST.bindTo(nodeRepository.flagSource()).value().isEnabled(type.name());
    }

    public Capacity applyOn(Capacity capacity, ApplicationId application) {
        return capacity.withLimits(applyOn(capacity.minResources(), capacity, application),
                                   applyOn(capacity.maxResources(), capacity, application));
    }

    private ClusterResources applyOn(ClusterResources resources, Capacity capacity, ApplicationId application) {
        int nodes = decideSize(resources.nodes(), capacity.isRequired(), application.instance().isTester());
        int groups = Math.min(resources.groups(), nodes); // cannot have more groups than nodes
        var nodeResources = decideNodeResources(resources.nodeResources(), capacity.isRequired());
        return new ClusterResources(nodes, groups, nodeResources);
    }

    private int decideSize(int requested, boolean required, boolean isTester) {
        if (isTester) return 1;

        if (required) return requested;
        switch(zone.environment()) {
            case dev : case test : return 1;
            case perf : return Math.min(requested, 3);
            case staging: return requested <= 1 ? requested : Math.max(2, requested / 10);
            case prod : return requested;
            default : throw new IllegalArgumentException("Unsupported environment " + zone.environment());
        }
    }

    private NodeResources decideNodeResources(NodeResources target, boolean required) {
        if (required) return target;
        if (target.isUnspecified()) return target; // Cannot be modified

        // Dev does not cap the cpu or network of containers since usage is spotty: Allocate just a small amount exclusively
        if (zone.environment() == Environment.dev && !zone.getCloud().dynamicProvisioning())
            target = target.withVcpu(0.1).withBandwidthGbps(0.1);

        // Allow slow storage in zones which are not performance sensitive
        if (zone.system().isCd() || zone.environment() == Environment.dev || zone.environment() == Environment.test)
            target = target.with(NodeResources.DiskSpeed.any).with(NodeResources.StorageType.any).withBandwidthGbps(0.1);

        return target;
    }

    public NodeResources defaultNodeResources(ClusterSpec.Type clusterType) {
        if (clusterType == ClusterSpec.Type.admin) {
            if (zone.system() == SystemName.dev) {
                // Use small logserver in dev system
                return new NodeResources(0.1, 1, 10, 0.3);
            }
            return zone.getCloud().dynamicProvisioning() && ! sharedHosts.apply(clusterType) ?
                   new NodeResources(0.5, 4, 50, 0.3) :
                   new NodeResources(0.5, 2, 50, 0.3);
        }

        return zone.getCloud().dynamicProvisioning() ?
                new NodeResources(2.0, 8, 50, 0.3) :
                new NodeResources(1.5, 8, 50, 0.3);
    }

    /**
     * Returns whether the nodes requested can share physical host with other applications.
     * A security feature which only makes sense for prod.
     */
    public boolean decideExclusivity(Capacity capacity, boolean requestedExclusivity) {
        return requestedExclusivity && (capacity.isRequired() || zone.environment() == Environment.prod);
    }

}
