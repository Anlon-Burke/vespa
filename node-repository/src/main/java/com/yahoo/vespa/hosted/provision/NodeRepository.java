// Copyright 2020 Oath Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.provision;

import com.google.inject.Inject;
import com.yahoo.collections.ListMap;
import com.yahoo.component.AbstractComponent;
import com.yahoo.component.Version;
import com.yahoo.concurrent.maintenance.JobControl;
import com.yahoo.config.provision.ApplicationId;
import com.yahoo.config.provision.ApplicationTransaction;
import com.yahoo.config.provision.DockerImage;
import com.yahoo.config.provision.Flavor;
import com.yahoo.config.provision.NodeFlavors;
import com.yahoo.config.provision.NodeType;
import com.yahoo.config.provision.Zone;
import com.yahoo.config.provisioning.NodeRepositoryConfig;
import com.yahoo.transaction.Mutex;
import com.yahoo.transaction.NestedTransaction;
import com.yahoo.vespa.curator.Curator;
import com.yahoo.vespa.flags.FlagSource;
import com.yahoo.vespa.hosted.provision.Node.State;
import com.yahoo.vespa.hosted.provision.applications.Applications;
import com.yahoo.vespa.hosted.provision.lb.LoadBalancer;
import com.yahoo.vespa.hosted.provision.lb.LoadBalancerId;
import com.yahoo.vespa.hosted.provision.lb.LoadBalancerInstance;
import com.yahoo.vespa.hosted.provision.lb.LoadBalancerList;
import com.yahoo.vespa.hosted.provision.maintenance.InfrastructureVersions;
import com.yahoo.vespa.hosted.provision.maintenance.NodeFailer;
import com.yahoo.vespa.hosted.provision.maintenance.PeriodicApplicationMaintainer;
import com.yahoo.vespa.hosted.provision.node.Agent;
import com.yahoo.vespa.hosted.provision.node.Allocation;
import com.yahoo.vespa.hosted.provision.node.IP;
import com.yahoo.vespa.hosted.provision.node.NodeAcl;
import com.yahoo.vespa.hosted.provision.node.filter.NodeFilter;
import com.yahoo.vespa.hosted.provision.node.filter.NodeListFilter;
import com.yahoo.vespa.hosted.provision.node.filter.StateFilter;
import com.yahoo.vespa.hosted.provision.os.OsVersions;
import com.yahoo.vespa.hosted.provision.persistence.CuratorDatabaseClient;
import com.yahoo.vespa.hosted.provision.persistence.DnsNameResolver;
import com.yahoo.vespa.hosted.provision.persistence.JobControlFlags;
import com.yahoo.vespa.hosted.provision.persistence.NameResolver;
import com.yahoo.vespa.hosted.provision.provisioning.ContainerImages;
import com.yahoo.vespa.hosted.provision.provisioning.FirmwareChecks;
import com.yahoo.vespa.hosted.provision.provisioning.HostResourcesCalculator;
import com.yahoo.vespa.hosted.provision.provisioning.ProvisionServiceProvider;
import com.yahoo.vespa.hosted.provision.restapi.NotFoundException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BiFunction;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The hosted Vespa production node repository, which stores its state in Zookeeper.
 * The node repository knows about all nodes in a zone, their states and manages all transitions between
 * node states.
 * <p>
 * Node repo locking: Locks must be acquired before making changes to the set of nodes, or to the content
 * of the nodes.
 * Unallocated states use a single lock, while application level locks are used for all allocated states
 * such that applications can mostly change in parallel.
 * If both locks are needed acquire the application lock first, then the unallocated lock.
 * <p>
 * Changes to the set of active nodes must be accompanied by changes to the config model of the application.
 * Such changes are not handled by the node repository but by the classes calling it - see
 * {@link com.yahoo.vespa.hosted.provision.provisioning.NodeRepositoryProvisioner} for such changes initiated
 * by the application package and {@link PeriodicApplicationMaintainer}
 * for changes initiated by the node repository.
 * Refer to {@link com.yahoo.vespa.hosted.provision.maintenance.NodeRepositoryMaintenance} for timing details
 * of the node state transitions.
 *
 * @author bratseth
 */
// Node state transitions:
// 1) (new) | deprovisioned - > provisioned -> (dirty ->) ready -> reserved -> active -> inactive -> dirty -> ready
// 2) inactive -> reserved | parked
// 3) reserved -> dirty
// 4) * -> failed | parked -> (breakfixed) -> dirty | active | deprovisioned
// 5) deprovisioned -> (forgotten)
// Nodes have an application assigned when in states reserved, active and inactive.
// Nodes might have an application assigned in dirty.
public class NodeRepository extends AbstractComponent {

    private static final Logger log = Logger.getLogger(NodeRepository.class.getName());

    private final CuratorDatabaseClient db;
    private final Clock clock;
    private final Zone zone;
    private final NodeFlavors flavors;
    private final HostResourcesCalculator resourcesCalculator;
    private final NameResolver nameResolver;
    private final OsVersions osVersions;
    private final InfrastructureVersions infrastructureVersions;
    private final FirmwareChecks firmwareChecks;
    private final ContainerImages containerImages;
    private final JobControl jobControl;
    private final Applications applications;
    private final int spareCount;

