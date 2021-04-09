// Copyright Verizon Media. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.provision.os;

import com.yahoo.component.Version;
import com.yahoo.config.provision.ApplicationId;
import com.yahoo.config.provision.NodeResources;
import com.yahoo.config.provision.NodeType;
import com.yahoo.test.ManualClock;
import com.yahoo.vespa.hosted.provision.Node;
import com.yahoo.vespa.hosted.provision.NodeList;
import com.yahoo.vespa.hosted.provision.node.Agent;
import com.yahoo.vespa.hosted.provision.node.OsVersion;
import com.yahoo.vespa.hosted.provision.node.Status;
import com.yahoo.vespa.hosted.provision.provisioning.ProvisioningTester;
import org.junit.Test;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * @author mpolden
 */
public class OsVersionsTest {

    private final ProvisioningTester tester = new ProvisioningTester.Builder().build();
    private final ApplicationId infraApplication = ApplicationId.from("hosted-vespa", "infra", "default");

    @Test
    public void upgrade() {
        var versions = new OsVersions(tester.nodeRepository());
        provisionInfraApplication(10);
        Supplier<NodeList> hostNodes = () -> tester.nodeRepository().nodes().list().nodeType(NodeType.host);

        // Upgrade OS
        assertTrue("No versions set", versions.readChange().targets().isEmpty());
        var version1 = Version.fromString("7.1");
        versions.setTarget(NodeType.host, version1, Duration.ZERO, false);
        assertEquals(version1, versions.targetFor(NodeType.host).get());
        assertTrue("Per-node wanted OS version remains unset", hostNodes.get().stream().allMatch(node -> node.status().osVersion().wanted().isEmpty()));

        // One host upgrades to a later version outside the control of orchestration
        Node hostOnLaterVersion = hostNodes.get().first().get();
        setCurrentVersion(List.of(hostOnLaterVersion), Version.fromString("8.1"));

        // Upgrade OS again
        var version2 = Version.fromString("7.2");
        versions.setTarget(NodeType.host, version2, Duration.ZERO, false);
        assertEquals(version2, versions.targetFor(NodeType.host).get());

        // Resume upgrade
        versions.resumeUpgradeOf(NodeType.host, true);
        NodeList allHosts = hostNodes.get();
        assertTrue("Wanted version is set", allHosts.except(hostOnLaterVersion).stream()
                                                    .allMatch(node -> node.status().osVersion().wanted().isPresent()));
        assertTrue("Wanted version is not set for host on later version",
                   allHosts.first().get().status().osVersion().wanted().isEmpty());

        // Halt upgrade
        versions.resumeUpgradeOf(NodeType.host, false);
        assertTrue("Wanted version is unset", hostNodes.get().stream()
                                                       .allMatch(node -> node.status().osVersion().wanted().isEmpty()));

        // Downgrading fails
        try {
            versions.setTarget(NodeType.host, version1, Duration.ZERO, false);
            fail("Expected exception");
        } catch (IllegalArgumentException ignored) {}

        // Forcing downgrade succeeds
        versions.setTarget(NodeType.host, version1, Duration.ZERO, true);
        assertEquals(version1, versions.targetFor(NodeType.host).get());

        // Target can be removed
        versions.removeTarget(NodeType.host);
        assertFalse(versions.targetFor(NodeType.host).isPresent());
        assertTrue(hostNodes.get().stream().allMatch(node -> node.status().osVersion().wanted().isEmpty()));
    }

