// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.config.server.maintenance;

import com.yahoo.cloud.config.ConfigserverConfig;
import com.yahoo.config.FileReference;
import com.yahoo.config.provision.ApplicationId;
import com.yahoo.config.subscription.ConfigSourceSet;
import com.yahoo.jrt.Supervisor;
import com.yahoo.jrt.Transport;
import com.yahoo.vespa.config.ConnectionPool;
import com.yahoo.vespa.config.server.ApplicationRepository;
import com.yahoo.vespa.config.server.session.Session;
import com.yahoo.vespa.config.server.session.SessionRepository;
import com.yahoo.vespa.config.server.tenant.Tenant;
import com.yahoo.vespa.curator.Curator;
import com.yahoo.vespa.defaults.Defaults;
import com.yahoo.vespa.filedistribution.FileDistributionConnectionPool;
import com.yahoo.vespa.filedistribution.FileDownloader;
import com.yahoo.vespa.filedistribution.FileReferenceDownload;
import com.yahoo.vespa.flags.FlagSource;

import java.io.File;
import java.time.Duration;
import java.util.List;
import java.util.logging.Logger;

import static com.yahoo.vespa.config.server.filedistribution.FileDistributionUtil.fileReferenceExistsOnDisk;
import static com.yahoo.vespa.config.server.filedistribution.FileDistributionUtil.getOtherConfigServersInCluster;

/**
 * Verifies that all active sessions has an application package on local disk.
 * If not, the package is downloaded with file distribution. This can happen e.g.
 * if a configserver is down when the application is deployed.
 *
 * @author gjoranv
 */
public class ApplicationPackageMaintainer extends ConfigServerMaintainer {

    private static final Logger log = Logger.getLogger(ApplicationPackageMaintainer.class.getName());

    private final ApplicationRepository applicationRepository;
    private final File downloadDirectory;
    private final ConfigserverConfig configserverConfig;
    private final Supervisor supervisor = new Supervisor(new Transport("filedistribution-pool")).setDropEmptyBuffers(true);
    private final FileDownloader fileDownloader;

    ApplicationPackageMaintainer(ApplicationRepository applicationRepository,
                                 Curator curator,
                                 Duration interval,
                                 FlagSource flagSource) {
        super(applicationRepository, curator, flagSource, applicationRepository.clock().instant(), interval, false);
        this.applicationRepository = applicationRepository;
        this.configserverConfig = applicationRepository.configserverConfig();
        this.downloadDirectory = new File(Defaults.getDefaults().underVespaHome(configserverConfig.fileReferencesDir()));
        this.fileDownloader = createFileDownloader(configserverConfig, downloadDirectory, supervisor);
    }

    @Override
    protected double maintain() {
        if (getOtherConfigServersInCluster(configserverConfig).isEmpty()) return 1.0; // Nothing to do

        int attempts = 0;
        int failures = 0;

        for (var applicationId : applicationRepository.listApplications()) {
            log.finest(() -> "Verifying application package for " + applicationId);
            Session session = applicationRepository.getActiveSession(applicationId);
            if (session == null)
                continue;  // App might be deleted after call to listApplications() or not activated yet (bootstrap phase)

            FileReference appFileReference = session.getApplicationPackageReference();
            if (appFileReference != null) {
                long sessionId = session.getSessionId();
                attempts++;
                if (!fileReferenceExistsOnDisk(downloadDirectory, appFileReference)) {
                    log.fine(() -> "Downloading application package for " + applicationId + " (session " + sessionId + ")");

                    FileReferenceDownload download = new FileReferenceDownload(appFileReference,
                                                                               this.getClass().getSimpleName(),
                                                                               false);
                    if (fileDownloader.getFile(download).isEmpty()) {
                        failures++;
                        log.info("Failed downloading application package (" + appFileReference + ")" +
                                         " for " + applicationId + " (session "  +
                                         applicationRepository.getActiveSession(applicationId) + ")");
                        continue;
                    }
                }
                createLocalSessionIfMissing(applicationId, sessionId);
            }
        }
        return asSuccessFactor(attempts, failures);
    }

    private static FileDownloader createFileDownloader(ConfigserverConfig configserverConfig,
                                                       File downloadDirectory,
                                                       Supervisor supervisor) {
        List<String> otherConfigServersInCluster = getOtherConfigServersInCluster(configserverConfig);
        ConfigSourceSet configSourceSet = new ConfigSourceSet(otherConfigServersInCluster);

        ConnectionPool connectionPool = (otherConfigServersInCluster.isEmpty())
                ? FileDownloader.emptyConnectionPool()
                : new FileDistributionConnectionPool(configSourceSet, supervisor);
        return new FileDownloader(connectionPool, supervisor, downloadDirectory, Duration.ofSeconds(30));
    }

    @Override
    public void awaitShutdown() {
        supervisor.transport().shutdown().join();
        fileDownloader.close();
        super.awaitShutdown();
    }

    private void createLocalSessionIfMissing(ApplicationId applicationId, long sessionId) {
        Tenant tenant = applicationRepository.getTenant(applicationId);
        SessionRepository sessionRepository = tenant.getSessionRepository();
        if (sessionRepository.getLocalSession(sessionId) == null)
            sessionRepository.createLocalSessionFromDistributedApplicationPackage(sessionId);
    }

}
