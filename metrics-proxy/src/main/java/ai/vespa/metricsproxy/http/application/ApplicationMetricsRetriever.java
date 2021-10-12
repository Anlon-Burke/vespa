// Copyright 2020 Oath Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package ai.vespa.metricsproxy.http.application;

import ai.vespa.metricsproxy.metric.model.ConsumerId;
import ai.vespa.metricsproxy.metric.model.MetricsPacket;
import ai.vespa.util.http.hc5.VespaAsyncHttpClientBuilder;
import com.google.inject.Inject;
import com.yahoo.component.AbstractComponent;
import org.apache.hc.client5.http.HttpHostConnectException;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.async.CloseableHttpAsyncClient;
import org.apache.hc.core5.reactor.IOReactorConfig;
import org.apache.hc.core5.util.Timeout;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import static ai.vespa.metricsproxy.http.ValuesFetcher.defaultMetricsConsumerId;

/**
 * This class retrieves metrics from all nodes in the given config, usually all
 * nodes in a Vespa application.
 *
 * @author gjoranv
 */
public class ApplicationMetricsRetriever extends AbstractComponent implements Runnable {

    private static final Logger log = Logger.getLogger(ApplicationMetricsRetriever.class.getName());

    static final Duration MIN_TIMEOUT = Duration.ofSeconds(60);
    static final Duration MAX_TIMEOUT = Duration.ofSeconds(240);

    private static final int HTTP_CONNECT_TIMEOUT = 5000;
    private static final int HTTP_SOCKET_TIMEOUT = 30000;
    private static final Duration METRICS_TTL = Duration.ofSeconds(30);

    private final CloseableHttpAsyncClient httpClient = createHttpClient();
    private final List<NodeMetricsClient> clients;
    private final Thread pollThread;
    private final Set<ConsumerId> consumerSet;
    private long pollCount = 0;
    private boolean stopped;

    // Non-final for testing
    private final AtomicReference<Duration> taskTimeout;

    @Inject
    public ApplicationMetricsRetriever(MetricsNodesConfig nodesConfig) {
        clients = createNodeClients(nodesConfig);
        taskTimeout = new AtomicReference<>(timeout(clients.size()));
        stopped = false;
        consumerSet = new HashSet<>();
        httpClient.start();
        pollThread = new Thread(this, "metrics-poller");
        pollThread.setDaemon(true);
    }

    @Override
    public void run() {
        try {
            while (true) {
                ConsumerId [] consumers;
                synchronized (pollThread) {
                    consumers = consumerSet.toArray(new ConsumerId[0]);
                }
                for (ConsumerId consumer : consumers) {
                    int numFailed = fetchMetricsAsync(consumer);
                    if (numFailed > 0 ) {
                        log.log(Level.INFO, "Updated metrics for consumer '" + consumer +"' failed for " + numFailed + " services");
                    } else {
                        log.log(Level.FINE, "Updated metrics for consumer '" + consumer +"'.");
                    }
                }
                Duration timeUntilNextPoll = Duration.ofMillis(1000);
                synchronized (pollThread) {
                    pollCount++;
                    pollThread.notifyAll();
                    pollThread.wait(timeUntilNextPoll.toMillis());
                    if (stopped) return;
                }
            }
        } catch (InterruptedException e) {}
    }

    @Override
    public void deconstruct() {
        synchronized (pollThread) {
            stopped = true;
            pollThread.notifyAll();
        }
        try {
            pollThread.join();
        } catch (InterruptedException e) {}
        try {
            httpClient.close();
        } catch (IOException e) {
            log.warning("Failed closing httpclient: " + e);
        }
        super.deconstruct();
    }

    Map<Node, List<MetricsPacket>> getMetrics() {
        return getMetrics(defaultMetricsConsumerId);
    }

    public Map<Node, List<MetricsPacket>> getMetrics(ConsumerId consumer) {
        log.log(Level.INFO, () -> "Retrieving metrics from " + clients.size() + " nodes.");
        synchronized (pollThread) {
            if (consumerSet.add(consumer)) {
                // Wakeup poll thread first time we see a new consumer
                pollThread.notifyAll();
            }
        }
        Map<Node, List<MetricsPacket>> metrics = new HashMap<>();
        for (NodeMetricsClient client : clients) {
            metrics.put(client.node, client.getMetrics(consumer));
        }
        return metrics;
    }

    void startPollAndWait() {
        try {
            synchronized (pollThread) {
                if ( ! pollThread.isAlive()) {
                    pollThread.start();
                }
                long before = pollCount;
                pollThread.notifyAll();
                while (pollCount <= before + 1) {
                    pollThread.notifyAll();
                    pollThread.wait();
                }
            }
        } catch (InterruptedException e) {}
    }

    private int fetchMetricsAsync(ConsumerId consumer) {
        Map<Node, Future<Boolean>> futures = new HashMap<>();
        for (NodeMetricsClient client : clients) {
            var optional = client.startSnapshotUpdate(consumer, METRICS_TTL);
            optional.ifPresent(future -> futures.put(client.node, future));
        }
        int numOk = 0;
        int numTried = futures.size();
        for (Map.Entry<Node, Future<Boolean>> entry : futures.entrySet()) {
            try {
                Boolean result = entry.getValue().get(taskTimeout.get().toMillis(), TimeUnit.MILLISECONDS);
                if ((result != null) && result) numOk++;
            } catch (InterruptedException | ExecutionException | TimeoutException e) {
                Throwable cause = e.getCause();
                if ( e instanceof ExecutionException && (cause != null) && (cause instanceof HttpHostConnectException)) {
                    // Remove once we have some track time.
                    log.log(Level.WARNING, "Failed retrieving metrics for '" + entry.getKey() +  "' : " + cause.getMessage());
                } else {
                    log.log(Level.WARNING, "Failed retrieving metrics for '" + entry.getKey() + "' : ", e);
                }
            }
        }
        log.log(Level.FINE, () -> "Finished retrieving metrics from " + clients.size() + " nodes.");
        return numTried - numOk;
    }

    private List<NodeMetricsClient> createNodeClients(MetricsNodesConfig nodesConfig) {
        return nodesConfig.node().stream()
                .map(Node::new)
                .map(node-> new NodeMetricsClient(httpClient, node, Clock.systemUTC()))
                .collect(Collectors.toList());
    }

    static CloseableHttpAsyncClient createHttpClient() {
        return VespaAsyncHttpClientBuilder.create()
                .setIOReactorConfig(IOReactorConfig.custom().setIoThreadCount(2).build())
                .setUserAgent("application-metrics-retriever")
                .setDefaultRequestConfig(RequestConfig.custom()
                                                 .setConnectTimeout(Timeout.ofMilliseconds(HTTP_CONNECT_TIMEOUT))
                                                 .setResponseTimeout(Timeout.ofMilliseconds(HTTP_SOCKET_TIMEOUT))
                                                 .build())
                .build();
    }

    static Duration timeout(int clients) {
        Duration timeout = Duration.ofSeconds(Long.max(MIN_TIMEOUT.toSeconds(), clients));
        return timeout.compareTo(MAX_TIMEOUT) > 0 ? MAX_TIMEOUT : timeout;
    }

    // For testing only!
    void setTaskTimeout(Duration taskTimeout) {
        this.taskTimeout.set(taskTimeout);
    }

}