    /**
     * Creates a node repository from a zookeeper provider.
     * This will use the system time to make time-sensitive decisions
     */
    @Inject
    public NodeRepository(NodeRepositoryConfig config,
                          NodeFlavors flavors,
                          ProvisionServiceProvider provisionServiceProvider,
                          Curator curator,
                          Zone zone,
                          FlagSource flagSource) {
        this(flavors,
             provisionServiceProvider,
             curator,
             Clock.systemUTC(),
             zone,
             new DnsNameResolver(),
             DockerImage.fromString(config.containerImage())
                        .withReplacedBy(DockerImage.fromString(config.containerImageReplacement())),
             flagSource,
             config.useCuratorClientCache(),
             zone.environment().isProduction() && !zone.getCloud().dynamicProvisioning() ? 1 : 0,
             config.nodeCacheSize());
    }

    /**
     * Creates a node repository from a zookeeper provider and a clock instance
     * which will be used for time-sensitive decisions.
     */
    public NodeRepository(NodeFlavors flavors,
                          ProvisionServiceProvider provisionServiceProvider,
                          Curator curator,
                          Clock clock,
                          Zone zone,
                          NameResolver nameResolver,
                          DockerImage containerImage,
                          FlagSource flagSource,
                          boolean useCuratorClientCache,
                          int spareCount,
                          long nodeCacheSize) {
        // TODO (valerijf): Uncomment when exception for prod.cd-aws is removed
//        if (provisionServiceProvider.getHostProvisioner().isPresent() != zone.getCloud().dynamicProvisioning())
//            throw new IllegalArgumentException(String.format(
//                    "dynamicProvisioning property must be 1-to-1 with availability of HostProvisioner, was: dynamicProvisioning=%s, hostProvisioner=%s",
//                    zone.getCloud().dynamicProvisioning(), provisionServiceProvider.getHostProvisioner().map(__ -> "present").orElse("empty")));

        this.db = new CuratorDatabaseClient(flavors, curator, clock, zone, useCuratorClientCache, nodeCacheSize);
        this.zone = zone;
        this.clock = clock;
        this.flavors = flavors;
        this.resourcesCalculator = provisionServiceProvider.getHostResourcesCalculator();
        this.nameResolver = nameResolver;
        this.osVersions = new OsVersions(this);
        this.infrastructureVersions = new InfrastructureVersions(db);
        this.firmwareChecks = new FirmwareChecks(db, clock);
        this.containerImages = new ContainerImages(db, containerImage);
        this.jobControl = new JobControl(new JobControlFlags(db, flagSource));
        this.applications = new Applications(db);
        this.spareCount = spareCount;
        rewriteNodes();
    }

    /** Read and write all nodes to make sure they are stored in the latest version of the serialized format */
    private void rewriteNodes() {
        Instant start = clock.instant();
        int nodesWritten = 0;
        for (State state : State.values()) {
            List<Node> nodes = db.readNodes(state);
            // TODO(mpolden): This should take the lock before writing
            db.writeTo(state, nodes, Agent.system, Optional.empty());
            nodesWritten += nodes.size();
        }
        Instant end = clock.instant();
        log.log(Level.INFO, String.format("Rewrote %d nodes in %s", nodesWritten, Duration.between(start, end)));
    }

    /** Returns the curator database client used by this */
    public CuratorDatabaseClient database() { return db; }

    /** @return The name resolver used to resolve hostname and ip addresses */
    public NameResolver nameResolver() { return nameResolver; }

    /** Returns the OS versions to use for nodes in this */
    public OsVersions osVersions() { return osVersions; }

    /** Returns the infrastructure versions to use for nodes in this */
    public InfrastructureVersions infrastructureVersions() { return infrastructureVersions; }

    /** Returns the status of firmware checks for hosts managed by this. */
    public FirmwareChecks firmwareChecks() { return firmwareChecks; }

    /** Returns the docker images to use for nodes in this. */
    public ContainerImages containerImages() { return containerImages; }

    /** Returns the status of maintenance jobs managed by this. */
    public JobControl jobControl() { return jobControl; }

    /** Returns this node repo's view of the applications deployed to it */
    public Applications applications() { return applications; }

    public NodeFlavors flavors() {
        return flavors;
    }

    public HostResourcesCalculator resourcesCalculator() { return resourcesCalculator; }

    /** The number of nodes we should ensure has free capacity for node failures whenever possible */
    public int spareCount() { return spareCount; }

    // ---------------- Query API ----------------------------------------------------------------

    /**
     * Finds and returns the node with the hostname in any of the given states, or empty if not found 
     *
     * @param hostname the full host name of the node
     * @param inState the states the node may be in. If no states are given, it will be returned from any state
     * @return the node, or empty if it was not found in any of the given states
     */
    public Optional<Node> getNode(String hostname, State ... inState) {
        return db.readNode(hostname, inState);
    }

