// Copyright 2020 Oath Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.controller.deployment;

import com.yahoo.config.provision.SystemName;
import com.yahoo.jdisc.Metric;
import com.yahoo.vespa.hosted.controller.api.integration.deployment.JobId;

import java.util.Map;
import java.util.function.Supplier;

/**
 * Records metrics related to deployment jobs.
 *
 * @author jonmv
 */
public class JobMetrics {

    public static final String start = "deployment.start";
    public static final String outOfCapacity = "deployment.outOfCapacity";
    public static final String endpointCertificateTimeout = "deployment.endpointCertificateTimeout";
    public static final String deploymentFailure = "deployment.deploymentFailure";
    public static final String convergenceFailure = "deployment.convergenceFailure";
    public static final String testFailure = "deployment.testFailure";
    public static final String error = "deployment.error";
    public static final String abort = "deployment.abort";
    public static final String success = "deployment.success";

    private final Metric metric;
    private final Supplier<SystemName> system;

    public JobMetrics(Metric metric, Supplier<SystemName> system) {
        this.metric = metric;
        this.system = system;
    }

    public void jobStarted(JobId id) {
        metric.add(start, 1, metric.createContext(contextOf(id)));
    }

    public void jobFinished(JobId id, RunStatus status) {
        metric.add(valueOf(status), 1, metric.createContext(contextOf(id)));
    }

    Map<String, String> contextOf(JobId id) {
        return Map.of("applicationId", id.application().toFullString(),
                      "tenantName", id.application().tenant().value(),
                      "app", id.application().application().value() + "." + id.application().instance().value(),
                      "test", Boolean.toString(id.type().isTest()),
                      "zone", id.type().zone(system.get()).value());
    }

    static String valueOf(RunStatus status) {
        switch (status) {
            case outOfCapacity: return outOfCapacity;
            case endpointCertificateTimeout: return endpointCertificateTimeout;
            case deploymentFailed: return deploymentFailure;
            case installationFailed: return convergenceFailure;
            case testFailure: return testFailure;
            case error: return error;
            case aborted: return abort;
            case success: return success;
            default: throw new IllegalArgumentException("Unexpected run status '" + status + "'");
        }
    }

}
