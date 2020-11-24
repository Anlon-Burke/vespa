// Copyright Verizon Media. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.config.server.application;

import com.yahoo.config.model.api.Reindexing;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toMap;
import static java.util.stream.Collectors.toUnmodifiableMap;

/**
 * Pending and ready reindexing per document type. Each document type can have either a pending or a ready reindexing.
 * Each cluster may also have a global status, which is merged with its document type-specific status, by selecting
 * whichever status is ready the latest. The application may also have a global status, which is merged likewise.
 * This is immutable.
 *
 * @author jonmv
 */
public class ApplicationReindexing implements Reindexing {

    private final boolean enabled;
    private final Status common;
    private final Map<String, Cluster> clusters;

    ApplicationReindexing(boolean enabled, Status common, Map<String, Cluster> clusters) {
        this.enabled = enabled;
        this.common = requireNonNull(common);
        this.clusters = Map.copyOf(clusters);
    }

    /** Reindexing for the whole application ready now. */
    public static ApplicationReindexing ready(Instant now) {
        return new ApplicationReindexing(true, new Status(now), Map.of());
    }

    /** Returns a copy of this with reindexing for the whole application ready at the given instant. */
    public ApplicationReindexing withReady(Instant readyAt) {
        return new ApplicationReindexing(enabled,
                                         new Status(readyAt),
                                         clusters.entrySet().stream()
                                                 .filter(cluster -> ! cluster.getValue().pending.isEmpty())
                                                 .collect(toUnmodifiableMap(cluster -> cluster.getKey(),
                                                                            cluster -> new Cluster(new Status(readyAt),
                                                                                                   cluster.getValue().pending,
                                                                                                   Map.of()))));
    }

    /** Returns a copy of this with reindexing for the given cluster ready at the given instant. */
    public ApplicationReindexing withReady(String cluster, Instant readyAt) {
        Cluster current = clusters.getOrDefault(cluster, Cluster.ready(common));
        Cluster modified = new Cluster(new Status(readyAt), current.pending, Map.of());
        return new ApplicationReindexing(enabled, common, with(cluster, modified, clusters));
    }

    /** Returns a copy of this with reindexing for the given document type in the given cluster ready at the given instant. */
    public ApplicationReindexing withReady(String cluster, String documentType, Instant readyAt) {
        Cluster current = clusters.getOrDefault(cluster, Cluster.ready(common));
        Cluster modified = new Cluster(current.common,
                                       current.pending,
                                       with(documentType, new Status(readyAt), current.ready));
        return new ApplicationReindexing(enabled, common, with(cluster, modified, clusters));
    }

    /** Returns a copy of this with a pending reindexing at the given generation, for the given document type. */
    public ApplicationReindexing withPending(String cluster, String documentType, long requiredGeneration) {
        Cluster current = clusters.getOrDefault(cluster, Cluster.ready(common));
        Cluster modified = new Cluster(current.common,
                                       with(documentType, requirePositive(requiredGeneration), current.pending),
                                       current.ready);
        return new ApplicationReindexing(enabled, common, with(cluster, modified, clusters));
    }

    /** Returns a copy of this with no pending reindexing for the given document type. */
    public ApplicationReindexing withoutPending(String cluster, String documentType) {
        Cluster current = clusters.getOrDefault(cluster, Cluster.ready(common));
        Cluster modified = new Cluster(current.common,
                                       without(documentType, current.pending),
                                       current.ready);
        return new ApplicationReindexing(enabled, common, with(cluster, modified, clusters));
    }

    /** Returns a copy of this with the enabled-state set to the given value. */
    public ApplicationReindexing enabled(boolean enabled) {
        return new ApplicationReindexing(enabled, common, clusters);
    }

    @Override
    public boolean enabled() {
        return enabled;
    }

    /** The common reindexing status for the whole application. */
    public Status common() {
        return common;
    }

    /** The reindexing status of each of the clusters of this application. */
    public Map<String, Cluster> clusters() { return clusters; }

    @Override
    public Optional<Reindexing.Status> status(String cluster, String documentType) {
        return ! clusters.containsKey(cluster)
               ? Optional.of(common())
               : ! clusters.get(cluster).ready().containsKey(documentType)
                 ? Optional.of(clusters.get(cluster).common())
                 : Optional.of(clusters.get(cluster).ready().get(documentType));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ApplicationReindexing that = (ApplicationReindexing) o;
        return enabled == that.enabled &&
               common.equals(that.common) &&
               clusters.equals(that.clusters);
    }

    @Override
    public int hashCode() {
        return Objects.hash(enabled, common, clusters);
    }

    @Override
    public String toString() {
        return "ApplicationReindexing{" +
               "enabled=" + enabled +
               ", common=" + common +
               ", clusters=" + clusters +
               '}';
    }

    /** Reindexing status for a single content cluster in an application. */
    public static class Cluster {

        private static Cluster ready(Status common) { return new Cluster(common, Map.of(), Map.of()); }

        private final Status common;
        private final Map<String, Long> pending;
        private final Map<String, Status> ready;

        Cluster(Status common, Map<String, Long> pending, Map<String, Status> ready) {
            this.common = requireNonNull(common);
            this.pending = Map.copyOf(pending);
            this.ready = Map.copyOf(ready);
        }

        /** The common reindexing status for all document types in this cluster. */
        public Status common() {
            return common;
        }

        /** The config generation at which the application must have converged for the latest reindexing to begin, per document type.  */
        public Map<String, Long> pending() {
            return pending;
        }

        /** The reindexing status for ready document types in this cluster. */
        public Map<String, Status> ready() {
            return ready;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Cluster cluster = (Cluster) o;
            return common.equals(cluster.common) &&
                   pending.equals(cluster.pending) &&
                   ready.equals(cluster.ready);
        }

        @Override
        public int hashCode() {
            return Objects.hash(common, pending, ready);
        }

        @Override
        public String toString() {
            return "Cluster{" +
                   "common=" + common +
                   ", pending=" + pending +
                   ", ready=" + ready +
                   '}';
        }

    }


    /** Reindexing status common to an application, one of its clusters, or a single document type in a cluster. */
    public static class Status implements Reindexing.Status {

        private final Instant ready;

        Status(Instant ready) {
            this.ready = ready.truncatedTo(ChronoUnit.MILLIS);
        }

        @Override
        public Instant ready() { return ready; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Status status = (Status) o;
            return ready.equals(status.ready);
        }

        @Override
        public int hashCode() {
            return Objects.hash(ready);
        }

        @Override
        public String toString() {
            return "ready at " + ready;
        }

    }


    private static long requirePositive(long generation) {
        if (generation <= 0)
            throw new IllegalArgumentException("Generation must be positive, but was " + generation);

        return generation;
    }

    private static <T> Map<String, T> without(String removed, Map<String, T> map) {
        return map.keySet().stream()
                  .filter(key -> ! removed.equals(key))
                  .collect(toUnmodifiableMap(key -> key,
                                             key -> map.get(key)));
    }

    private static <T> Map<String, T> with(String added, T value, Map<String, T> map) {
        return Stream.concat(Stream.of(added), map.keySet().stream()).distinct()
                     .collect(toUnmodifiableMap(key -> key,
                                                key -> added.equals(key) ? value
                                                                         : map.get(key)));
    }

}
