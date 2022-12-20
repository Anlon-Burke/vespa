// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.provision.provisioning;

import com.yahoo.config.provision.ClusterSpec;
import com.yahoo.config.provision.Environment;
import com.yahoo.config.provision.NodeResources;
import com.yahoo.config.provision.NodeType;
import com.yahoo.config.provision.Zone;
import com.yahoo.vespa.hosted.provision.NodeRepository;

import java.util.Locale;

/**
 * Defines the resource limits for nodes in various zones
 *
 * @author bratseth
 * @see CapacityPolicies
 */
public class NodeResourceLimits {

    private final NodeRepository nodeRepository;

    public NodeResourceLimits(NodeRepository nodeRepository) {
        this.nodeRepository = nodeRepository;
    }

    /** Validates the resources applications ask for (which are in "advertised" resource space) */
    public void ensureWithinAdvertisedLimits(String type, NodeResources requested, ClusterSpec cluster) {
        if (requested.isUnspecified()) return;

        if (requested.vcpu() < minAdvertisedVcpu(cluster))
            illegal(type, "vcpu", "", cluster, requested.vcpu(), minAdvertisedVcpu(cluster));
        if (requested.memoryGb() < minAdvertisedMemoryGb(cluster))
            illegal(type, "memoryGb", "Gb", cluster, requested.memoryGb(), minAdvertisedMemoryGb(cluster));
        if (requested.diskGb() < minAdvertisedDiskGb(requested, cluster.isExclusive()))
            illegal(type, "diskGb", "Gb", cluster, requested.diskGb(), minAdvertisedDiskGb(requested, cluster.isExclusive()));
    }

    /** Returns whether the real resources we'll end up with on a given tenant node are within limits */
    public boolean isWithinRealLimits(NodeCandidate candidateNode, ClusterSpec cluster) {
        if (candidateNode.type() != NodeType.tenant) return true; // Resource limits only apply to tenant nodes
        return isWithinRealLimits(nodeRepository.resourcesCalculator().realResourcesOf(candidateNode, nodeRepository),
                                  cluster);
    }

    /** Returns whether the real resources we'll end up with on a given tenant node are within limits */
    public boolean isWithinRealLimits(NodeResources realResources, ClusterSpec cluster) {
        if (realResources.isUnspecified()) return true;

        if (realResources.vcpu() < minRealVcpu(cluster)) return false;
        if (realResources.memoryGb() < minRealMemoryGb(cluster)) return false;
        if (realResources.diskGb() < minRealDiskGb()) return false;
       return true;
    }

    public NodeResources enlargeToLegal(NodeResources requested, ClusterSpec cluster, boolean exclusive) {
        if (requested.isUnspecified()) return requested;

        return requested.withVcpu(Math.max(minAdvertisedVcpu(cluster), requested.vcpu()))
                        .withMemoryGb(Math.max(minAdvertisedMemoryGb(cluster), requested.memoryGb()))
                        .withDiskGb(Math.max(minAdvertisedDiskGb(requested, exclusive), requested.diskGb()));
    }

    private double minAdvertisedVcpu(ClusterSpec cluster) {
        if (zone().environment() == Environment.dev && ! nodeRepository.exclusiveAllocation(cluster)) return 0.1;
        if (cluster.type().isContent() && zone().environment().isProduction()) return 1.0;
        if (cluster.type() == ClusterSpec.Type.admin) return 0.1;
        return 0.5;
    }

    private double minAdvertisedMemoryGb(ClusterSpec cluster) {
        if (cluster.type() == ClusterSpec.Type.admin) return 1;
        return 4;
    }

    private double minAdvertisedDiskGb(NodeResources requested, boolean exclusive) {
        return minRealDiskGb() + reservedDiskSpaceGb(requested.storageType(), exclusive);
    }

    // Note: Assumes node type 'host'
    private long reservedDiskSpaceGb(NodeResources.StorageType storageType, boolean exclusive) {
        if (storageType == NodeResources.StorageType.local && ! zone().cloud().allowHostSharing())
            return nodeRepository.resourcesCalculator().reservedDiskSpaceInBase2Gb(NodeType.host, ! exclusive);
        else
            return 4;
    }

    private double minRealVcpu(ClusterSpec cluster) { return minAdvertisedVcpu(cluster); }

    private double minRealMemoryGb(ClusterSpec cluster) {
        return minAdvertisedMemoryGb(cluster) - 1.7;
    }

    private double minRealDiskGb() { return 6; }

    private Zone zone() { return nodeRepository.zone(); }

    private void illegal(String type, String resource, String unit, ClusterSpec cluster, double requested, double minAllowed) {
        if ( ! unit.isEmpty())
            unit = " " + unit;
        String message = String.format(Locale.ENGLISH,
                                       "%s cluster '%s': " + type + " " + resource +
                                       " size is %.2f%s but must be at least %.2f%s",
                                       cluster.type().name(), cluster.id().value(), requested, unit, minAllowed, unit);
        throw new IllegalArgumentException(message);
    }

}
