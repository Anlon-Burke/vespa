// Copyright 2018 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.config.server.maintenance;

import com.google.inject.Inject;
import com.yahoo.cloud.config.ConfigserverConfig;
import com.yahoo.component.AbstractComponent;
import com.yahoo.jdisc.Metric;
import com.yahoo.vespa.config.server.ApplicationRepository;
import com.yahoo.vespa.config.server.ConfigServerBootstrap;
import com.yahoo.vespa.config.server.application.ConfigConvergenceChecker;
import com.yahoo.vespa.curator.Curator;
import com.yahoo.vespa.flags.FlagSource;

import java.time.Clock;
import java.time.Duration;

/**
 * Maintenance jobs of the config server.
 * Each maintenance job is a singleton instance of its implementing class, created and owned by this,
 * and running its own dedicated thread. {@link ConfigServerBootstrap} is injected into this class, so
 * no maintainers will run until bootstrapping is done
 *
 * @author hmusum
 */
public class ConfigServerMaintenance extends AbstractComponent {

    private final TenantsMaintainer tenantsMaintainer;
    private final FileDistributionMaintainer fileDistributionMaintainer;
    private final SessionsMaintainer sessionsMaintainer;
    private final ApplicationPackageMaintainer applicationPackageMaintainer;
    private final ReindexingMaintainer reindexingMaintainer;

    @Inject
    public ConfigServerMaintenance(ConfigServerBootstrap configServerBootstrap,
                                   ConfigserverConfig configserverConfig,
                                   ApplicationRepository applicationRepository,
                                   Curator curator,
                                   FlagSource flagSource,
                                   ConfigConvergenceChecker convergence) {
        DefaultTimes defaults = new DefaultTimes(configserverConfig);
        tenantsMaintainer = new TenantsMaintainer(applicationRepository, curator, flagSource, defaults.defaultInterval, Clock.systemUTC());
        fileDistributionMaintainer = new FileDistributionMaintainer(applicationRepository, curator, defaults.defaultInterval, flagSource);
        sessionsMaintainer = new SessionsMaintainer(applicationRepository, curator, Duration.ofSeconds(30), flagSource);
        applicationPackageMaintainer = new ApplicationPackageMaintainer(applicationRepository, curator, Duration.ofSeconds(30), flagSource);
        reindexingMaintainer = new ReindexingMaintainer(applicationRepository, curator, flagSource, Duration.ofMinutes(30), convergence, Clock.systemUTC());
    }

    @Override
    public void deconstruct() {
        fileDistributionMaintainer.close();
        sessionsMaintainer.close();
        applicationPackageMaintainer.close();
        tenantsMaintainer.close();
        reindexingMaintainer.close();
    }

    /*
     * Default values from config. If one of the values needs to be changed, add the value to
     * configserver-config.xml in the config server application directory and restart the config server
     */
    private static class DefaultTimes {

        private final Duration defaultInterval;

        DefaultTimes(ConfigserverConfig configserverConfig) {
            this.defaultInterval = Duration.ofMinutes(configserverConfig.maintainerIntervalMinutes());
        }
    }

}