    /**
     * Returns all nodes in any of the given states.
     *
     * @param inState the states to return nodes from. If no states are given, all nodes of the given type are returned
     * @return the node, or empty if it was not found in any of the given states
     */
    public List<Node> getNodes(State ... inState) {
        return new ArrayList<>(db.readNodes(inState));
    }
    /**
     * Finds and returns the nodes of the given type in any of the given states.
     *
     * @param type the node type to return
     * @param inState the states to return nodes from. If no states are given, all nodes of the given type are returned
     * @return the node, or empty if it was not found in any of the given states
     */
    public List<Node> getNodes(NodeType type, State ... inState) {
        return db.readNodes(inState).stream().filter(node -> node.type().equals(type)).collect(Collectors.toList());
    }

    /** Returns a filterable list of nodes in this repository in any of the given states */
    public NodeList list(State ... inState) {
        return NodeList.copyOf(getNodes(inState));
    }

    public NodeList list(ApplicationId application, State ... inState) {
        return NodeList.copyOf(getNodes(application, inState));
    }

    /** Returns a filterable list of all nodes of an application */
    public NodeList list(ApplicationId application) {
        return NodeList.copyOf(getNodes(application));
    }

    /** Returns a locked list of all nodes in this repository */
    public LockedNodeList list(Mutex lock) {
        return new LockedNodeList(getNodes(), lock);
    }

    /** Returns a filterable list of all load balancers in this repository */
    public LoadBalancerList loadBalancers() {
        return loadBalancers((ignored) -> true);
    }

    /** Returns a filterable list of load balancers belonging to given application */
    public LoadBalancerList loadBalancers(ApplicationId application) {
        return loadBalancers((id) -> id.application().equals(application));
    }

    private LoadBalancerList loadBalancers(Predicate<LoadBalancerId> predicate) {
        return LoadBalancerList.copyOf(db.readLoadBalancers(predicate).values());
    }

    public List<Node> getNodes(ApplicationId id, State ... inState) { return db.readNodes(id, inState); }
    public List<Node> getInactive() { return db.readNodes(State.inactive); }
    public List<Node> getFailed() { return db.readNodes(State.failed); }

    /**
     * Returns the ACL for the node (trusted nodes, networks and ports)
     */
    private NodeAcl getNodeAcl(Node node, NodeList candidates) {
        Set<Node> trustedNodes = new TreeSet<>(Comparator.comparing(Node::hostname));
        Set<Integer> trustedPorts = new LinkedHashSet<>();
        Set<String> trustedNetworks = new LinkedHashSet<>();

        // For all cases below, trust:
        // - SSH: If the Docker host has one container, and it is using the Docker host's network namespace,
        //   opening up SSH to the Docker host is done here as a trusted port. For simplicity all nodes have
        //   SSH opened (which is safe for 2 reasons: SSH daemon is not run inside containers, and NPT networks
        //   will (should) not forward port 22 traffic to container).
        // - parent host (for health checks and metrics)
        // - nodes in same application
        // - load balancers allocated to application
        trustedPorts.add(22);
        candidates.parentOf(node).ifPresent(trustedNodes::add);
        node.allocation().ifPresent(allocation -> {
            trustedNodes.addAll(candidates.owner(allocation.owner()).asList());
            loadBalancers(allocation.owner()).asList().stream()
                                             .map(LoadBalancer::instance)
                                             .map(LoadBalancerInstance::networks)
                                             .forEach(trustedNetworks::addAll);
        });

        switch (node.type()) {
            case tenant:
                // Tenant nodes in other states than ready, trust:
                // - config servers
                // - proxy nodes
                // - parents of the nodes in the same application: If some of the nodes are on a different IP versions
                //   or only a subset of them are dual-stacked, the communication between the nodes may be NATed
                //   with via parent's IP address.
                trustedNodes.addAll(candidates.nodeType(NodeType.config).asList());
                trustedNodes.addAll(candidates.nodeType(NodeType.proxy).asList());
                node.allocation().ifPresent(allocation ->
                        trustedNodes.addAll(candidates.parentsOf(candidates.owner(allocation.owner())).asList()));

                if (node.state() == State.ready) {
                    // Tenant nodes in state ready, trust:
                    // - All tenant nodes in zone. When a ready node is allocated to a an application there's a brief
                    //   window where current ACLs have not yet been applied on the node. To avoid service disruption
                    //   during this window, ready tenant nodes trust all other tenant nodes.
                    trustedNodes.addAll(candidates.nodeType(NodeType.tenant).asList());
                }
                break;

            case config:
                // Config servers trust:
                // - all nodes
                // - port 4443 from the world
                trustedNodes.addAll(candidates.asList());
                trustedPorts.add(4443);
                break;

            case proxy:
                // Proxy nodes trust:
                // - config servers
                // - all connections from the world on 4080 (insecure tb removed), and 4443
                trustedNodes.addAll(candidates.nodeType(NodeType.config).asList());
                trustedPorts.add(443);
                trustedPorts.add(4080);
                trustedPorts.add(4443);
                break;

            case controller:
                // Controllers:
                // - port 4443 (HTTPS + Athenz) from the world
                // - port 443 (HTTPS + Okta) from the world
                // - port 80 (HTTP) from the world - for redirect to HTTPS/443 only
                trustedPorts.add(4443);
                trustedPorts.add(443);
                trustedPorts.add(80);
                break;

            default:
                illegal("Don't know how to create ACL for " + node + " of type " + node.type());
        }

        return new NodeAcl(node, trustedNodes, trustedNetworks, trustedPorts);
    }

