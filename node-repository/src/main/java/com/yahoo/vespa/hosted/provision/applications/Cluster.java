// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.provision.applications;

import com.yahoo.config.provision.Capacity;
import com.yahoo.config.provision.ClusterResources;
import com.yahoo.config.provision.ClusterSpec;
import com.yahoo.vespa.hosted.provision.autoscale.Autoscaler;
import com.yahoo.vespa.hosted.provision.autoscale.Autoscaling;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The node repo's view of a cluster in an application deployment.
 *
 * This is immutable, and must be locked with the application lock on read-modify-write.
 *
 * @author bratseth
 */
public class Cluster {

    public static final int maxScalingEvents = 15;

    private final ClusterSpec.Id id;
    private final boolean exclusive;
    private final ClusterResources min, max;
    private final boolean required;
    private final Autoscaling suggested;
    private final Autoscaling target;

    /** The maxScalingEvents last scaling events of this, sorted by increasing time (newest last) */
    private final List<ScalingEvent> scalingEvents;
    private final AutoscalingStatus autoscalingStatus;

    public Cluster(ClusterSpec.Id id,
                   boolean exclusive,
                   ClusterResources minResources,
                   ClusterResources maxResources,
                   boolean required,
                   Autoscaling suggested,
                   Autoscaling target,
                   List<ScalingEvent> scalingEvents,
                   AutoscalingStatus autoscalingStatus) {
        this.id = Objects.requireNonNull(id);
        this.exclusive = exclusive;
        this.min = Objects.requireNonNull(minResources);
        this.max = Objects.requireNonNull(maxResources);
        this.required = required;
        this.suggested = Objects.requireNonNull(suggested);
        Objects.requireNonNull(target);
        if (target.resources().isPresent() && ! target.resources().get().isWithin(minResources, maxResources))
            this.target = Autoscaling.empty();
        else
            this.target = target;
        this.scalingEvents = List.copyOf(scalingEvents);
        this.autoscalingStatus = autoscalingStatus;
    }

    public ClusterSpec.Id id() { return id; }

    /** Returns whether the nodes allocated to this cluster must be on host exclusively dedicated to this application */
    public boolean exclusive() { return exclusive; }

    /** Returns the configured minimal resources in this cluster */
    public ClusterResources minResources() { return min; }

    /** Returns the configured maximal resources in this cluster */
    public ClusterResources maxResources() { return max; }

    /**
     * Returns whether the resources of this cluster are required to be within the specified min and max.
     * Otherwise they may be adjusted by capacity policies.
     */
    public boolean required() { return required; }

    /**
     * Returns the computed resources (between min and max, inclusive) this cluster should
     * have allocated at the moment (whether or not it actually has it),
     * or empty if the system currently has no target.
     */
    public Autoscaling target() { return target; }

    /**
     * The suggested resources, which may or may not be within the min and max limits,
     * or empty if there is currently no recorded suggestion.
     */
    public Autoscaling suggested() { return suggested; }

    /** Returns true if there is a current suggestion and we should actually make this suggestion to users. */
    public boolean shouldSuggestResources(ClusterResources currentResources) {
        if (suggested.resources().isEmpty()) return false;
        if (suggested.resources().get().isWithin(min, max)) return false;
        if ( ! Autoscaler.worthRescaling(currentResources, suggested.resources().get())) return false;
        return true;
    }

    /** Returns the recent scaling events in this cluster */
    public List<ScalingEvent> scalingEvents() { return scalingEvents; }

    public Optional<ScalingEvent> lastScalingEvent() {
        if (scalingEvents.isEmpty()) return Optional.empty();
        return Optional.of(scalingEvents.get(scalingEvents.size() - 1));
    }

    /** The latest autoscaling status of this cluster, or unknown (never null) if none */
    public AutoscalingStatus autoscalingStatus() { return autoscalingStatus; }

    public Cluster withConfiguration(boolean exclusive, Capacity capacity) {
        return new Cluster(id, exclusive,
                           capacity.minResources(), capacity.maxResources(), capacity.isRequired(),
                           suggested, target, scalingEvents, autoscalingStatus);
    }

    public Cluster withSuggested(Autoscaling suggested) {
        return new Cluster(id, exclusive, min, max, required, suggested, target, scalingEvents, autoscalingStatus);
    }

    public Cluster withTarget(Autoscaling target) {
        return new Cluster(id, exclusive, min, max, required, suggested, target, scalingEvents, autoscalingStatus);
    }

    /** Add or update (based on "at" time) a scaling event */
    public Cluster with(ScalingEvent scalingEvent) {
        List<ScalingEvent> scalingEvents = new ArrayList<>(this.scalingEvents);

        int existingIndex = eventIndexAt(scalingEvent.at());
        if (existingIndex >= 0)
            scalingEvents.set(existingIndex, scalingEvent);
        else
            scalingEvents.add(scalingEvent);

        prune(scalingEvents);
        return new Cluster(id, exclusive, min, max, required, suggested, target, scalingEvents, autoscalingStatus);
    }

    public Cluster with(AutoscalingStatus autoscalingStatus) {
        if (autoscalingStatus.equals(this.autoscalingStatus)) return this;
        return new Cluster(id, exclusive, min, max, required, suggested, target, scalingEvents, autoscalingStatus);
    }

    @Override
    public int hashCode() { return id.hashCode(); }

    @Override
    public boolean equals(Object other) {
        if (other == this) return true;
        if ( ! (other instanceof Cluster)) return false;
        return ((Cluster)other).id().equals(this.id);
    }

    @Override
    public String toString() { return id.toString(); }

    private void prune(List<ScalingEvent> scalingEvents) {
        while (scalingEvents.size() > maxScalingEvents)
            scalingEvents.remove(0);
    }

    private int eventIndexAt(Instant at) {
        for (int i = 0; i < scalingEvents.size(); i++) {
            if (scalingEvents.get(i).at().equals(at))
                return i;
        }
        return -1;
    }

    public static Cluster create(ClusterSpec.Id id, boolean exclusive, Capacity requested) {
        return new Cluster(id, exclusive, requested.minResources(), requested.maxResources(), requested.isRequired(),
                           Autoscaling.empty(), Autoscaling.empty(), List.of(), AutoscalingStatus.empty());
    }

}
