// Copyright Verizon Media. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.config.server.maintenance;

import com.yahoo.log.LogLevel;
import com.yahoo.vespa.config.server.ApplicationRepository;
import com.yahoo.vespa.curator.Curator;
import com.yahoo.vespa.flags.FlagSource;

import java.time.Duration;

/**
 * Removes expired sessions and locks
 * <p>
 * Note: Unit test is in ApplicationRepositoryTest
 *
 * @author hmusum
 */
public class SessionsMaintainer extends ConfigServerMaintainer {
    private final boolean hostedVespa;
    private int iteration = 0;

    SessionsMaintainer(ApplicationRepository applicationRepository, Curator curator, Duration interval, FlagSource flagSource) {
        super(applicationRepository, curator, flagSource, Duration.ofMinutes(1), interval);
        this.hostedVespa = applicationRepository.configserverConfig().hostedVespa();
    }

    @Override
    protected boolean maintain() {
        if (iteration % 10 == 0)
            log.log(LogLevel.INFO, () -> "Running " + SessionsMaintainer.class.getSimpleName() + ", iteration "  + iteration);

        applicationRepository.deleteExpiredLocalSessions();

        if (hostedVespa) {
            Duration expiryTime = Duration.ofMinutes(90);
            int deleted = applicationRepository.deleteExpiredRemoteSessions(expiryTime);
            log.log(LogLevel.FINE, () -> "Deleted " + deleted + " expired remote sessions older than " + expiryTime);
        }

        iteration++;
        return true;
    }

}
