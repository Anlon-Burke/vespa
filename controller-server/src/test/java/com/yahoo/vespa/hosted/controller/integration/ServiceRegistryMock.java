// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.controller.integration;

import com.google.inject.Inject;
import com.yahoo.cloud.config.ConfigserverConfig;
import com.yahoo.component.AbstractComponent;
import com.yahoo.config.provision.SystemName;
import com.yahoo.test.ManualClock;
import com.yahoo.vespa.hosted.controller.api.integration.ServiceRegistry;
import com.yahoo.vespa.hosted.controller.api.integration.archive.ArchiveService;
import com.yahoo.vespa.hosted.controller.api.integration.archive.MockArchiveService;
import com.yahoo.vespa.hosted.controller.api.integration.athenz.AccessControlService;
import com.yahoo.vespa.hosted.controller.api.integration.athenz.MockAccessControlService;
import com.yahoo.vespa.hosted.controller.api.integration.aws.MockCloudEventFetcher;
import com.yahoo.vespa.hosted.controller.api.integration.aws.MockResourceTagger;
import com.yahoo.vespa.hosted.controller.api.integration.aws.MockRoleService;
import com.yahoo.vespa.hosted.controller.api.integration.aws.ResourceTagger;
import com.yahoo.vespa.hosted.controller.api.integration.aws.RoleService;
import com.yahoo.vespa.hosted.controller.api.integration.billing.BillingController;
import com.yahoo.vespa.hosted.controller.api.integration.billing.BillingDatabaseClient;
import com.yahoo.vespa.hosted.controller.api.integration.billing.BillingDatabaseClientMock;
import com.yahoo.vespa.hosted.controller.api.integration.billing.MockBillingController;
import com.yahoo.vespa.hosted.controller.api.integration.billing.PlanRegistry;
import com.yahoo.vespa.hosted.controller.api.integration.billing.PlanRegistryMock;
import com.yahoo.vespa.hosted.controller.api.integration.certificates.EndpointCertificateMock;
import com.yahoo.vespa.hosted.controller.api.integration.certificates.EndpointCertificateValidator;
import com.yahoo.vespa.hosted.controller.api.integration.certificates.EndpointCertificateValidatorMock;
import com.yahoo.vespa.hosted.controller.api.integration.dns.MemoryNameService;
import com.yahoo.vespa.hosted.controller.api.integration.entity.MemoryEntityService;
import com.yahoo.vespa.hosted.controller.api.integration.horizon.HorizonClient;
import com.yahoo.vespa.hosted.controller.api.integration.horizon.MockHorizonClient;
import com.yahoo.vespa.hosted.controller.api.integration.organization.MockContactRetriever;
import com.yahoo.vespa.hosted.controller.api.integration.organization.MockIssueHandler;
import com.yahoo.vespa.hosted.controller.api.integration.resource.CostReportConsumerMock;
import com.yahoo.vespa.hosted.controller.api.integration.resource.ResourceDatabaseClient;
import com.yahoo.vespa.hosted.controller.api.integration.resource.ResourceDatabaseClientMock;
import com.yahoo.vespa.hosted.controller.api.integration.secrets.NoopTenantSecretService;
import com.yahoo.vespa.hosted.controller.api.integration.stubs.DummyOwnershipIssues;
import com.yahoo.vespa.hosted.controller.api.integration.stubs.DummySystemMonitor;
import com.yahoo.vespa.hosted.controller.api.integration.stubs.LoggingDeploymentIssues;
import com.yahoo.vespa.hosted.controller.api.integration.stubs.MockMailer;
import com.yahoo.vespa.hosted.controller.api.integration.stubs.MockMeteringClient;
import com.yahoo.vespa.hosted.controller.api.integration.stubs.MockRunDataStore;
import com.yahoo.vespa.hosted.controller.api.integration.stubs.MockTesterCloud;
import com.yahoo.vespa.hosted.controller.api.integration.user.RoleMaintainer;
import com.yahoo.vespa.hosted.controller.api.integration.user.RoleMaintainerMock;
import com.yahoo.vespa.hosted.controller.api.integration.vcmr.MockChangeRequestClient;

/**
 * A mock implementation of a {@link ServiceRegistry} for testing purposes.
 *
 * @author mpolden
 */
public class ServiceRegistryMock extends AbstractComponent implements ServiceRegistry {

