// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.controller.deployment;

import com.yahoo.component.Version;
import com.yahoo.text.Text;
import com.yahoo.vespa.hosted.controller.Application;
import com.yahoo.vespa.hosted.controller.api.integration.deployment.ApplicationVersion;
import com.yahoo.vespa.hosted.controller.application.Change;
import com.yahoo.vespa.hosted.controller.application.Deployment;

import java.util.Objects;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * Source and target versions for an application.
 *
 * @author jvenstad
 * @author mpolden
 */
public class Versions {

    private final Version targetPlatform;
    private final ApplicationVersion targetApplication;
    private final Optional<Version> sourcePlatform;
    private final Optional<ApplicationVersion> sourceApplication;

    public Versions(Version targetPlatform, ApplicationVersion targetApplication, Optional<Version> sourcePlatform,
                    Optional<ApplicationVersion> sourceApplication) {
        if (sourcePlatform.isPresent() ^ sourceApplication.isPresent())
            throw new IllegalArgumentException("Sources must both be present or absent.");

        this.targetPlatform = requireNonNull(targetPlatform);
        this.targetApplication = requireNonNull(targetApplication);
        this.sourcePlatform = requireNonNull(sourcePlatform);
        this.sourceApplication = requireNonNull(sourceApplication);
    }

    /** A copy of this, without source versions. */
    public Versions withoutSources() {
        return new Versions(targetPlatform, targetApplication, Optional.empty(), Optional.empty());
    }

    /** Target platform version for this */
    public Version targetPlatform() {
        return targetPlatform;
    }

    /** Target application version for this */
    public ApplicationVersion targetApplication() {
        return targetApplication;
    }

    /** Source platform version for this */
    public Optional<Version> sourcePlatform() {
        return sourcePlatform;
    }

    /** Source application version for this */
    public Optional<ApplicationVersion> sourceApplication() {
        return sourceApplication;
    }

    /** Returns whether source versions are present and match those of the given job other versions. */
    public boolean sourcesMatchIfPresent(Versions versions) {
        return (sourcePlatform.map(targetPlatform::equals).orElse(true) ||
                sourcePlatform.equals(versions.sourcePlatform())) &&
               (sourceApplication.map(targetApplication::equals).orElse(true) ||
                sourceApplication.equals(versions.sourceApplication()));
    }

    public boolean targetsMatch(Versions versions) {
        return targetPlatform.equals(versions.targetPlatform()) &&
               targetApplication.equals(versions.targetApplication());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if ( ! (o instanceof Versions)) return false;
        Versions versions = (Versions) o;
        return Objects.equals(targetPlatform, versions.targetPlatform) &&
               Objects.equals(targetApplication, versions.targetApplication) &&
               Objects.equals(sourcePlatform, versions.sourcePlatform) &&
               Objects.equals(sourceApplication, versions.sourceApplication);
    }

    @Override
    public int hashCode() {
        return Objects.hash(targetPlatform, targetApplication, sourcePlatform, sourceApplication);
    }

    @Override
    public String toString() {
        return Text.format("platform %s%s, application %s%s",
                             sourcePlatform.filter(source -> !source.equals(targetPlatform))
                                           .map(source -> source + " -> ").orElse(""),
                             targetPlatform,
                             sourceApplication.filter(source -> !source.equals(targetApplication))
                                              .map(source -> source.id() + " -> ").orElse(""),
                             targetApplication.id());
    }

    /** Create versions using given change and application */
    public static Versions from(Change change, Application application, Optional<Version> existingPlatform,
                                Optional<ApplicationVersion> existingApplication, Version defaultPlatformVersion) {
        return new Versions(targetPlatform(application, change, existingPlatform, defaultPlatformVersion),
                            targetApplication(application, change, existingApplication),
                            existingPlatform,
                            existingApplication);
    }

    /** Create versions using given change and application */
    public static Versions from(Change change, Application application, Optional<Deployment> deployment,
                                Version defaultPlatformVersion) {
        return new Versions(targetPlatform(application, change, deployment.map(Deployment::version), defaultPlatformVersion),
                            targetApplication(application, change, deployment.map(Deployment::applicationVersion)),
                            deployment.map(Deployment::version),
                            deployment.map(Deployment::applicationVersion));
    }

    private static Version targetPlatform(Application application, Change change, Optional<Version> existing,
                                          Version defaultVersion) {
        if (change.isPinned() && change.platform().isPresent())
            return change.platform().get();

        return max(change.platform(), existing)
                .orElseGet(() -> application.oldestDeployedPlatform().orElse(defaultVersion));
    }

    private static ApplicationVersion targetApplication(Application application, Change change,
                                                        Optional<ApplicationVersion> existing) {
        return change.application()
                     .or(() -> existing)
                     .orElseGet(() -> defaultApplicationVersion(application));
    }

    private static ApplicationVersion defaultApplicationVersion(Application application) {
        return application.oldestDeployedApplication()
                          .or(application::latestVersion)
                          .orElse(ApplicationVersion.unknown);
    }

    private static <T extends Comparable<T>> Optional<T> max(Optional<T> o1, Optional<T> o2) {
        return o1.isEmpty() ? o2 : o2.isEmpty() ? o1 : o1.get().compareTo(o2.get()) >= 0 ? o1 : o2;
    }

}
