// Copyright 2018 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.clustercontroller.core;

import com.yahoo.vdslib.state.ClusterState;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * A cluster state bundle is a wrapper around the baseline ("source of truth") cluster
 * state and any bucket space specific states that may be derived from it.
 *
 * The baseline state represents the generated state of the _nodes_ in the cluster,
 * while the per-space states represent possible transformations that make sense in
 * the context of that particular bucket space. The most prominent example is
 * transforming nodes in the default bucket space into maintenance mode if they have
 * merges pending in the global space.
 *
 * The baseline state is identical to the legacy, global cluster state that the
 * cluster controller has historically produced as its only output.
 *
 * The bundle also contains an additional "deferred activation" flag which tells
 * the recipient if the cluster state transition should complete immediately or
 * await an explicit activation RPC from the cluster controller.
 */
public class ClusterStateBundle {

    private final AnnotatedClusterState baselineState;
    private final Map<String, AnnotatedClusterState> derivedBucketSpaceStates;
    private final FeedBlock feedBlock;
    private final boolean deferredActivation;

    /**
     * Feed blocking status of the entire cluster that will be communicated to the nodes
     * as part of the cluster state bundle. If not present, or if blockFeedInCluster is
     * false, feed is not automatically blocked.
     *
     * Note that feed blocking only applies to client feed, not to feed generated by internal
     * maintenance operations such as merging.
     *
     * Immutable, so may be safely passed around.
     */
    public static class FeedBlock {
        private final boolean blockFeedInCluster;
        private final String description;

        public FeedBlock(boolean blockFeedInCluster, String description) {
            this.blockFeedInCluster = blockFeedInCluster;
            this.description = description;
        }

        public static FeedBlock blockedWithDescription(String desc) {
            return new FeedBlock(true, desc);
        }

        public boolean blockFeedInCluster() {
            return blockFeedInCluster;
        }

        public String getDescription() {
            return description;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            FeedBlock feedBlock = (FeedBlock) o;
            return (blockFeedInCluster == feedBlock.blockFeedInCluster &&
                    Objects.equals(description, feedBlock.description));
        }

        @Override
        public int hashCode() {
            return Objects.hash(blockFeedInCluster, description);
        }
    }

    public static class Builder {
        private final AnnotatedClusterState baselineState;
        private Map<String, AnnotatedClusterState> explicitDerivedStates;
        private ClusterStateDeriver stateDeriver;
        private Set<String> bucketSpaces;
        private boolean deferredActivation = false;
        private FeedBlock feedBlock = null;

        public Builder(AnnotatedClusterState baselineState) {
            this.baselineState = baselineState;
        }

        public Builder stateDeriver(ClusterStateDeriver stateDeriver) {
            this.stateDeriver = stateDeriver;
            return this;
        }

        public Builder bucketSpaces(Set<String> bucketSpaces) {
            if (this.explicitDerivedStates != null) {
                throw new IllegalStateException("Cannot set bucket spaces on Builder that already " +
                                                "has explicit derived states set");
            }
            this.bucketSpaces = bucketSpaces;
            return this;
        }

        public Builder bucketSpaces(String... bucketSpaces) {
            return bucketSpaces(new TreeSet<>(Arrays.asList(bucketSpaces)));
        }

        public Builder explicitDerivedStates(Map<String, AnnotatedClusterState> derivedStates) {
            if (this.bucketSpaces != null || this.stateDeriver != null) {
                throw new IllegalStateException("Cannot set explicitly derived states on Builder " +
                                                "that already has bucket spaces or deriver set");
            }
            this.explicitDerivedStates = derivedStates;
            return this;
        }

        public Builder deferredActivation(boolean deferred) {
            this.deferredActivation = deferred;
            return this;
        }

        public Builder feedBlock(FeedBlock fb) {
            this.feedBlock = fb;
            return this;
        }

        public ClusterStateBundle deriveAndBuild() {
            if ((stateDeriver == null || bucketSpaces == null || bucketSpaces.isEmpty()) && explicitDerivedStates == null) {
                return ClusterStateBundle.ofBaselineOnly(baselineState, feedBlock, deferredActivation);
            }
            Map<String, AnnotatedClusterState> derived;
            if (explicitDerivedStates != null) {
                derived = explicitDerivedStates;
            } else {
                derived = bucketSpaces.stream()
                        .collect(Collectors.toMap(
                                Function.identity(),
                                s -> stateDeriver.derivedFrom(baselineState, s)));
            }
            return new ClusterStateBundle(baselineState, derived, feedBlock, deferredActivation);
        }
    }

    private ClusterStateBundle(AnnotatedClusterState baselineState, Map<String, AnnotatedClusterState> derivedBucketSpaceStates) {
        this(baselineState, derivedBucketSpaceStates, null, false);
    }

    private ClusterStateBundle(AnnotatedClusterState baselineState,
                               Map<String, AnnotatedClusterState> derivedBucketSpaceStates,
                               FeedBlock feedBlock,
                               boolean deferredActivation) {
        this.baselineState = baselineState;
        this.derivedBucketSpaceStates = Collections.unmodifiableMap(derivedBucketSpaceStates);
        this.feedBlock = feedBlock;
        this.deferredActivation = deferredActivation;
    }