    /**
     * Creates a list of node ACLs which identify which nodes the given node should trust
     *
     * @param node Node for which to generate ACLs
     * @param children Return ACLs for the children of the given node (e.g. containers on a Docker host)
     * @return List of node ACLs
     */
    public List<NodeAcl> getNodeAcls(Node node, boolean children) {
        NodeList candidates = list();
        if (children) {
            return candidates.childrenOf(node).asList().stream()
                             .map(childNode -> getNodeAcl(childNode, candidates))
                             .collect(Collectors.toUnmodifiableList());
        }
        return List.of(getNodeAcl(node, candidates));
    }

    /**
     * Returns whether the zone managed by this node repository seems to be working.
     * If too many nodes are not responding, there is probably some zone-wide issue
     * and we should probably refrain from making changes to it.
     */
    public boolean isWorking() {
        NodeList activeNodes = list(State.active);
        if (activeNodes.size() <= 5) return true; // Not enough data to decide
        NodeList downNodes = activeNodes.down();
        return ! ( (double)downNodes.size() / (double)activeNodes.size() > 0.2 );
    }

    // ----------------- Node lifecycle -----------------------------------------------------------

    /** Adds a list of newly created docker container nodes to the node repository as <i>reserved</i> nodes */
    public List<Node> addDockerNodes(LockedNodeList nodes) {
        for (Node node : nodes) {
            if ( ! node.flavor().getType().equals(Flavor.Type.DOCKER_CONTAINER))
                illegal("Cannot add " + node + ": This is not a docker node");
            if ( ! node.allocation().isPresent())
                illegal("Cannot add " + node + ": Docker containers needs to be allocated");
            Optional<Node> existing = getNode(node.hostname());
            if (existing.isPresent())
                illegal("Cannot add " + node + ": A node with this name already exists (" +
                        existing.get() + ", " + existing.get().history() + "). Node to be added: " +
                        node + ", " + node.history());
        }
        return db.addNodesInState(nodes.asList(), State.reserved, Agent.system);
    }

    /**
     * Adds a list of (newly created) nodes to the node repository as <i>provisioned</i> nodes.
     * If any of the nodes already exists in the deprovisioned state, the new node will be merged
     * with the history of that node.
     */
    public List<Node> addNodes(List<Node> nodes, Agent agent) {
        try (Mutex lock = lockUnallocated()) {
            List<Node> nodesToAdd =  new ArrayList<>();
            List<Node> nodesToRemove = new ArrayList<>();
            for (int i = 0; i < nodes.size(); i++) {
                var node = nodes.get(i);

                // Check for duplicates
                for (int j = 0; j < i; j++) {
                    if (node.equals(nodes.get(j)))
                        illegal("Cannot add nodes: " + node + " is duplicated in the argument list");
                }

                Optional<Node> existing = getNode(node.hostname());
                if (existing.isPresent()) {
                    if (existing.get().state() != State.deprovisioned)
                        illegal("Cannot add " + node + ": A node with this name already exists");
                    node = node.with(existing.get().history());
                    node = node.with(existing.get().reports());
                    node = node.with(node.status().withFailCount(existing.get().status().failCount()));
                    if (existing.get().status().firmwareVerifiedAt().isPresent())
                        node = node.with(node.status().withFirmwareVerifiedAt(existing.get().status().firmwareVerifiedAt().get()));
                    nodesToRemove.add(existing.get());
                }

                nodesToAdd.add(node);
            }
            List<Node> resultingNodes = db.addNodesInState(IP.Config.verify(nodesToAdd, list(lock)), State.provisioned, agent);
            db.removeNodes(nodesToRemove);
            return resultingNodes;
        }
    }

    /** Sets a list of nodes ready and returns the nodes in the ready state */
    public List<Node> setReady(List<Node> nodes, Agent agent, String reason) {
        try (Mutex lock = lockUnallocated()) {
            List<Node> nodesWithResetFields = nodes.stream()
                    .map(node -> {
                        if (node.state() != State.provisioned && node.state() != State.dirty)
                            illegal("Can not set " + node + " ready. It is not provisioned or dirty.");
                        if (node.type() == NodeType.host && node.ipConfig().pool().getIpSet().isEmpty())
                            illegal("Can not set host " + node + " ready. Its IP address pool is empty.");
                        return node.withWantToRetire(false, false, Agent.system, clock.instant());
                    })
                    .collect(Collectors.toList());

            return db.writeTo(State.ready, nodesWithResetFields, agent, Optional.of(reason));
        }
    }