    @Test
    public void max_active_upgrades() {
        int totalNodes = 20;
        int maxActiveUpgrades = 5;
        var versions = new OsVersions(tester.nodeRepository(), false, maxActiveUpgrades);
        provisionInfraApplication(totalNodes);
        Supplier<NodeList> hostNodes = () -> tester.nodeRepository().nodes().list().state(Node.State.active).hosts();

        // 5 nodes have no version. The other 15 are spread across different versions
        var hostNodesList = hostNodes.get().asList();
        for (int i = totalNodes - maxActiveUpgrades - 1; i >= 0; i--) {
            setCurrentVersion(List.of(hostNodesList.get(i)), new Version(7, 0, i));
        }

        // Deprovisioned hosts are not considered
        for (var host : tester.makeReadyNodes(10, "default", NodeType.host)) {
            tester.nodeRepository().nodes().fail(host.hostname(), Agent.system, OsVersions.class.getSimpleName());
            tester.nodeRepository().nodes().removeRecursively(host.hostname());
        }
        assertEquals(10, tester.nodeRepository().nodes().list(Node.State.deprovisioned).size());

        // Set target
        var version1 = Version.fromString("7.1");
        versions.setTarget(NodeType.host, version1, Duration.ZERO, false);
        assertEquals(version1, versions.targetFor(NodeType.host).get());

        // Activate target
        for (int i = 0; i < totalNodes; i += maxActiveUpgrades) {
            versions.resumeUpgradeOf(NodeType.host, true);
            var nodes = hostNodes.get();
            var nodesUpgrading = nodes.changingOsVersion();
            assertEquals("Target is changed for a subset of nodes", maxActiveUpgrades, nodesUpgrading.size());
            assertEquals("Wanted version is set for nodes upgrading", version1,
                         minVersion(nodesUpgrading, OsVersion::wanted));
            var nodesOnLowestVersion = nodes.asList().stream()
                                            .sorted(Comparator.comparing(node -> node.status().osVersion().current().orElse(Version.emptyVersion)))
                                            .collect(Collectors.toList())
                                            .subList(0, maxActiveUpgrades);
            assertEquals("Nodes on lowest version are told to upgrade",
                         nodesUpgrading.asList(), nodesOnLowestVersion);
            completeReprovisionOf(nodesUpgrading.asList());
        }

        // Activating again after all nodes have upgraded does nothing
        versions.resumeUpgradeOf(NodeType.host, true);
        assertEquals("All nodes upgraded", version1, minVersion(hostNodes.get(), OsVersion::current));
    }

    @Test
    public void newer_upgrade_aborts_upgrade_to_stale_version() {
        var versions = new OsVersions(tester.nodeRepository());
        provisionInfraApplication(10);
        Supplier<NodeList> hostNodes = () -> tester.nodeRepository().nodes().list().hosts();

        // Some nodes are targeting an older version
        var version1 = Version.fromString("7.1");
        setWantedVersion(hostNodes.get().asList().subList(0, 5), version1);

        // Trigger upgrade to next version
        var version2 = Version.fromString("7.2");
        versions.setTarget(NodeType.host, version2, Duration.ZERO, false);
        versions.resumeUpgradeOf(NodeType.host, true);

        // Wanted version is changed to newest target for all nodes
        assertEquals(version2, minVersion(hostNodes.get(), OsVersion::wanted));
    }

    @Test
    public void upgrade_by_retiring() {
        var versions = new OsVersions(tester.nodeRepository(), true, Integer.MAX_VALUE);
        var clock = (ManualClock) tester.nodeRepository().clock();
        int hostCount = 10;
        // Provision hosts and children
        List<Node> hosts = provisionInfraApplication(hostCount);
        NodeResources resources = new NodeResources(2, 4, 8, 1);
        for (var host : hosts) {
            tester.makeReadyChildren(2, resources, host.hostname());
        }
        Supplier<NodeList> hostNodes = () -> tester.nodeRepository().nodes().list()
                                                   .hosts()
                                                   .not().state(Node.State.deprovisioned);

        // Target is set and upgrade started
        var version1 = Version.fromString("7.1");
        Duration totalBudget = Duration.ofHours(12);
        Duration nodeBudget = totalBudget.dividedBy(hostCount);
        versions.setTarget(NodeType.host, version1, totalBudget,false);
        versions.resumeUpgradeOf(NodeType.host, true);

        // One host is deprovisioning
        assertEquals(1, hostNodes.get().deprovisioning().size());

        // Nothing happens on next resume as first host has not spent its budget
        versions.resumeUpgradeOf(NodeType.host, true);
        NodeList nodesDeprovisioning = hostNodes.get().deprovisioning();
        assertEquals(1, nodesDeprovisioning.size());
        assertEquals(2, deprovisioningChildrenOf(nodesDeprovisioning.asList().get(0)).size());

        // Budget has been spent and another host is retired
        clock.advance(nodeBudget);
        versions.resumeUpgradeOf(NodeType.host, true);
        assertEquals(2, hostNodes.get().deprovisioning().size());

        // Two nodes complete their upgrade by being reprovisioned
        completeReprovisionOf(hostNodes.get().deprovisioning().asList());
        assertEquals(2, hostNodes.get().onOsVersion(version1).size());
        // The remaining hosts complete their upgrade
        for (int i = 0; i < hostCount - 2; i++) {
            clock.advance(nodeBudget);
            versions.resumeUpgradeOf(NodeType.host, true);
            nodesDeprovisioning = hostNodes.get().deprovisioning();
            assertEquals(1, nodesDeprovisioning.size());
            assertEquals(2, deprovisioningChildrenOf(nodesDeprovisioning.asList().get(0)).size());
            completeReprovisionOf(nodesDeprovisioning.asList());
        }

        // All hosts upgraded and none are deprovisioning
        assertEquals(hostCount, hostNodes.get().onOsVersion(version1).not().deprovisioning().size());
        assertEquals(hostCount, tester.nodeRepository().nodes().list(Node.State.deprovisioned).size());
        var lastRetiredAt = clock.instant().truncatedTo(ChronoUnit.MILLIS);

        // Resuming after everything has upgraded does nothing
        versions.resumeUpgradeOf(NodeType.host, true);
        assertEquals(0, hostNodes.get().deprovisioning().size());

        // Another upgrade is triggered. Last retirement time is preserved
        clock.advance(Duration.ofDays(1));
        var version2 = Version.fromString("7.2");
        versions.setTarget(NodeType.host, version2, totalBudget, false);
        assertEquals(lastRetiredAt, versions.readChange().targets().get(NodeType.host).lastRetiredAt().get());
    }