    private final ManualClock clock = new ManualClock();
    private final ZoneRegistryMock zoneRegistryMock;
    private final ConfigServerMock configServerMock;
    private final MemoryNameService memoryNameService = new MemoryNameService();
    private final MockMailer mockMailer = new MockMailer();
    private final EndpointCertificateMock endpointCertificateMock = new EndpointCertificateMock();
    private final EndpointCertificateValidatorMock endpointCertificateValidatorMock = new EndpointCertificateValidatorMock();
    private final MockMeteringClient mockMeteringClient = new MockMeteringClient();
    private final MockContactRetriever mockContactRetriever = new MockContactRetriever();
    private final MockIssueHandler mockIssueHandler = new MockIssueHandler();
    private final DummyOwnershipIssues dummyOwnershipIssues = new DummyOwnershipIssues();
    private final LoggingDeploymentIssues loggingDeploymentIssues = new LoggingDeploymentIssues();
    private final MemoryEntityService memoryEntityService = new MemoryEntityService();
    private final DummySystemMonitor systemMonitor = new DummySystemMonitor();
    private final CostReportConsumerMock costReportConsumerMock = new CostReportConsumerMock();
    private final MockCloudEventFetcher mockAwsEventFetcher = new MockCloudEventFetcher();
    private final ArtifactRepositoryMock artifactRepositoryMock = new ArtifactRepositoryMock();
    private final MockTesterCloud mockTesterCloud;
    private final ApplicationStoreMock applicationStoreMock = new ApplicationStoreMock();
    private final MockRunDataStore mockRunDataStore = new MockRunDataStore();
    private final MockResourceTagger mockResourceTagger = new MockResourceTagger();
    private final RoleService roleService = new MockRoleService();
    private final BillingController billingController = new MockBillingController(clock);
    private final ContainerRegistryMock containerRegistry = new ContainerRegistryMock();
    private final NoopTenantSecretService tenantSecretService = new NoopTenantSecretService();
    private final ArchiveService archiveService = new MockArchiveService();
    private final MockChangeRequestClient changeRequestClient = new MockChangeRequestClient();
    private final AccessControlService accessControlService = new MockAccessControlService();
    private final HorizonClient horizonClient = new MockHorizonClient();
    private final PlanRegistry planRegistry = new PlanRegistryMock();
    private final ResourceDatabaseClient resourceDb = new ResourceDatabaseClientMock(planRegistry);
    private final BillingDatabaseClient billingDb = new BillingDatabaseClientMock(clock, planRegistry);
    private final RoleMaintainerMock roleMaintainer = new RoleMaintainerMock();

    public ServiceRegistryMock(SystemName system) {
        this.zoneRegistryMock = new ZoneRegistryMock(system);
        this.configServerMock = new ConfigServerMock(zoneRegistryMock);
        this.mockTesterCloud = new MockTesterCloud(nameService());
    }

    @Inject
    public ServiceRegistryMock(ConfigserverConfig config) {
        this(SystemName.from(config.system()));
    }

    public ServiceRegistryMock() {
        this(SystemName.main);
    }

    @Override
    public ConfigServerMock configServer() {
        return configServerMock;
    }

    @Override
    public ManualClock clock() {
        return clock;
    }

    @Override
    public MockMailer mailer() {
        return mockMailer;
    }

    @Override
    public EndpointCertificateMock endpointCertificateProvider() {
        return endpointCertificateMock;
    }

    @Override
    public EndpointCertificateValidator endpointCertificateValidator() {
        return endpointCertificateValidatorMock;
    }

    @Override
    public MockMeteringClient meteringService() {
        return mockMeteringClient;
    }

    @Override
    public MockContactRetriever contactRetriever() {
        return mockContactRetriever;
    }

    @Override
    public MockIssueHandler issueHandler() {
        return mockIssueHandler;
    }

    @Override
    public DummyOwnershipIssues ownershipIssues() {
        return dummyOwnershipIssues;
    }

    @Override
    public LoggingDeploymentIssues deploymentIssues() {
        return loggingDeploymentIssues;
    }

    @Override
    public MemoryEntityService entityService() {
        return memoryEntityService;
    }

    @Override
    public CostReportConsumerMock costReportConsumer() {
        return costReportConsumerMock;
    }

    @Override
    public MockCloudEventFetcher eventFetcherService() {
        return mockAwsEventFetcher;
    }

    @Override
    public ArtifactRepositoryMock artifactRepository() {
        return artifactRepositoryMock;
    }

    @Override
    public MockTesterCloud testerCloud() {
        return mockTesterCloud;
    }

    @Override
    public ApplicationStoreMock applicationStore() {
        return applicationStoreMock;
    }

    @Override
    public MockRunDataStore runDataStore() {
        return mockRunDataStore;
    }

    @Override
    public MemoryNameService nameService() {
        return memoryNameService;
    }

    @Override
    public ZoneRegistryMock zoneRegistry() {
        return zoneRegistryMock;
    }

    @Override
    public ResourceTagger resourceTagger() {
        return mockResourceTagger;
    }

    @Override
    public RoleService roleService() {
        return roleService;
    }

    @Override
    public DummySystemMonitor systemMonitor() {
        return systemMonitor;
    }

    @Override
    public BillingController billingController() {
        return billingController;
    }

    @Override
    public ContainerRegistryMock containerRegistry() {
        return containerRegistry;
    }

    @Override
    public NoopTenantSecretService tenantSecretService() {
        return tenantSecretService;
    }

    @Override
    public ArchiveService archiveService() {
        return archiveService;
    }

    @Override
    public MockChangeRequestClient changeRequestClient() {
        return changeRequestClient;
    }

    @Override
    public AccessControlService accessControlService() {
        return accessControlService;
    }

    @Override
    public HorizonClient horizonClient() {
        return horizonClient;
    }

    @Override
    public PlanRegistry planRegistry() {
        return planRegistry;
    }

    @Override
    public ResourceDatabaseClient resourceDatabase() {
        return resourceDb;
    }

    @Override
    public BillingDatabaseClient billingDatabase() {
        return billingDb;
    }

    @Override
    public RoleMaintainer roleMaintainer() {
        return roleMaintainer;
    }

    public ConfigServerMock configServerMock() {
        return configServerMock;
    }

    public MockContactRetriever contactRetrieverMock() {
        return mockContactRetriever;
    }

    public EndpointCertificateMock endpointCertificateMock() {
        return endpointCertificateMock;
    }

    public RoleMaintainerMock roleMaintainerMock() {
        return roleMaintainer;
    }
}
