// Copyright 2020 Oath Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package ai.vespa.metricsproxy.http;

import ai.vespa.metricsproxy.core.MetricsConsumers;
import ai.vespa.metricsproxy.core.MetricsManager;
import ai.vespa.metricsproxy.metric.model.ConsumerId;
import ai.vespa.metricsproxy.metric.model.MetricsPacket;
import ai.vespa.metricsproxy.metric.model.json.JsonRenderingException;
import ai.vespa.metricsproxy.service.VespaServices;

import java.time.Instant;
import java.util.List;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import static ai.vespa.metricsproxy.metric.model.ConsumerId.toConsumerId;

/**
 * Generates metrics values in json format for the metrics/v1 rest api.
 *
 * @author gjoranv
 */
public class ValuesFetcher {

    private static final Logger log = Logger.getLogger(ValuesFetcher.class.getName());

    public static final ConsumerId defaultMetricsConsumerId = toConsumerId("default");

    private final MetricsManager metricsManager;
    private final VespaServices vespaServices;
    private final MetricsConsumers metricsConsumers;

    public ValuesFetcher(MetricsManager metricsManager,
                  VespaServices vespaServices,
                  MetricsConsumers metricsConsumers) {
        this.metricsManager = metricsManager;
        this.vespaServices = vespaServices;
        this.metricsConsumers = metricsConsumers;
    }

    public List<MetricsPacket> fetch(String requestedConsumer) throws JsonRenderingException {
        ConsumerId consumer = getConsumerOrDefault(requestedConsumer, metricsConsumers);

        return fetchAllMetrics()
                .stream()
                .filter(metricsPacket -> metricsPacket.consumers().contains(consumer))
                .collect(Collectors.toList());
    }

    public List<MetricsPacket.Builder> fetchMetricsAsBuilders(String requestedConsumer) throws JsonRenderingException {
        ConsumerId consumer = getConsumerOrDefault(requestedConsumer, metricsConsumers);

        return metricsManager.getMetricsAsBuilders(vespaServices.getVespaServices(), Instant.now())
                .stream()
                .filter(builder -> builder.hasConsumer(consumer))
                .collect(Collectors.toList());
    }


    public List<MetricsPacket> fetchAllMetrics() throws JsonRenderingException {
        return metricsManager.getMetrics(vespaServices.getVespaServices(), Instant.now());
    }

    public static ConsumerId getConsumerOrDefault(String requestedConsumer, MetricsConsumers consumers) {
        if (requestedConsumer == null) return defaultMetricsConsumerId;

        ConsumerId consumerId = toConsumerId(requestedConsumer);
        if (! consumers.getAllConsumers().contains(consumerId)) {
            log.info("No consumer with id '" + requestedConsumer + "' - using the default consumer instead.");
            return defaultMetricsConsumerId;
        }
        return consumerId;
    }

}