    @Test
    public void upgrade_by_retiring_everything_at_once() {
        var versions = new OsVersions(tester.nodeRepository(), true, Integer.MAX_VALUE);
        int hostCount = 3;
        provisionInfraApplication(hostCount, NodeType.confighost);
        Supplier<NodeList> hostNodes = () -> tester.nodeRepository().nodes().list()
                                                   .nodeType(NodeType.confighost)
                                                   .not().state(Node.State.deprovisioned);

        // Target is set with zero budget and upgrade started
        var version1 = Version.fromString("7.1");
        versions.setTarget(NodeType.confighost, version1, Duration.ZERO,false);
        for (int i = 0; i < hostCount; i++) {
            versions.resumeUpgradeOf(NodeType.confighost, true);
        }

        // All hosts are deprovisioning
        assertEquals(hostCount, hostNodes.get().deprovisioning().size());
        // Nodes complete their upgrade by being reprovisioned
        completeReprovisionOf(hostNodes.get().deprovisioning().asList(), NodeType.confighost);
        assertEquals(hostCount, hostNodes.get().onOsVersion(version1).size());
    }

    @Test
    public void upgrade_by_rebuilding() {
        var versions = new OsVersions(tester.nodeRepository(), false, Integer.MAX_VALUE);
        var clock = tester.clock();
        int hostCount = 10;
        provisionInfraApplication(hostCount + 1);
        Supplier<NodeList> hostNodes = () -> tester.nodeRepository().nodes().list().nodeType(NodeType.host);

        // All hosts upgrade to first version. Upgrades are delegated
        var version0 = Version.fromString("7.0");
        versions.setTarget(NodeType.host, version0, Duration.ZERO, false);
        setCurrentVersion(hostNodes.get().asList(), version0);

        // One host is failed out
        Node failedHost = tester.nodeRepository().nodes().fail(hostNodes.get().first().get().hostname(),
                                                               Agent.system, getClass().getSimpleName());

        // Target is set for new major version. Upgrade mechanism switches to rebuilding
        var version1 = Version.fromString("8.0");
        Duration totalBudget = Duration.ofHours(12);
        Duration nodeBudget = totalBudget.dividedBy(hostCount);
        versions.setTarget(NodeType.host, version1, totalBudget, false);
        versions.resumeUpgradeOf(NodeType.host, true);

        // One host starts rebuilding
        assertEquals(1, hostNodes.get().rebuilding().size());

        // Nothing happens on next resume as first host has not spent its budget
        versions.resumeUpgradeOf(NodeType.host, true);
        assertEquals(1, hostNodes.get().rebuilding().size());

        // Budget has been spent and another host is rebuilt
        clock.advance(nodeBudget);
        versions.resumeUpgradeOf(NodeType.host, true);
        NodeList hostsRebuilding = hostNodes.get().rebuilding();
        assertEquals(2, hostsRebuilding.size());

        // Hosts are rebuilt
        completeRebuildOf(hostsRebuilding.asList(), NodeType.host);
        assertEquals(2, hostNodes.get().onOsVersion(version1).size());

        // The remaining hosts complete their upgrade
        for (int i = 0; i < hostCount - 2; i++) {
            clock.advance(nodeBudget);
            versions.resumeUpgradeOf(NodeType.host, true);
            hostsRebuilding = hostNodes.get().rebuilding();
            assertEquals(1, hostsRebuilding.size());
            completeRebuildOf(hostsRebuilding.asList(), NodeType.host);
        }

        // All hosts upgraded and none are rebuilding
        assertEquals(hostCount, hostNodes.get().onOsVersion(version1).not().rebuilding().size());
        assertEquals(hostCount, tester.nodeRepository().nodes().list(Node.State.active).size());

        // Resuming after everything has upgraded has no effect
        versions.resumeUpgradeOf(NodeType.host, true);
        assertEquals(0, hostNodes.get().rebuilding().size());

        // Next version is within same major. Upgrade mechanism switches to delegated
        var version2 = Version.fromString("8.1");
        versions.setTarget(NodeType.host, version2, totalBudget, false);
        versions.resumeUpgradeOf(NodeType.host, true);
        NodeList nonFailingHosts = hostNodes.get().except(failedHost);
        assertTrue("Wanted version is set", nonFailingHosts.except(failedHost).stream()
                                                    .allMatch(node -> node.status().osVersion().wanted().isPresent()));
        setCurrentVersion(nonFailingHosts.asList(), version2);
        assertEquals(10, hostNodes.get().except(failedHost).onOsVersion(version2).size());

        // Failed host is reactivated
        Node reactivatedHost = tester.nodeRepository().nodes().reactivate(failedHost.hostname(), Agent.system, getClass().getSimpleName());
        assertEquals(version0, reactivatedHost.status().osVersion().current().get());

        // Resuming upgrades reactivated host. Upgrade mechanism switches to rebuilding
        clock.advance(nodeBudget);
        versions.resumeUpgradeOf(NodeType.host, true);
        hostsRebuilding = hostNodes.get().rebuilding();
        assertEquals(List.of(reactivatedHost), hostsRebuilding.asList());
        completeRebuildOf(hostsRebuilding.asList(), NodeType.host);
    }

