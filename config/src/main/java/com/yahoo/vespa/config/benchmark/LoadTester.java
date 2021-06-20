// Copyright Verizon Media. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.config.benchmark;

import com.yahoo.collections.Tuple2;
import com.yahoo.io.IOUtils;
import com.yahoo.jrt.Spec;
import com.yahoo.jrt.Supervisor;
import com.yahoo.jrt.Target;
import com.yahoo.jrt.Transport;
import com.yahoo.jrt.TransportMetrics;
import com.yahoo.system.CommandLineParser;
import com.yahoo.vespa.config.ConfigDefinitionKey;
import com.yahoo.vespa.config.ConfigKey;
import com.yahoo.vespa.config.protocol.CompressionType;
import com.yahoo.vespa.config.protocol.DefContent;
import com.yahoo.vespa.config.protocol.JRTClientConfigRequest;
import com.yahoo.vespa.config.protocol.JRTClientConfigRequestV3;
import com.yahoo.vespa.config.protocol.JRTConfigRequestFactory;
import com.yahoo.vespa.config.protocol.Trace;
import com.yahoo.vespa.config.util.ConfigUtils;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

import static com.yahoo.vespa.config.ConfigKey.createFull;

/**
 * A config client for generating load against a config server or config proxy.
 * <p>
 * Log messages from a run will have a # first in the line, the end result will not.
 *
 * @author Vegard Havdal
 */
public class LoadTester {

    private static boolean debug = false;
    private final Transport transport = new Transport("rpc-client");
    protected Supervisor supervisor = new Supervisor(transport);
    private List<ConfigKey<?>> configs = new ArrayList<>();
    private Map<ConfigDefinitionKey, Tuple2<String, String[]>> defs = new HashMap<>();
    private final CompressionType compressionType = JRTConfigRequestFactory.getCompressionType();

    /**
     * @param args command-line arguments
     */
    public static void main(String[] args) throws IOException, InterruptedException {
        CommandLineParser parser = new CommandLineParser("LoadTester", args);
        parser.addLegalUnarySwitch("-d", "debug");
        parser.addRequiredBinarySwitch("-c", "host (config proxy or server)");
        parser.addRequiredBinarySwitch("-p", "port");
        parser.addRequiredBinarySwitch("-i", "iterations per thread");
        parser.addRequiredBinarySwitch("-t", "threads");
        parser.addLegalBinarySwitch("-l", "configs file, on form name,configid. (To get list: vespa-configproxy-cmd -m cache | cut -d ',' -f1-2)");
        parser.addLegalBinarySwitch("-dd", "dir with def files, must be of form name.def");
        parser.parse();
        String host = parser.getBinarySwitches().get("-c");
        int port = Integer.parseInt(parser.getBinarySwitches().get("-p"));
        int iterations = Integer.parseInt(parser.getBinarySwitches().get("-i"));
        int threads = Integer.parseInt(parser.getBinarySwitches().get("-t"));
        String configsList = parser.getBinarySwitches().get("-l");
        String defPath = parser.getBinarySwitches().get("-dd");
        debug = parser.getUnarySwitches().contains("-d");
        new LoadTester().runLoad(host, port, iterations, threads, configsList, defPath);
    }

    private void runLoad(String host, int port, int iterations, int threads,
                         String configsList, String defPath) throws IOException, InterruptedException {
        configs = readConfigs(configsList);
        defs = readDefs(defPath);
        List<LoadThread> threadList = new ArrayList<>();
        long startInNanos = System.nanoTime();
        Metrics m = new Metrics();

        for (int i = 0; i < threads; i++) {
            LoadThread lt = new LoadThread(iterations, host, port);
            threadList.add(lt);
            lt.start();
        }

        for (LoadThread lt : threadList) {
            lt.join();
            m.merge(lt.metrics);
        }
        printOutput(startInNanos, threads, iterations, m);
    }

    private Map<ConfigDefinitionKey, Tuple2<String, String[]>> readDefs(String defPath) throws IOException {
        Map<ConfigDefinitionKey, Tuple2<String, String[]>> ret = new HashMap<>();
        if (defPath == null) return ret;

        File defDir = new File(defPath);
        if (!defDir.isDirectory()) {
            System.out.println("# Given def file dir is not a directory: " +
                               defDir.getPath() + " , will not send def contents in requests.");
            return ret;
        }
        File[] files = defDir.listFiles();
        if (files == null) {
            System.out.println("# Given def file dir has no files: " +
                               defDir.getPath() + " , will not send def contents in requests.");
            return ret;
        }
        for (File f : files) {
            String name = f.getName();
            if (!name.endsWith(".def")) continue;
            String contents = IOUtils.readFile(f);
            ConfigDefinitionKey key = ConfigUtils.createConfigDefinitionKeyFromDefFile(f);
            ret.put(key, new Tuple2<>(ConfigUtils.getDefMd5(Arrays.asList(contents.split("\n"))), contents.split("\n")));
        }
        System.out.println("#  Read " + ret.size() + " def files from " + defDir.getPath());
        return ret;
    }

