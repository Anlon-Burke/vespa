// Copyright 2018 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.controller.api.integration.stubs;

import com.yahoo.config.provision.ApplicationId;
import com.yahoo.vespa.hosted.controller.api.integration.RunDataStore;
import com.yahoo.vespa.hosted.controller.api.integration.deployment.RunId;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author jonmv
 */
public class MockRunDataStore implements RunDataStore {

    private final Map<RunId, byte[]> logs = new ConcurrentHashMap<>();
    private final Map<RunId, byte[]> reports = new ConcurrentHashMap<>();

    @Override
    public Optional<byte[]> get(RunId id) {
        return Optional.ofNullable(logs.get(id));
    }

    @Override
    public void put(RunId id, byte[] log) {
        logs.put(id, log);
    }

    @Override
    public Optional<byte[]> getTestReport(RunId id) {
        return Optional.ofNullable(reports.get(id));
    }

    @Override
    public void putTestReport(RunId id, byte[] report) {
        reports.put(id, report);
    }

    @Override
    public void delete(RunId id) {
        logs.remove(id);
        reports.remove(id);
    }

    @Override
    public void delete(ApplicationId id) {
        logs.keySet().removeIf(runId -> runId.application().equals(id));
        reports.keySet().removeIf(runId -> runId.application().equals(id));
    }

}
