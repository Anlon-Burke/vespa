// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.provision.provisioning;

import com.yahoo.config.provision.ApplicationId;
import com.yahoo.config.provision.ApplicationTransaction;
import com.yahoo.config.provision.ClusterMembership;
import com.yahoo.config.provision.ClusterSpec;
import com.yahoo.config.provision.Flavor;
import com.yahoo.config.provision.HostSpec;
import com.yahoo.config.provision.ParentHostUnavailableException;
import com.yahoo.vespa.hosted.provision.Node;
import com.yahoo.vespa.hosted.provision.NodeList;
import com.yahoo.vespa.hosted.provision.NodeMutex;
import com.yahoo.vespa.hosted.provision.NodeRepository;
import com.yahoo.vespa.hosted.provision.applications.Application;
import com.yahoo.vespa.hosted.provision.applications.ScalingEvent;
import com.yahoo.vespa.hosted.provision.node.Agent;
import com.yahoo.vespa.hosted.provision.node.Allocation;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Performs activation of resources for an application. E.g. nodes or load balancers.
 *
 * @author bratseth
 */
class Activator {

    private final NodeRepository nodeRepository;
    private final Optional<LoadBalancerProvisioner> loadBalancerProvisioner;

    public Activator(NodeRepository nodeRepository, Optional<LoadBalancerProvisioner> loadBalancerProvisioner) {
        this.nodeRepository = nodeRepository;
        this.loadBalancerProvisioner = loadBalancerProvisioner;
    }

    /** Activate required resources for application guarded by given lock */
    public void activate(Collection<HostSpec> hosts, long generation, ApplicationTransaction transaction) {
        activateNodes(hosts, generation, transaction);
        activateLoadBalancers(hosts, transaction);
    }

    /**
     * Add operations to activates nodes for an application to the given transaction.
     * The operations are not effective until the transaction is committed.
     * <p>
     * Pre condition: The application has a possibly empty set of nodes in each of reserved and active.
     * <p>
     * Post condition: Nodes in reserved which are present in <code>hosts</code> are moved to active.
     * Nodes in active which are not present in <code>hosts</code> are moved to inactive.
     *
     * @param hosts the hosts to make the set of active nodes of this
     * @param generation the application config generation that is activated
     * @param transaction transaction with operations to commit together with any operations done within the repository,
     *                    while holding the node repository lock on this application
     */
    private void activateNodes(Collection<HostSpec> hosts, long generation, ApplicationTransaction transaction) {
        Instant activationTime = nodeRepository.clock().instant(); // Use one timestamp for all activation changes
        ApplicationId application = transaction.application();
        Set<String> hostnames = hosts.stream().map(HostSpec::hostname).collect(Collectors.toSet());
        NodeList allNodes = nodeRepository.nodes().list();
        NodeList applicationNodes = allNodes.owner(application);

        NodeList reserved = updatePortsFrom(hosts, applicationNodes.state(Node.State.reserved)
                                                                   .matching(node -> hostnames.contains(node.hostname())));
        NodeList oldActive = applicationNodes.state(Node.State.active); // All nodes active now
        NodeList continuedActive = oldActive.matching(node -> hostnames.contains(node.hostname()));
        NodeList newActive = withHostInfo(continuedActive, hosts, activationTime).and(reserved); // All nodes that will be active when this is committed
        if ( ! containsAll(hostnames, newActive))
            throw new IllegalArgumentException("Activation of " + application + " failed. " +
                                               "Could not find all requested hosts." +
                                               "\nRequested: " + hosts +
                                               "\nReserved: " + toHostNames(reserved) +
                                               "\nActive: " + toHostNames(oldActive) +
                                               "\nThis might happen if the time from reserving host to activation takes " +
                                               "longer time than reservation expiry (the hosts will then no longer be reserved)");

        validateParentHosts(application, allNodes, reserved);

        NodeList activeToRemove = oldActive.matching(node ->  ! hostnames.contains(node.hostname()));
        activeToRemove = NodeList.copyOf(activeToRemove.mapToList(Node::unretire)); // only active nodes can be retired. TODO: Move this line to deactivate
        deactivate(activeToRemove, transaction); // TODO: Pass activation time in this call and next line
        nodeRepository.nodes().activate(newActive.asList(), transaction.nested()); // activate also continued active to update node state

        rememberResourceChange(transaction, generation, activationTime,
                               oldActive.not().retired(),
                               newActive.not().retired());
        unreserveParentsOf(reserved);
    }

    private void deactivate(NodeList toDeactivate, ApplicationTransaction transaction) {
        nodeRepository.nodes().deactivate(toDeactivate.not().failing().asList(), transaction);
        nodeRepository.nodes().fail(toDeactivate.failing().asList(), transaction);
    }

    private void rememberResourceChange(ApplicationTransaction transaction, long generation, Instant at,
                                        NodeList oldNodes, NodeList newNodes) {
        Optional<Application> application = nodeRepository.applications().get(transaction.application());
        if (application.isEmpty()) return; // infrastructure app, hopefully :-|

        Map<ClusterSpec.Id, NodeList> currentNodesByCluster = newNodes.groupingBy(node -> node.allocation().get().membership().cluster().id());
        Application modified = application.get();
        for (var clusterEntry : currentNodesByCluster.entrySet()) {
            var cluster = modified.cluster(clusterEntry.getKey()).get();
            var previousResources = oldNodes.cluster(clusterEntry.getKey()).toResources();
            var currentResources = clusterEntry.getValue().toResources();
            if ( ! previousResources.justNumbers().equals(currentResources.justNumbers())) {
                cluster = cluster.with(ScalingEvent.create(previousResources, currentResources, generation, at));
            }
            if (cluster.targetResources().isPresent()
                && cluster.targetResources().get().justNumbers().equals(currentResources.justNumbers())) {
                cluster = cluster.withAutoscalingStatus("Cluster is ideally scaled within configured limits");
            }
            if (cluster != modified.cluster(clusterEntry.getKey()).get())
                modified = modified.with(cluster);
        }

        if (modified != application.get())
            nodeRepository.applications().put(modified, transaction);
    }