    public Node setReady(String hostname, Agent agent, String reason) {
        Node nodeToReady = getNode(hostname).orElseThrow(() ->
                new NoSuchNodeException("Could not move " + hostname + " to ready: Node not found"));

        if (nodeToReady.state() == State.ready) return nodeToReady;
        return setReady(List.of(nodeToReady), agent, reason).get(0);
    }

    /** Reserve nodes. This method does <b>not</b> lock the node repository */
    public List<Node> reserve(List<Node> nodes) { 
        return db.writeTo(State.reserved, nodes, Agent.application, Optional.empty());
    }

    /** Activate nodes. This method does <b>not</b> lock the node repository */
    public List<Node> activate(List<Node> nodes, NestedTransaction transaction) {
        return db.writeTo(State.active, nodes, Agent.application, Optional.empty(), transaction);
    }

    /**
     * Sets a list of nodes to have their allocation removable (active to inactive) in the node repository.
     *
     * @param application the application the nodes belong to
     * @param nodes the nodes to make removable. These nodes MUST be in the active state.
     */
    public void setRemovable(ApplicationId application, List<Node> nodes) {
        try (Mutex lock = lock(application)) {
            List<Node> removableNodes =
                nodes.stream().map(node -> node.with(node.allocation().get().removable(true)))
                              .collect(Collectors.toList());
            write(removableNodes, lock);
        }
    }

    /**
     * Deactivates these nodes in a transaction and returns the nodes in the new state which will hold if the
     * transaction commits.
     */
    public List<Node> deactivate(List<Node> nodes, ApplicationTransaction transaction) {
        return db.writeTo(State.inactive, nodes, Agent.application, Optional.empty(), transaction.nested());
    }

    /** Removes this application: Active nodes are deactivated while all non-active nodes are set dirty. */
    public void remove(ApplicationTransaction transaction) {
        NodeList applicationNodes = list(transaction.application());
        NodeList activeNodes = applicationNodes.state(State.active);
        deactivate(activeNodes.asList(), transaction);
        db.writeTo(State.dirty,
                   applicationNodes.except(activeNodes.asSet()).asList(),
                   Agent.system,
                   Optional.of("Application is removed"),
                   transaction.nested());
        applications.remove(transaction);
    }

    /** Move nodes to the dirty state */
    public List<Node> setDirty(List<Node> nodes, Agent agent, String reason) {
        return performOn(NodeListFilter.from(nodes), (node, lock) -> setDirty(node, agent, reason));
    }

    /**
     * Set a node dirty, allowed if it is in the provisioned, inactive, failed or parked state.
     * Use this to clean newly provisioned nodes or to recycle failed nodes which have been repaired or put on hold.
     *
     * @throws IllegalArgumentException if the node has hardware failure
     */
    public Node setDirty(Node node, Agent agent, String reason) {
        return db.writeTo(State.dirty, node, agent, Optional.of(reason));
    }


    public List<Node> dirtyRecursively(String hostname, Agent agent, String reason) {
        Node nodeToDirty = getNode(hostname).orElseThrow(() ->
                new IllegalArgumentException("Could not deallocate " + hostname + ": Node not found"));

        List<Node> nodesToDirty =
                (nodeToDirty.type().isHost() ?
                        Stream.concat(list().childrenOf(hostname).asList().stream(), Stream.of(nodeToDirty)) :
                        Stream.of(nodeToDirty))
                .filter(node -> node.state() != State.dirty)
                .collect(Collectors.toList());

        List<String> hostnamesNotAllowedToDirty = nodesToDirty.stream()
                .filter(node -> node.state() != State.provisioned)
                .filter(node -> node.state() != State.failed)
                .filter(node -> node.state() != State.parked)
                .filter(node -> node.state() != State.breakfixed)
                .map(Node::hostname)
                .collect(Collectors.toList());
        if ( ! hostnamesNotAllowedToDirty.isEmpty())
            illegal("Could not deallocate " + nodeToDirty + ": " +
                    hostnamesNotAllowedToDirty + " are not in states [provisioned, failed, parked, breakfixed]");

        return nodesToDirty.stream().map(node -> setDirty(node, agent, reason)).collect(Collectors.toList());
    }

    /**
     * Fails this node and returns it in its new state.
     *
     * @return the node in its new state
     * @throws NoSuchNodeException if the node is not found
     */
    public Node fail(String hostname, Agent agent, String reason) {
        return move(hostname, true, State.failed, agent, Optional.of(reason));
    }

    /**
     * Fails all the nodes that are children of hostname before finally failing the hostname itself.
     *
     * @return List of all the failed nodes in their new state
     */
    public List<Node> failRecursively(String hostname, Agent agent, String reason) {
        return moveRecursively(hostname, State.failed, agent, Optional.of(reason));
    }