    private NodeList deprovisioningChildrenOf(Node parent) {
        return tester.nodeRepository().nodes().list()
                     .childrenOf(parent)
                     .deprovisioning();
    }

    private List<Node> provisionInfraApplication(int nodeCount) {
        return provisionInfraApplication(nodeCount, NodeType.host);
    }

    private List<Node> provisionInfraApplication(int nodeCount, NodeType nodeType) {
        var nodes = tester.makeReadyNodes(nodeCount, "default", nodeType, 1);
        tester.prepareAndActivateInfraApplication(infraApplication, nodeType);
        return nodes.stream()
                    .map(Node::hostname)
                    .flatMap(hostname -> tester.nodeRepository().nodes().node(hostname).stream())
                    .collect(Collectors.toList());
    }

    private Version minVersion(NodeList nodes, Function<OsVersion, Optional<Version>> versionField) {
        return nodes.asList().stream()
                    .map(Node::status)
                    .map(Status::osVersion)
                    .map(versionField)
                    .flatMap(Optional::stream)
                    .min(Comparator.naturalOrder())
                    .orElse(Version.emptyVersion);

    }

    private void setWantedVersion(List<Node> nodes, Version wantedVersion) {
        tester.patchNodes(nodes, node -> node.with(node.status().withOsVersion(node.status().osVersion().withWanted(Optional.of(wantedVersion)))));
    }

    private void setCurrentVersion(List<Node> nodes, Version currentVersion) {
        tester.patchNodes(nodes, node -> node.with(node.status().withOsVersion(node.status().osVersion().withCurrent(Optional.of(currentVersion)))));
    }

    private void completeReprovisionOf(List<Node> nodes) {
        completeReprovisionOf(nodes, NodeType.host);
    }

    private void completeReprovisionOf(List<Node> nodes, NodeType nodeType) {
        // Complete upgrade by deprovisioning stale hosts and provisioning new ones
        tester.patchNodes(nodes, (node) -> {
            Optional<Version> wantedOsVersion = node.status().osVersion().wanted();
            if (node.status().wantToDeprovision()) {
                tester.nodeRepository().nodes().park(node.hostname(), false, Agent.system,
                                                     getClass().getSimpleName());
                tester.nodeRepository().nodes().removeRecursively(node.hostname());
                node = provisionInfraApplication(1, nodeType).get(0);
            }
            return node.with(node.status().withOsVersion(node.status().osVersion().withCurrent(wantedOsVersion)));
        });
    }

    private void completeRebuildOf(List<Node> nodes, NodeType nodeType) {
        // Complete upgrade by rebuilding stale hosts
        tester.patchNodes(nodes, (node) -> {
            Optional<Version> wantedOsVersion = node.status().osVersion().wanted();
            if (node.status().wantToRebuild()) {
                tester.nodeRepository().nodes().park(node.hostname(), false, Agent.system,
                                                     getClass().getSimpleName());
                tester.nodeRepository().nodes().removeRecursively(node.hostname());
                node = tester.nodeRepository().nodes().restore(node.hostname(), Agent.system, getClass().getSimpleName());
                node = tester.nodeRepository().nodes().setReady(node.hostname(), Agent.system, getClass().getSimpleName());
                tester.prepareAndActivateInfraApplication(infraApplication, nodeType);
                node = tester.nodeRepository().nodes().node(node.hostname()).get();
            }
            return node.with(node.status().withOsVersion(node.status().osVersion().withCurrent(wantedOsVersion)));
        });
    }

}