    public static Builder builder(AnnotatedClusterState baselineState) {
        return new Builder(baselineState);
    }

    public static ClusterStateBundle of(AnnotatedClusterState baselineState, Map<String, AnnotatedClusterState> derivedBucketSpaceStates) {
        return new ClusterStateBundle(baselineState, derivedBucketSpaceStates);
    }

    public static ClusterStateBundle of(AnnotatedClusterState baselineState,
                                        Map<String, AnnotatedClusterState> derivedBucketSpaceStates,
                                        boolean deferredActivation) {
        return new ClusterStateBundle(baselineState, derivedBucketSpaceStates, null, deferredActivation);
    }

    public static ClusterStateBundle of(AnnotatedClusterState baselineState,
                                        Map<String, AnnotatedClusterState> derivedBucketSpaceStates,
                                        FeedBlock feedBlock,
                                        boolean deferredActivation) {
        return new ClusterStateBundle(baselineState, derivedBucketSpaceStates, feedBlock, deferredActivation);
    }

    public static ClusterStateBundle ofBaselineOnly(AnnotatedClusterState baselineState,
                                                    FeedBlock feedBlock,
                                                    boolean deferredActivation) {
        return new ClusterStateBundle(baselineState, Collections.emptyMap(), feedBlock, deferredActivation);
    }

    public static ClusterStateBundle ofBaselineOnly(AnnotatedClusterState baselineState) {
        return new ClusterStateBundle(baselineState, Collections.emptyMap());
    }

    public static ClusterStateBundle empty() {
        return ofBaselineOnly(AnnotatedClusterState.emptyState());
    }

    public AnnotatedClusterState getBaselineAnnotatedState() {
        return baselineState;
    }

    public ClusterState getBaselineClusterState() {
        return baselineState.getClusterState();
    }

    public Map<String, AnnotatedClusterState> getDerivedBucketSpaceStates() {
        return derivedBucketSpaceStates;
    }

    public boolean deferredActivation() { return this.deferredActivation; }

    public ClusterStateBundle cloneWithMapper(Function<ClusterState, ClusterState> mapper) {
        AnnotatedClusterState clonedBaseline = baselineState.cloneWithClusterState(
                mapper.apply(baselineState.getClusterState().clone()));
        Map<String, AnnotatedClusterState> clonedDerived = derivedBucketSpaceStates.entrySet().stream()
                .collect(Collectors.toMap(e -> e.getKey(), e -> e.getValue().cloneWithClusterState(
                        mapper.apply(e.getValue().getClusterState().clone()))));
        return new ClusterStateBundle(clonedBaseline, clonedDerived, feedBlock, deferredActivation);
    }

    public ClusterStateBundle clonedWithVersionSet(int version) {
        return cloneWithMapper(state -> {
            state.setVersion(version);
            return state;
        });
    }

    public boolean similarTo(ClusterStateBundle other) {
        if (!baselineState.getClusterState().similarToIgnoringInitProgress(other.baselineState.getClusterState())) {
            return false;
        }
        if (clusterFeedIsBlocked() != other.clusterFeedIsBlocked()) {
            return false;
        }
        // FIXME we currently treat mismatching bucket space sets as unchanged to avoid breaking some tests
        return derivedBucketSpaceStates.entrySet().stream()
                .allMatch(entry -> other.derivedBucketSpaceStates.getOrDefault(entry.getKey(), entry.getValue())
                        .getClusterState().similarToIgnoringInitProgress(entry.getValue().getClusterState()));
    }

    public int getVersion() {
        return baselineState.getClusterState().getVersion();
    }

    public Optional<FeedBlock> getFeedBlock() {
        return Optional.ofNullable(feedBlock);
    }

    public FeedBlock getFeedBlockOrNull() {
        return feedBlock;
    }

    public boolean clusterFeedIsBlocked() {
        return (feedBlock != null && feedBlock.blockFeedInCluster());
    }

    @Override
    public String toString() {
        String feedBlockedStr = clusterFeedIsBlocked()
                ? String.format(", feed blocked: '%s'", feedBlock.description)
                : "";
        if (derivedBucketSpaceStates.isEmpty()) {
            return String.format("ClusterStateBundle('%s'%s%s)", baselineState,
                    deferredActivation ? " (deferred activation)" : "",
                    feedBlockedStr);
        }
        Map<String, AnnotatedClusterState> orderedStates = new TreeMap<>(derivedBucketSpaceStates);
        return String.format("ClusterStateBundle('%s', %s%s%s)", baselineState, orderedStates.entrySet().stream()
                .map(e -> String.format("%s '%s'", e.getKey(), e.getValue()))
                .collect(Collectors.joining(", ")),
                deferredActivation ? " (deferred activation)" : "",
                feedBlockedStr);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ClusterStateBundle that = (ClusterStateBundle) o;
        return (deferredActivation == that.deferredActivation &&
                Objects.equals(baselineState, that.baselineState) &&
                Objects.equals(derivedBucketSpaceStates, that.derivedBucketSpaceStates) &&
                Objects.equals(feedBlock, that.feedBlock));
    }

    @Override
    public int hashCode() {
        return Objects.hash(baselineState, derivedBucketSpaceStates, feedBlock, deferredActivation);
    }
}