    /**
     * Parks this node and returns it in its new state.
     *
     * @return the node in its new state
     * @throws NoSuchNodeException if the node is not found
     */
    public Node park(String hostname, boolean keepAllocation, Agent agent, String reason) {
        return move(hostname, keepAllocation, State.parked, agent, Optional.of(reason));
    }

    /**
     * Parks all the nodes that are children of hostname before finally parking the hostname itself.
     *
     * @return List of all the parked nodes in their new state
     */
    public List<Node> parkRecursively(String hostname, Agent agent, String reason) {
        return moveRecursively(hostname, State.parked, agent, Optional.of(reason));
    }

    /**
     * Moves a previously failed or parked node back to the active state.
     *
     * @return the node in its new state
     * @throws NoSuchNodeException if the node is not found
     */
    public Node reactivate(String hostname, Agent agent, String reason) {
        return move(hostname, true, State.active, agent, Optional.of(reason));
    }

    /**
     * Moves a host to breakfixed state, removing any children.
     */
    public List<Node> breakfixRecursively(String hostname, Agent agent, String reason) {
        Node node = getNode(hostname).orElseThrow(() ->
                new NoSuchNodeException("Could not breakfix " + hostname + ": Node not found"));

        try (Mutex lock = lockUnallocated()) {
            requireBreakfixable(node);
            List<Node> removed = removeChildren(node, false);
            removed.add(move(node, State.breakfixed, agent, Optional.of(reason)));
            return removed;
        }
    }

    private List<Node> moveRecursively(String hostname, State toState, Agent agent, Optional<String> reason) {
        List<Node> moved = list().childrenOf(hostname).asList().stream()
                                         .map(child -> move(child, toState, agent, reason))
                                         .collect(Collectors.toList());

        moved.add(move(hostname, true, toState, agent, reason));
        return moved;
    }

    private Node move(String hostname, boolean keepAllocation, State toState, Agent agent, Optional<String> reason) {
        Node node = getNode(hostname).orElseThrow(() ->
                new NoSuchNodeException("Could not move " + hostname + " to " + toState + ": Node not found"));

        if (!keepAllocation && node.allocation().isPresent()) {
            node = node.withoutAllocation();
        }

        return move(node, toState, agent, reason);
    }

    private Node move(Node node, State toState, Agent agent, Optional<String> reason) {
        if (toState == Node.State.active && node.allocation().isEmpty())
            illegal("Could not set " + node + " active. It has no allocation.");

        // TODO: Work out a safe lock acquisition strategy for moves, e.g. migrate to lockNode.
        try (Mutex lock = lock(node)) {
            if (toState == State.active) {
                for (Node currentActive : getNodes(node.allocation().get().owner(), State.active)) {
                    if (node.allocation().get().membership().cluster().equals(currentActive.allocation().get().membership().cluster())
                        && node.allocation().get().membership().index() == currentActive.allocation().get().membership().index())
                        illegal("Could not set " + node + " active: Same cluster and index as " + currentActive);
                }
            }
            return db.writeTo(toState, node, agent, reason);
        }
    }

    /*
     * This method is used by the REST API to handle readying nodes for new allocations. For tenant docker
     * containers this will remove the node from node repository, otherwise the node will be moved to state ready.
     */
    public Node markNodeAvailableForNewAllocation(String hostname, Agent agent, String reason) {
        Node node = getNode(hostname).orElseThrow(() -> new NotFoundException("No node with hostname '" + hostname + "'"));
        if (node.flavor().getType() == Flavor.Type.DOCKER_CONTAINER && node.type() == NodeType.tenant) {
            if (node.state() != State.dirty)
                illegal("Cannot make " + node  + " available for new allocation as it is not in state [dirty]");
            return removeRecursively(node, true).get(0);
        }

        if (node.state() == State.ready) return node;

        Node parentHost = node.parentHostname().flatMap(this::getNode).orElse(node);
        List<String> failureReasons = NodeFailer.reasonsToFailParentHost(parentHost);
        if ( ! failureReasons.isEmpty())
            illegal(node + " cannot be readied because it has hard failures: " + failureReasons);

        return setReady(List.of(node), agent, reason).get(0);
    }

    /**
     * Removes all the nodes that are children of hostname before finally removing the hostname itself.
     *
     * @return a List of all the nodes that have been removed or (for hosts) deprovisioned
     */
    public List<Node> removeRecursively(String hostname) {
        Node node = getNode(hostname).orElseThrow(() -> new NotFoundException("No node with hostname '" + hostname + "'"));
        return removeRecursively(node, false);
    }

    public List<Node> removeRecursively(Node node, boolean force) {
        try (Mutex lock = lockUnallocated()) {
            requireRemovable(node, false, force);

            if (node.type().isHost()) {
                List<Node> removed = removeChildren(node, force);
                if (zone.getCloud().dynamicProvisioning() || node.type() != NodeType.host)
                    db.removeNodes(List.of(node));
                else {
                    node = node.with(IP.Config.EMPTY);
                    move(node, State.deprovisioned, Agent.system, Optional.empty());
                }
                removed.add(node);
                return removed;
            }
            else {
                List<Node> removed = List.of(node);
                db.removeNodes(removed);
                return removed;
            }
        }
    }