    private void printOutput(long startInNanos, long threads, long iterations, Metrics metrics) {
        float durSec = (float) (System.nanoTime() - startInNanos) / 1_000_000_000f;
        StringBuilder sb = new StringBuilder();
        sb.append("#reqs/sec #avglatency #minlatency #maxlatency #failedrequests\n");
        sb.append(((float) (iterations * threads)) / durSec).append(",");
        sb.append((metrics.latencyInMillis / threads / iterations)).append(",");
        sb.append((metrics.minLatency)).append(",");
        sb.append((metrics.maxLatency)).append(",");
        sb.append((metrics.failedRequests));
        sb.append("\n");
        sb.append('#').append(TransportMetrics.getInstance().snapshot().toString()).append('\n');
        System.out.println(sb);
    }

    private List<ConfigKey<?>> readConfigs(String configsList) throws IOException {
        List<ConfigKey<?>> ret = new ArrayList<>();
        BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(configsList), StandardCharsets.UTF_8));
        String str = br.readLine();
        while (str != null) {
            String[] nameAndId = str.split(",");
            Tuple2<String, String> nameAndNamespace = ConfigUtils.getNameAndNamespaceFromString(nameAndId[0]);
            ConfigKey<?> key = new ConfigKey<>(nameAndNamespace.first, nameAndId[1], nameAndNamespace.second);
            ret.add(key);
            str = br.readLine();
        }
        br.close();
        return ret;
    }

    private static class Metrics {

        long latencyInMillis = 0;
        long failedRequests = 0;
        long maxLatency = Long.MIN_VALUE;
        long minLatency = Long.MAX_VALUE;

        public void merge(Metrics m) {
            this.latencyInMillis += m.latencyInMillis;
            this.failedRequests += m.failedRequests;
            updateMin(m.minLatency);
            updateMax(m.maxLatency);
        }

        public void update(long latency) {
            this.latencyInMillis += latency;
            updateMin(latency);
            updateMax(latency);
        }

        private void updateMin(long latency) {
            if (latency < minLatency)
                minLatency = latency;
        }

        private void updateMax(long latency) {
            if (latency > maxLatency)
                maxLatency = latency;
        }

        private void incFailedRequests() {
            failedRequests++;
        }
    }

    private class LoadThread extends Thread {

        private final int iterations;
        private final String host;
        private final int port;
        private final Metrics metrics = new Metrics();

        LoadThread(int iterations, String host, int port) {
            this.iterations = iterations;
            this.host = host;
            this.port = port;
        }

        @Override
        public void run() {
            Spec spec = new Spec(host, port);
            Target target = connect(spec);

            for (int i = 0; i < iterations; i++) {
                ConfigKey<?> reqKey = configs.get(ThreadLocalRandom.current().nextInt(configs.size()));
                ConfigDefinitionKey dKey = new ConfigDefinitionKey(reqKey);
                Tuple2<String, String[]> defContent = defs.get(dKey);
                if (defContent == null && defs.size() > 0) { // Only complain if we actually did run with a def dir
                    System.out.println("# No def found for " + dKey + ", not sending in request.");
                }
                ConfigKey<?> configKey = createFull(reqKey.getName(), reqKey.getConfigId(), reqKey.getNamespace(), defContent.first);
                JRTClientConfigRequest request = createRequest(configKey, defContent.second);
                if (debug) System.out.println("# Requesting: " + reqKey);
                long start = System.nanoTime();
                target.invokeSync(request.getRequest(), 10.0);
                long durationInMillis = (System.nanoTime() - start) / 1_000_000;
                if (request.isError()) {
                    target = handleError(request, spec, target);
                } else {
                    metrics.update(durationInMillis);
                }
            }
        }

        private JRTClientConfigRequest createRequest(ConfigKey<?> reqKey, String[] defContent) {
            if (defContent == null) defContent = new String[0];
            final long serverTimeout = 1000;
            return JRTClientConfigRequestV3.createWithParams(reqKey, DefContent.fromList(Arrays.asList(defContent)),
                                                             ConfigUtils.getCanonicalHostName(), "", 0, serverTimeout, Trace.createDummy(),
                                                             compressionType, Optional.empty());
        }

        private Target connect(Spec spec) {
            return supervisor.connect(spec);
        }

        private Target handleError(JRTClientConfigRequest request, Spec spec, Target target) {
            if (List.of("Connection lost", "Connection down").contains(request.errorMessage())) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
                System.out.println("# Connection lost, reconnecting...");
                target.close();
                target = connect(spec);
            } else {
                System.err.println(request.errorMessage());
            }
            metrics.incFailedRequests();
            return target;
        }

    }

}