    /** When a tenant node is activated on a host, we can open up that host for use by others */
    private void unreserveParentsOf(NodeList nodes) {
        for (Node node : nodes) {
            if ( node.parentHostname().isEmpty()) continue;
            Optional<Node> parentNode = nodeRepository.nodes().node(node.parentHostname().get());
            if (parentNode.isEmpty()) continue;
            if (parentNode.get().reservedTo().isEmpty()) continue;

            // Above is an optimization to avoid unnecessary locking - now repeat all conditions under lock
            Optional<NodeMutex> parent = nodeRepository.nodes().lockAndGet(node.parentHostname().get());
            if (parent.isEmpty()) continue;
            try (var lock = parent.get()) {
                if (lock.node().reservedTo().isEmpty()) continue;
                nodeRepository.nodes().write(lock.node().withoutReservedTo(), lock);
            }
        }
    }

    /** Activate load balancers */
    private void activateLoadBalancers(Collection<HostSpec> hosts, ApplicationTransaction transaction) {
        loadBalancerProvisioner.ifPresent(provisioner -> provisioner.activate(allClustersOf(hosts), transaction));
    }

    private static Set<ClusterSpec> allClustersOf(Collection<HostSpec> hosts) {
        return hosts.stream()
                    .map(HostSpec::membership)
                    .flatMap(Optional::stream)
                    .map(ClusterMembership::cluster)
                    .collect(Collectors.toUnmodifiableSet());
    }

    private static void validateParentHosts(ApplicationId application, NodeList nodes, NodeList potentialChildren) {
        Set<String> parentHostnames = potentialChildren.stream()
                .map(Node::parentHostname)
                .flatMap(Optional::stream)
                .collect(Collectors.toSet());

        Set<String> nonActiveHosts = nodes.asList().stream()
                .filter(node -> parentHostnames.contains(node.hostname()))
                .filter(node -> node.state() != Node.State.active)
                .map(Node::hostname)
                .collect(Collectors.toSet());

        if (nonActiveHosts.size() > 0) {
            long numActive = parentHostnames.size() - nonActiveHosts.size();
            var messageBuilder = new StringBuilder()
                    .append(numActive).append("/").append(parentHostnames.size())
                    .append(" hosts for ")
                    .append(application)
                    .append(" have completed provisioning and bootstrapping, still waiting for ");

            if (nonActiveHosts.size() <= 5) {
                messageBuilder.append(nonActiveHosts.stream()
                        .sorted()
                        .collect(Collectors.joining(", ")));
            } else {
                messageBuilder.append(nonActiveHosts.stream()
                        .sorted()
                        .limit(3)
                        .collect(Collectors.joining(", ")))
                        .append(", and others");
            }
            var message = messageBuilder.toString();

            throw new ParentHostUnavailableException(message);
        }
    }

    private Set<String> toHostNames(NodeList nodes) {
        return nodes.stream().map(Node::hostname).collect(Collectors.toSet());
    }

    private boolean containsAll(Set<String> hosts, NodeList nodes) {
        Set<String> notFoundHosts = new HashSet<>(hosts);
        for (Node node : nodes)
            notFoundHosts.remove(node.hostname());
        return notFoundHosts.isEmpty();
    }

    /** Returns the input nodes with the changes resulting from applying the settings in hosts to the given list of nodes. */
    private NodeList withHostInfo(NodeList nodes, Collection<HostSpec> hosts, Instant at) {
        List<Node> updated = new ArrayList<>();
        for (Node node : nodes) {
            HostSpec hostSpec = getHost(node.hostname(), hosts);
            node = hostSpec.membership().get().retired() ? node.retire(at) : node.unretire();
            if (! hostSpec.advertisedResources().equals(node.resources())) // A resized node
                node = node.with(new Flavor(hostSpec.advertisedResources()), Agent.application, at);
            Allocation allocation = node.allocation().get()
                                        .with(hostSpec.membership().get())
                                        .withRequestedResources(hostSpec.requestedResources()
                                                                        .orElse(node.resources()));
            if (hostSpec.networkPorts().isPresent())
                allocation = allocation.withNetworkPorts(hostSpec.networkPorts().get());
            node = node.with(allocation);
            updated.add(node);
        }
        return NodeList.copyOf(updated);
    }

    /**
     * Returns the input nodes with any port allocations from the hosts
     */
    private NodeList updatePortsFrom(Collection<HostSpec> hosts, NodeList nodes) {
        List<Node> updated = new ArrayList<>();
        for (Node node : nodes) {
            HostSpec hostSpec = getHost(node.hostname(), hosts);
            Allocation allocation = node.allocation().get();
            if (hostSpec.networkPorts().isPresent()) {
                allocation = allocation.withNetworkPorts(hostSpec.networkPorts().get());
                node = node.with(allocation);
            }
            updated.add(node);
        }
        return NodeList.copyOf(updated);
    }

    private HostSpec getHost(String hostname, Collection<HostSpec> fromHosts) {
        for (HostSpec host : fromHosts)
            if (host.hostname().equals(hostname))
                return host;
        return null;
    }

}