    /** Forgets a deprovisioned node. This removes all traces of the node in the node repository. */
    public void forget(Node node) {
        if (node.state() != State.deprovisioned)
            throw new IllegalArgumentException(node + " must be deprovisioned before it can be forgotten");
        db.removeNodes(List.of(node));
    }

    private List<Node> removeChildren(Node node, boolean force) {
        List<Node> children = list().childrenOf(node).asList();
        children.forEach(child -> requireRemovable(child, true, force));
        db.removeNodes(children);
        return new ArrayList<>(children);
    }

    /**
     * Throws if the given node cannot be removed. Removal is allowed if:
     *  - Tenant node: node is unallocated
     *  - Host node: iff in state provisioned|failed|parked
     *  - Child node:
     *      If only removing the container node: node in state ready
     *      If also removing the parent node: child is in state provisioned|failed|parked|dirty|ready
     */
    private void requireRemovable(Node node, boolean removingAsChild, boolean force) {
        if (force) return;

        if (node.type() == NodeType.tenant && node.allocation().isPresent())
            illegal(node + " is currently allocated and cannot be removed");

        if (!node.type().isHost() && !removingAsChild) {
            if (node.state() != State.ready)
                illegal(node + " can not be removed as it is not in the state " + State.ready);
        }
        else if (!node.type().isHost()) { // removing a child node
            Set<State> legalStates = EnumSet.of(State.provisioned, State.failed, State.parked, State.dirty, State.ready);
            if ( ! legalStates.contains(node.state()))
                illegal(node + " can not be removed as it is not in the states " + legalStates);
        }
        else { // a host
            Set<State> legalStates = EnumSet.of(State.provisioned, State.failed, State.parked);
            if (! legalStates.contains(node.state()))
                illegal(node + " can not be removed as it is not in the states " + legalStates);
        }
    }

    /**
     * Throws if given node cannot be breakfixed.
     * Breakfix is allowed if the following is true:
     *  - Node is tenant host
     *  - Node is in zone without dynamic provisioning
     *  - Node is in parked or failed state
     */
    private void requireBreakfixable(Node node) {
        if (zone().getCloud().dynamicProvisioning()) {
            illegal("Can not breakfix in zone: " + zone());
        }

        if (node.type() != NodeType.host) {
            illegal(node + " can not be breakfixed as it is not a tenant host");
        }

        Set<State> legalStates = EnumSet.of(State.failed, State.parked);
        if (! legalStates.contains(node.state())) {
            illegal(node + " can not be removed as it is not in the states " + legalStates);
        }
    }

    /**
     * Increases the restart generation of the active nodes matching the filter.
     *
     * @return the nodes in their new state
     */
    public List<Node> restart(NodeFilter filter) {
        return performOn(StateFilter.from(State.active, filter),
                         (node, lock) -> write(node.withRestart(node.allocation().get().restartGeneration().withIncreasedWanted()),
                                               lock));
    }

    /**
     * Increases the reboot generation of the nodes matching the filter.
     *
     * @return the nodes in their new state
     */
    public List<Node> reboot(NodeFilter filter) {
        return performOn(filter, (node, lock) -> write(node.withReboot(node.status().reboot().withIncreasedWanted()), lock));
    }

    /**
     * Set target OS version of all nodes matching given filter.
     *
     * @return the nodes in their new state
     */
    public List<Node> upgradeOs(NodeFilter filter, Optional<Version> version) {
        return performOn(filter, (node, lock) -> {
            var newStatus = node.status().withOsVersion(node.status().osVersion().withWanted(version));
            return write(node.with(newStatus), lock);
        });
    }

    /** Retire nodes matching given filter */
    public List<Node> retire(NodeFilter filter, Agent agent, Instant instant) {
        return performOn(filter, (node, lock) -> write(node.withWantToRetire(true, agent, instant), lock));
    }

    /**
     * Writes this node after it has changed some internal state but NOT changed its state field.
     * This does NOT lock the node repository implicitly, but callers are expected to already hold the lock.
     *
     * @param lock already acquired lock
     * @return the written node for convenience
     */
    public Node write(Node node, Mutex lock) { return write(List.of(node), lock).get(0); }

    /**
     * Writes these nodes after they have changed some internal state but NOT changed their state field.
     * This does NOT lock the node repository implicitly, but callers are expected to already hold the lock.
     *
     * @param lock already acquired lock
     * @return the written nodes for convenience
     */
    public List<Node> write(List<Node> nodes, @SuppressWarnings("unused") Mutex lock) {
        return db.writeTo(nodes, Agent.system, Optional.empty());
    }

