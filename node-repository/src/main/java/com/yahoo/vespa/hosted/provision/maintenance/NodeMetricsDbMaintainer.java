// Copyright Verizon Media. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.provision.maintenance;

import com.yahoo.collections.Pair;
import com.yahoo.config.provision.ApplicationId;
import com.yahoo.jdisc.Metric;
import com.yahoo.vespa.hosted.provision.NodeRepository;
import com.yahoo.vespa.hosted.provision.autoscale.MetricSnapshot;
import com.yahoo.vespa.hosted.provision.autoscale.MetricsFetcher;
import com.yahoo.vespa.hosted.provision.autoscale.MetricsDb;
import com.yahoo.yolean.Exceptions;

import java.time.Duration;
import java.util.Collection;
import java.util.logging.Level;
import java.util.stream.Collectors;

/**
 * Maintainer which keeps the node metric db up to date by periodically fetching metrics from all
 * active nodes.
 *
 * @author bratseth
 */
public class NodeMetricsDbMaintainer extends NodeRepositoryMaintainer {

    private static final int maxWarningsPerInvocation = 2;

    private final MetricsFetcher metricsFetcher;
    private final MetricsDb metricsDb;

    public NodeMetricsDbMaintainer(NodeRepository nodeRepository,
                                   MetricsFetcher metricsFetcher,
                                   MetricsDb metricsDb,
                                   Duration interval,
                                   Metric metric) {
        super(nodeRepository, interval, metric);
        this.metricsFetcher = metricsFetcher;
        this.metricsDb = metricsDb;
    }

    @Override
    protected boolean maintain() {
        int warnings = 0;
        for (ApplicationId application : activeNodesByApplication().keySet()) {
            try {
                metricsDb.add(filter(metricsFetcher.fetchMetrics(application)));
            }
            catch (Exception e) {
                // TODO: Don't warn if this only happens occasionally
                if (warnings++ < maxWarningsPerInvocation)
                    log.log(Level.WARNING, "Could not update metrics for " + application + ": " + Exceptions.toMessageString(e));
            }
        }
        metricsDb.gc();

        // Suppress failures for manual zones for now to avoid noise
        if (nodeRepository().zone().environment().isManuallyDeployed()) return true;

        return warnings == 0;
    }

    /** Filter out uninformative snapshots before storing */
    private Collection<Pair<String, MetricSnapshot>> filter(Collection<Pair<String, MetricSnapshot>> snapshots) {
        return snapshots.stream()
                        .filter(snapshot -> snapshot.getSecond().inService())
                        .collect(Collectors.toList());
    }

}
