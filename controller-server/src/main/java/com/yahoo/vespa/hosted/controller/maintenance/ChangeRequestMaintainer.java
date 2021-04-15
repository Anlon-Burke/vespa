// Copyright Verizon Media. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.controller.maintenance;

import com.yahoo.config.provision.Environment;
import com.yahoo.config.provision.SystemName;
import com.yahoo.config.provision.zone.ZoneId;
import com.yahoo.vespa.hosted.controller.Controller;
import com.yahoo.vespa.hosted.controller.api.integration.configserver.NodeRepository;
import com.yahoo.vespa.hosted.controller.api.integration.vcmr.ChangeRequest;
import com.yahoo.vespa.hosted.controller.api.integration.vcmr.ChangeRequestClient;
import com.yahoo.vespa.hosted.controller.api.integration.vcmr.VespaChangeRequest;
import com.yahoo.vespa.hosted.controller.persistence.CuratorDb;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * @author olaa
 */
public class ChangeRequestMaintainer extends ControllerMaintainer {

    private final Logger logger = Logger.getLogger(ChangeRequestMaintainer.class.getName());
    private final ChangeRequestClient changeRequestClient;
    private final SystemName system;
    private final CuratorDb curator;
    private final NodeRepository nodeRepository;

    public ChangeRequestMaintainer(Controller controller, Duration interval) {
        super(controller, interval, null, SystemName.allOf(Predicate.not(SystemName::isPublic)));
        this.changeRequestClient = controller.serviceRegistry().changeRequestClient();
        this.system = controller.system();
        this.curator = controller.curator();
        this.nodeRepository = controller.serviceRegistry().configServer().nodeRepository();
    }


    @Override
    protected boolean maintain() {
        var changeRequests = changeRequestClient.getUpcomingChangeRequests();

        if (!changeRequests.isEmpty()) {
            logger.info(() -> "Found the following upcoming change requests:");
            changeRequests.forEach(changeRequest -> logger.info(changeRequest::toString));
        }

        if (system.equals(SystemName.main)) {
            approveChanges(changeRequests);
            storeChangeRequests(changeRequests);
        }

        return true;
    }

    private void approveChanges(List<ChangeRequest> changeRequests) {
        var unapprovedRequests = changeRequests
                .stream()
                .filter(changeRequest -> changeRequest.getApproval() == ChangeRequest.Approval.REQUESTED)
                .collect(Collectors.toList());

        changeRequestClient.approveChangeRequests(unapprovedRequests);
    }

    private void storeChangeRequests(List<ChangeRequest> changeRequests) {
        var existingChangeRequests = curator.readChangeRequests()
                .stream()
                .collect(Collectors.toMap(ChangeRequest::getId, Function.identity()));

        var hostsByZone = hostsByZone();
        // Create or update requests in curator
        try (var lock = curator.lockChangeRequests()) {
            changeRequests.forEach(changeRequest -> {
                var optionalZone = inferZone(changeRequest, hostsByZone);
                optionalZone.ifPresent(zone -> {
                    var vcmr = existingChangeRequests
                            .getOrDefault(changeRequest.getId(), new VespaChangeRequest(changeRequest, zone))
                            .withSource(changeRequest.getChangeRequestSource());
                    curator.writeChangeRequest(vcmr);
                });
            });
        }
    }

    private Map<ZoneId, List<String>> hostsByZone() {
        return controller().zoneRegistry()
                .zones()
                .reachable()
                .in(Environment.prod)
                .ids()
                .stream()
                .collect(Collectors.toMap(
                        zone -> zone,
                        zone -> nodeRepository.list(zone, false)
                                .stream()
                                .map(node -> node.hostname().value())
                                .collect(Collectors.toList())
                ));
    }

    private Optional<ZoneId> inferZone(ChangeRequest changeRequest, Map<ZoneId, List<String>> hostsByZone) {
        return hostsByZone.entrySet().stream()
                .filter(entry -> !Collections.disjoint(entry.getValue(), changeRequest.getImpactedHosts()))
                .map(Map.Entry::getKey)
                .findFirst();
    }
}