    /**
     * Performs an operation requiring locking on all nodes matching some filter.
     *
     * @param filter the filter determining the set of nodes where the operation will be performed
     * @param action the action to perform
     * @return the set of nodes on which the action was performed, as they became as a result of the operation
     */
    private List<Node> performOn(NodeFilter filter, BiFunction<Node, Mutex, Node> action) {
        List<Node> unallocatedNodes = new ArrayList<>();
        ListMap<ApplicationId, Node> allocatedNodes = new ListMap<>();

        // Group matching nodes by the lock needed
        for (Node node : db.readNodes()) {
            if ( ! filter.matches(node)) continue;
            if (node.allocation().isPresent())
                allocatedNodes.put(node.allocation().get().owner(), node);
            else
                unallocatedNodes.add(node);
        }

        // perform operation while holding locks
        List<Node> resultingNodes = new ArrayList<>();
        try (Mutex lock = lockUnallocated()) {
            for (Node node : unallocatedNodes) {
                Optional<Node> currentNode = db.readNode(node.hostname()); // Re-read while holding lock
                if (currentNode.isEmpty()) continue;
                resultingNodes.add(action.apply(currentNode.get(), lock));
            }
        }
        for (Map.Entry<ApplicationId, List<Node>> applicationNodes : allocatedNodes.entrySet()) {
            try (Mutex lock = lock(applicationNodes.getKey())) {
                for (Node node : applicationNodes.getValue()) {
                    Optional<Node> currentNode = db.readNode(node.hostname());  // Re-read while holding lock
                    if (currentNode.isEmpty()) continue;
                    resultingNodes.add(action.apply(currentNode.get(), lock));
                }
            }
        }
        return resultingNodes;
    }

    public boolean canAllocateTenantNodeTo(Node host) {
        return canAllocateTenantNodeTo(host, zone.getCloud().dynamicProvisioning());
    }

    public static boolean canAllocateTenantNodeTo(Node host, boolean dynamicProvisioning) {
        if ( ! host.type().canRun(NodeType.tenant)) return false;
        if (host.status().wantToRetire()) return false;
        if (host.allocation().map(alloc -> alloc.membership().retired()).orElse(false)) return false;

        if (dynamicProvisioning)
            return EnumSet.of(State.active, State.ready, State.provisioned).contains(host.state());
        else
            return host.state() == State.active;
    }

    /** Returns the time keeper of this system */
    public Clock clock() { return clock; }

    /** Returns the zone of this system */
    public Zone zone() { return zone; }

    /** Create a lock which provides exclusive rights to making changes to the given application */
    public Mutex lock(ApplicationId application) {
        return db.lock(application);
    }

    /** Create a lock with a timeout which provides exclusive rights to making changes to the given application */
    public Mutex lock(ApplicationId application, Duration timeout) {
        return db.lock(application, timeout);
    }

    /** Create a lock which provides exclusive rights to modifying unallocated nodes */
    public Mutex lockUnallocated() { return db.lockInactive(); }

    /** Returns the unallocated/application lock, and the node acquired under that lock. */
    public Optional<NodeMutex> lockAndGet(Node node) {
        Node staleNode = node;

        final int maxRetries = 4;
        for (int i = 0; i < maxRetries; ++i) {
            Mutex lockToClose = lock(staleNode);
            try {
                // As an optimization we first try finding the node in the same state
                Optional<Node> freshNode = getNode(staleNode.hostname(), staleNode.state());
                if (freshNode.isEmpty()) {
                    freshNode = getNode(staleNode.hostname());
                    if (freshNode.isEmpty()) {
                        return Optional.empty();
                    }
                }

                if (Objects.equals(freshNode.get().allocation().map(Allocation::owner),
                                   staleNode.allocation().map(Allocation::owner))) {
                    NodeMutex nodeMutex = new NodeMutex(freshNode.get(), lockToClose);
                    lockToClose = null;
                    return Optional.of(nodeMutex);
                }

                // The wrong lock was held when the fresh node was fetched, so try again
                staleNode = freshNode.get();
            } finally {
                if (lockToClose != null) lockToClose.close();
            }
        }

        throw new IllegalStateException("Giving up (after " + maxRetries + " attempts) " +
                "fetching an up to date node under lock: " + node.hostname());
    }

    /** Returns the unallocated/application lock, and the node acquired under that lock. */
    public Optional<NodeMutex> lockAndGet(String hostname) {
        return getNode(hostname).flatMap(this::lockAndGet);
    }

    /** Returns the unallocated/application lock, and the node acquired under that lock. */
    public NodeMutex lockAndGetRequired(Node node) {
        return lockAndGet(node).orElseThrow(() -> new IllegalArgumentException("No such node: " + node.hostname()));
    }

    /** Returns the unallocated/application lock, and the node acquired under that lock. */
    public NodeMutex lockAndGetRequired(String hostname) {
        return lockAndGet(hostname).orElseThrow(() -> new IllegalArgumentException("No such node: " + hostname));
    }

    private Mutex lock(Node node) {
        return node.allocation().isPresent() ? lock(node.allocation().get().owner()) : lockUnallocated();
    }

    private void illegal(String message) {
        throw new IllegalArgumentException(message);
    }
}
