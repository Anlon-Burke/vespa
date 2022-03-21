// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.config.server.deploy;

import com.yahoo.cloud.config.ConfigserverConfig;
import com.yahoo.component.Version;
import com.yahoo.config.application.api.ApplicationPackage;
import com.yahoo.config.application.api.DeployLogger;
import com.yahoo.config.application.api.FileRegistry;
import com.yahoo.config.model.api.ConfigDefinitionRepo;
import com.yahoo.config.model.api.ConfigServerSpec;
import com.yahoo.config.model.api.ContainerEndpoint;
import com.yahoo.config.model.api.EndpointCertificateSecrets;
import com.yahoo.config.model.api.HostProvisioner;
import com.yahoo.config.model.api.Model;
import com.yahoo.config.model.api.ModelContext;
import com.yahoo.config.model.api.Provisioned;
import com.yahoo.config.model.api.Quota;
import com.yahoo.config.model.api.Reindexing;
import com.yahoo.config.model.api.TenantSecretStore;
import com.yahoo.config.provision.ApplicationId;
import com.yahoo.config.provision.AthenzDomain;
import com.yahoo.config.provision.ClusterSpec;
import com.yahoo.config.provision.DockerImage;
import com.yahoo.config.provision.HostName;
import com.yahoo.config.provision.TenantName;
import com.yahoo.config.provision.Zone;
import com.yahoo.container.jdisc.secretstore.SecretStore;
import com.yahoo.vespa.config.server.tenant.SecretStoreExternalIdRetriever;
import com.yahoo.vespa.flags.FetchVector;
import com.yahoo.vespa.flags.FlagSource;
import com.yahoo.vespa.flags.Flags;
import com.yahoo.vespa.flags.PermanentFlags;
import com.yahoo.vespa.flags.StringFlag;
import com.yahoo.vespa.flags.UnboundFlag;

import java.io.File;
import java.net.URI;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.function.ToIntFunction;

import static com.yahoo.vespa.config.server.ConfigServerSpec.fromConfig;
import static com.yahoo.vespa.flags.FetchVector.Dimension.CLUSTER_TYPE;

/**
 * Implementation of {@link ModelContext} for configserver.
 *
 * @author Ulf Lilleengen
 */
public class ModelContextImpl implements ModelContext {

    private final ApplicationPackage applicationPackage;
    private final Optional<Model> previousModel;
    private final Optional<ApplicationPackage> permanentApplicationPackage;
    private final DeployLogger deployLogger;
    private final ConfigDefinitionRepo configDefinitionRepo;
    private final FileRegistry fileRegistry;
    private final ExecutorService executor;
    private final HostProvisioner hostProvisioner;
    private final Provisioned provisioned;
    private final Optional<? extends Reindexing> reindexing;
    private final ModelContext.Properties properties;
    private final Optional<File> appDir;

    private final Optional<DockerImage> wantedDockerImageRepository;

    /** The version of Vespa we are building a model for */
    private final Version modelVespaVersion;

    /**
     * The Version of Vespa this model should specify that nodes should use. Note that this
     * is separate from the version of this model, as upgrades are not immediate.
     * We may build a config model of Vespa version "a" which specifies that nodes should
     * use Vespa version "b". The "a" model will then be used by nodes who have not yet
     * upgraded to version "b".
     */
    private final Version wantedNodeVespaVersion;

    public ModelContextImpl(ApplicationPackage applicationPackage,
                            Optional<Model> previousModel,
                            Optional<ApplicationPackage> permanentApplicationPackage,
                            DeployLogger deployLogger,
                            ConfigDefinitionRepo configDefinitionRepo,
                            FileRegistry fileRegistry,
                            ExecutorService executor,
                            Optional<? extends Reindexing> reindexing,
                            HostProvisioner hostProvisioner,
                            Provisioned provisioned,
                            ModelContext.Properties properties,
                            Optional<File> appDir,
                            Optional<DockerImage> wantedDockerImageRepository,
                            Version modelVespaVersion,
                            Version wantedNodeVespaVersion) {
        this.applicationPackage = applicationPackage;
        this.previousModel = previousModel;
        this.permanentApplicationPackage = permanentApplicationPackage;
        this.deployLogger = deployLogger;
        this.configDefinitionRepo = configDefinitionRepo;
        this.fileRegistry = fileRegistry;
        this.executor = executor;
        this.reindexing = reindexing;
        this.hostProvisioner = hostProvisioner;
        this.provisioned = provisioned;
        this.properties = properties;
        this.appDir = appDir;
        this.wantedDockerImageRepository = wantedDockerImageRepository;
        this.modelVespaVersion = modelVespaVersion;
        this.wantedNodeVespaVersion = wantedNodeVespaVersion;
    }

    @Override
    public ApplicationPackage applicationPackage() { return applicationPackage; }

    @Override
    public Optional<Model> previousModel() { return previousModel; }

    @Override
    public Optional<ApplicationPackage> permanentApplicationPackage() { return permanentApplicationPackage; }

    /**
     * Returns the host provisioner to use, or empty to use the default provisioner,
     * creating hosts from the application package defined hosts
     */
    @Override
    public HostProvisioner getHostProvisioner() { return hostProvisioner; }

    @Override
    public Provisioned provisioned() { return provisioned; }

    @Override
    public DeployLogger deployLogger() { return deployLogger; }

    @Override
    public ConfigDefinitionRepo configDefinitionRepo() { return configDefinitionRepo; }

    @Override
    public FileRegistry getFileRegistry() { return fileRegistry; }

    @Override
    public ExecutorService getExecutor() {
        return executor;
    }

    @Override
    public Optional<? extends Reindexing> reindexing() { return  reindexing; }

    @Override
    public ModelContext.Properties properties() { return properties; }

    @Override
    public Optional<File> appDir() { return appDir; }

    @Override
    public Optional<DockerImage> wantedDockerImageRepo() { return wantedDockerImageRepository; }

    @Override
    public Version modelVespaVersion() { return modelVespaVersion; }

    @Override
    public Version wantedNodeVespaVersion() { return wantedNodeVespaVersion; }

    public static class FeatureFlags implements ModelContext.FeatureFlags {

        private final double defaultTermwiseLimit;
        private final boolean useThreePhaseUpdates;
        private final String feedSequencer;
        private final int feedTaskLimit;
        private final int feedMasterTaskLimit;
        private final String sharedFieldWriterExecutor;
        private final String responseSequencer;
        private final int numResponseThreads;
        private final boolean skipCommunicationManagerThread;
        private final boolean skipMbusRequestThread;
        private final boolean skipMbusReplyThread;
        private final boolean useAsyncMessageHandlingOnSchedule;
        private final double feedConcurrency;
        private final List<String> allowedAthenzProxyIdentities;
        private final int maxActivationInhibitedOutOfSyncGroups;
        private final ToIntFunction<ClusterSpec.Type> jvmOmitStackTraceInFastThrow;
        private final int maxConcurrentMergesPerContentNode;
        private final int maxMergeQueueSize;
        private final double resourceLimitDisk;
        private final double resourceLimitMemory;
        private final double minNodeRatioPerGroup;
        private final int metricsproxyNumThreads;
        private final int availableProcessors;
        private final boolean containerDumpHeapOnShutdownTimeout;
        private final double containerShutdownTimeout;
        private final int maxUnCommittedMemory;
        private final boolean forwardIssuesAsErrors;
        private final boolean ignoreThreadStackSizes;
        private final boolean unorderedMergeChaining;
        private final boolean useV8GeoPositions;
        private final boolean useV8DocManagerCfg;
        private final int maxCompactBuffers;
        private final boolean failDeploymentWithInvalidJvmOptions;
        private final List<String> ignoredHttpUserAgents;
        private final boolean enableServerOcspStapling;
        private final String persistenceAsyncThrottling;
        private final String mergeThrottlingPolicy;
        private final double persistenceThrottlingWsDecrementFactor;
        private final double persistenceThrottlingWsBackoff;
        private final int persistenceThrottlingWindowSize;
        private final double persistenceThrottlingWsResizeRate;
        private final boolean persistenceThrottlingOfMergeFeedOps;
        private final boolean inhibitDefaultMergesWhenGlobalMergesPending;
        private final boolean useQrserverServiceName;
        private final boolean avoidRenamingSummaryFeatures;
        private final boolean mergeGroupingResultInSearchInvoker;
        private final boolean experimentalSdParsing;
        private final String adminClusterNodeResourcesArchitecture;

        public FeatureFlags(FlagSource source, ApplicationId appId, Version version) {
            this.defaultTermwiseLimit = flagValue(source, appId, version, Flags.DEFAULT_TERM_WISE_LIMIT);
            this.useThreePhaseUpdates = flagValue(source, appId, version, Flags.USE_THREE_PHASE_UPDATES);
            this.feedSequencer = flagValue(source, appId, version, Flags.FEED_SEQUENCER_TYPE);
            this.feedTaskLimit = flagValue(source, appId, version, Flags.FEED_TASK_LIMIT);
            this.feedMasterTaskLimit = flagValue(source, appId, version, Flags.FEED_MASTER_TASK_LIMIT);
            this.sharedFieldWriterExecutor = flagValue(source, appId, version, Flags.SHARED_FIELD_WRITER_EXECUTOR);
            this.responseSequencer = flagValue(source, appId, version, Flags.RESPONSE_SEQUENCER_TYPE);
            this.numResponseThreads = flagValue(source, appId, version, Flags.RESPONSE_NUM_THREADS);
            this.skipCommunicationManagerThread = flagValue(source, appId, version, Flags.SKIP_COMMUNICATIONMANAGER_THREAD);
            this.skipMbusRequestThread = flagValue(source, appId, version, Flags.SKIP_MBUS_REQUEST_THREAD);
            this.skipMbusReplyThread = flagValue(source, appId, version, Flags.SKIP_MBUS_REPLY_THREAD);
            this.useAsyncMessageHandlingOnSchedule = flagValue(source, appId, version, Flags.USE_ASYNC_MESSAGE_HANDLING_ON_SCHEDULE);
            this.feedConcurrency = flagValue(source, appId, version, Flags.FEED_CONCURRENCY);
            this.allowedAthenzProxyIdentities = flagValue(source, appId, version, Flags.ALLOWED_ATHENZ_PROXY_IDENTITIES);
            this.maxActivationInhibitedOutOfSyncGroups = flagValue(source, appId, version, Flags.MAX_ACTIVATION_INHIBITED_OUT_OF_SYNC_GROUPS);
            this.jvmOmitStackTraceInFastThrow = type -> flagValueAsInt(source, appId, version, type, PermanentFlags.JVM_OMIT_STACK_TRACE_IN_FAST_THROW);
            this.maxConcurrentMergesPerContentNode = flagValue(source, appId, version, Flags.MAX_CONCURRENT_MERGES_PER_NODE);
            this.maxMergeQueueSize = flagValue(source, appId, version, Flags.MAX_MERGE_QUEUE_SIZE);
            this.resourceLimitDisk = flagValue(source, appId, version, PermanentFlags.RESOURCE_LIMIT_DISK);
            this.resourceLimitMemory = flagValue(source, appId, version, PermanentFlags.RESOURCE_LIMIT_MEMORY);
            this.minNodeRatioPerGroup = flagValue(source, appId, version, Flags.MIN_NODE_RATIO_PER_GROUP);
            this.metricsproxyNumThreads = flagValue(source, appId, version, Flags.METRICSPROXY_NUM_THREADS);
            this.availableProcessors = flagValue(source, appId, version, Flags.AVAILABLE_PROCESSORS);
            this.containerDumpHeapOnShutdownTimeout = flagValue(source, appId, version, Flags.CONTAINER_DUMP_HEAP_ON_SHUTDOWN_TIMEOUT);
            this.containerShutdownTimeout = flagValue(source, appId, version, Flags.CONTAINER_SHUTDOWN_TIMEOUT);
            this.maxUnCommittedMemory = flagValue(source, appId, version, Flags.MAX_UNCOMMITTED_MEMORY);
            this.forwardIssuesAsErrors = flagValue(source, appId, version, PermanentFlags.FORWARD_ISSUES_AS_ERRORS);
            this.ignoreThreadStackSizes = flagValue(source, appId, version, Flags.IGNORE_THREAD_STACK_SIZES);
            this.unorderedMergeChaining = flagValue(source, appId, version, Flags.UNORDERED_MERGE_CHAINING);
            this.useV8GeoPositions = flagValue(source, appId, version, Flags.USE_V8_GEO_POSITIONS);
            this.useV8DocManagerCfg = flagValue(source, appId, version, Flags.USE_V8_DOC_MANAGER_CFG);
            this.maxCompactBuffers = flagValue(source, appId, version, Flags.MAX_COMPACT_BUFFERS);
            this.failDeploymentWithInvalidJvmOptions = flagValue(source, appId, version, Flags.FAIL_DEPLOYMENT_WITH_INVALID_JVM_OPTIONS);
            this.ignoredHttpUserAgents = flagValue(source, appId, version, PermanentFlags.IGNORED_HTTP_USER_AGENTS);
            this.enableServerOcspStapling = flagValue(source, appId, version, Flags.ENABLE_SERVER_OCSP_STAPLING);
            this.persistenceAsyncThrottling = flagValue(source, appId, version, Flags.PERSISTENCE_ASYNC_THROTTLING);
            this.mergeThrottlingPolicy = flagValue(source, appId, version, Flags.MERGE_THROTTLING_POLICY);
            this.persistenceThrottlingWsDecrementFactor = flagValue(source, appId, version, Flags.PERSISTENCE_THROTTLING_WS_DECREMENT_FACTOR);
            this.persistenceThrottlingWsBackoff = flagValue(source, appId, version, Flags.PERSISTENCE_THROTTLING_WS_BACKOFF);
            this.persistenceThrottlingWindowSize = flagValue(source, appId, version, Flags.PERSISTENCE_THROTTLING_WINDOW_SIZE);
            this.persistenceThrottlingWsResizeRate = flagValue(source, appId, version, Flags.PERSISTENCE_THROTTLING_WS_RESIZE_RATE);
            this.persistenceThrottlingOfMergeFeedOps = flagValue(source, appId, version, Flags.PERSISTENCE_THROTTLING_OF_MERGE_FEED_OPS);
            this.inhibitDefaultMergesWhenGlobalMergesPending = flagValue(source, appId, version, Flags.INHIBIT_DEFAULT_MERGES_WHEN_GLOBAL_MERGES_PENDING);
            this.useQrserverServiceName = flagValue(source, appId, version, Flags.USE_QRSERVER_SERVICE_NAME);
            this.avoidRenamingSummaryFeatures = flagValue(source, appId, version, Flags.AVOID_RENAMING_SUMMARY_FEATURES);
            this.mergeGroupingResultInSearchInvoker = flagValue(source, appId, version, Flags.MERGE_GROUPING_RESULT_IN_SEARCH_INVOKER);
            this.experimentalSdParsing = flagValue(source, appId, version, Flags.EXPERIMENTAL_SD_PARSING);
            this.adminClusterNodeResourcesArchitecture = flagValue(source, appId, version, PermanentFlags.ADMIN_CLUSTER_NODE_ARCHITECTURE);
        }

        @Override public double defaultTermwiseLimit() { return defaultTermwiseLimit; }
        @Override public boolean useThreePhaseUpdates() { return useThreePhaseUpdates; }
        @Override public String feedSequencerType() { return feedSequencer; }
        @Override public int feedTaskLimit() { return feedTaskLimit; }
        @Override public int feedMasterTaskLimit() { return feedMasterTaskLimit; }
        @Override public String sharedFieldWriterExecutor() { return sharedFieldWriterExecutor; }
        @Override public String responseSequencerType() { return responseSequencer; }
        @Override public int defaultNumResponseThreads() { return numResponseThreads; }
        @Override public boolean skipCommunicationManagerThread() { return skipCommunicationManagerThread; }
        @Override public boolean skipMbusRequestThread() { return skipMbusRequestThread; }
        @Override public boolean skipMbusReplyThread() { return skipMbusReplyThread; }
        @Override public boolean useAsyncMessageHandlingOnSchedule() { return useAsyncMessageHandlingOnSchedule; }
        @Override public double feedConcurrency() { return feedConcurrency; }
        @Override public List<String> allowedAthenzProxyIdentities() { return allowedAthenzProxyIdentities; }
        @Override public int maxActivationInhibitedOutOfSyncGroups() { return maxActivationInhibitedOutOfSyncGroups; }
        @Override public String jvmOmitStackTraceInFastThrowOption(ClusterSpec.Type type) {
            return translateJvmOmitStackTraceInFastThrowIntToString(jvmOmitStackTraceInFastThrow, type);
        }
        @Override public int maxConcurrentMergesPerNode() { return maxConcurrentMergesPerContentNode; }
        @Override public int maxMergeQueueSize() { return maxMergeQueueSize; }
        @Override public double resourceLimitDisk() { return resourceLimitDisk; }
        @Override public double resourceLimitMemory() { return resourceLimitMemory; }
        @Override public double minNodeRatioPerGroup() { return minNodeRatioPerGroup; }
        @Override public int defaultPoolNumThreads() { return metricsproxyNumThreads; }
        @Override public int availableProcessors() { return availableProcessors; }
        @Override public double containerShutdownTimeout() { return containerShutdownTimeout; }
        @Override public boolean containerDumpHeapOnShutdownTimeout() { return containerDumpHeapOnShutdownTimeout; }
        @Override public int maxUnCommittedMemory() { return maxUnCommittedMemory; }
        @Override public boolean forwardIssuesAsErrors() { return forwardIssuesAsErrors; }
        @Override public boolean ignoreThreadStackSizes() { return ignoreThreadStackSizes; }
        @Override public boolean unorderedMergeChaining() { return unorderedMergeChaining; }
        @Override public boolean useV8GeoPositions() { return useV8GeoPositions; }
        @Override public boolean useV8DocManagerCfg() { return useV8DocManagerCfg; }
        @Override public boolean failDeploymentWithInvalidJvmOptions() { return failDeploymentWithInvalidJvmOptions; }
        @Override public int maxCompactBuffers() { return maxCompactBuffers; }
        @Override public List<String> ignoredHttpUserAgents() { return ignoredHttpUserAgents; }
        @Override public boolean enableServerOcspStapling() { return enableServerOcspStapling; }
        @Override public String persistenceAsyncThrottling() { return persistenceAsyncThrottling; }
        @Override public String mergeThrottlingPolicy() { return mergeThrottlingPolicy; }
        @Override public double persistenceThrottlingWsDecrementFactor() { return persistenceThrottlingWsDecrementFactor; }
        @Override public double persistenceThrottlingWsBackoff() { return persistenceThrottlingWsBackoff; }
        @Override public int persistenceThrottlingWindowSize() { return persistenceThrottlingWindowSize; }
        @Override public double persistenceThrottlingWsResizeRate() { return persistenceThrottlingWsResizeRate; }
        @Override public boolean persistenceThrottlingOfMergeFeedOps() { return persistenceThrottlingOfMergeFeedOps; }
        @Override public boolean inhibitDefaultMergesWhenGlobalMergesPending() { return inhibitDefaultMergesWhenGlobalMergesPending; }
        @Override public boolean useQrserverServiceName() { return useQrserverServiceName; }
        @Override public boolean avoidRenamingSummaryFeatures() { return avoidRenamingSummaryFeatures; }
        @Override public boolean mergeGroupingResultInSearchInvoker() { return mergeGroupingResultInSearchInvoker; }
        @Override public boolean experimentalSdParsing() { return experimentalSdParsing; }
        @Override public String adminClusterNodeArchitecture() { return adminClusterNodeResourcesArchitecture; }

        private static <V> V flagValue(FlagSource source, ApplicationId appId, Version vespaVersion, UnboundFlag<? extends V, ?, ?> flag) {
            return flag.bindTo(source)
                    .with(FetchVector.Dimension.APPLICATION_ID, appId.serializedForm())
                    .with(FetchVector.Dimension.VESPA_VERSION, vespaVersion.toFullString())
                    .boxedValue();
        }

        private static <V> V flagValue(FlagSource source, TenantName tenant, Version vespaVersion, UnboundFlag<? extends V, ?, ?> flag) {
            return flag.bindTo(source)
                    .with(FetchVector.Dimension.TENANT_ID, tenant.value())
                    .with(FetchVector.Dimension.VESPA_VERSION, vespaVersion.toFullString())
                    .boxedValue();
        }

        private static <V> V flagValue(FlagSource source,
                                       ApplicationId appId,
                                       Version vespaVersion,
                                       ClusterSpec.Type clusterType,
                                       UnboundFlag<? extends V, ?, ?> flag) {
            return flag.bindTo(source)
                       .with(FetchVector.Dimension.APPLICATION_ID, appId.serializedForm())
                       .with(FetchVector.Dimension.CLUSTER_TYPE, clusterType.name())
                       .with(FetchVector.Dimension.VESPA_VERSION, vespaVersion.toFullString())
                       .boxedValue();
        }

        static int flagValueAsInt(FlagSource source,
                                  ApplicationId appId,
                                  Version version,
                                  ClusterSpec.Type clusterType,
                                  UnboundFlag<? extends Boolean, ?, ?> flag) {
            return flagValue(source, appId, version, clusterType, flag) ? 1 : 0;
        }

        private String translateJvmOmitStackTraceInFastThrowIntToString(ToIntFunction<ClusterSpec.Type> function,
                                                                        ClusterSpec.Type clusterType) {
            return function.applyAsInt(clusterType) == 1 ? "" : "-XX:-OmitStackTraceInFastThrow";
        }

    }

    public static class Properties implements ModelContext.Properties {

        private final ModelContext.FeatureFlags featureFlags;
        private final ApplicationId applicationId;
        private final boolean multitenant;
        private final List<ConfigServerSpec> configServerSpecs;
        private final HostName loadBalancerName;
        private final URI ztsUrl;
        private final String athenzDnsSuffix;
        private final boolean hostedVespa;
        private final Zone zone;
        private final Set<ContainerEndpoint> endpoints;
        private final boolean isBootstrap;
        private final boolean isFirstTimeDeployment;
        private final Optional<EndpointCertificateSecrets> endpointCertificateSecrets;
        private final Optional<AthenzDomain> athenzDomain;
        private final Quota quota;
        private final List<TenantSecretStore> tenantSecretStores;
        private final SecretStore secretStore;
        private final StringFlag jvmGCOptionsFlag;
        private final boolean allowDisableMtls;
        private final List<X509Certificate> operatorCertificates;
        private final List<String> tlsCiphersOverride;
        private final List<String> zoneDnsSuffixes;
        private final List<String> environmentVariables;

        public Properties(ApplicationId applicationId,
                          Version nodeVespaVersion,
                          ConfigserverConfig configserverConfig,
                          Zone zone,
                          Set<ContainerEndpoint> endpoints,
                          boolean isBootstrap,
                          boolean isFirstTimeDeployment,
                          FlagSource flagSource,
                          Optional<EndpointCertificateSecrets> endpointCertificateSecrets,
                          Optional<AthenzDomain> athenzDomain,
                          Optional<Quota> maybeQuota,
                          List<TenantSecretStore> tenantSecretStores,
                          SecretStore secretStore,
                          List<X509Certificate> operatorCertificates) {
            this.featureFlags = new FeatureFlags(flagSource, applicationId, nodeVespaVersion);
            this.applicationId = applicationId;
            this.multitenant = configserverConfig.multitenant() || configserverConfig.hostedVespa() || Boolean.getBoolean("multitenant");
            this.configServerSpecs = fromConfig(configserverConfig);
            this.loadBalancerName = HostName.from(configserverConfig.loadBalancerAddress());
            this.ztsUrl = configserverConfig.ztsUrl() != null ? URI.create(configserverConfig.ztsUrl()) : null;
            this.athenzDnsSuffix = configserverConfig.athenzDnsSuffix();
            this.hostedVespa = configserverConfig.hostedVespa();
            this.zone = zone;
            this.endpoints = endpoints;
            this.isBootstrap = isBootstrap;
            this.isFirstTimeDeployment = isFirstTimeDeployment;
            this.endpointCertificateSecrets = endpointCertificateSecrets;
            this.athenzDomain = athenzDomain;
            this.quota = maybeQuota.orElseGet(Quota::unlimited);
            this.tenantSecretStores = tenantSecretStores;
            this.secretStore = secretStore;
            this.jvmGCOptionsFlag = PermanentFlags.JVM_GC_OPTIONS.bindTo(flagSource)
                    .with(FetchVector.Dimension.APPLICATION_ID, applicationId.serializedForm());
            this.allowDisableMtls = PermanentFlags.ALLOW_DISABLE_MTLS.bindTo(flagSource)
                    .with(FetchVector.Dimension.APPLICATION_ID, applicationId.serializedForm()).value();
            this.operatorCertificates = operatorCertificates;
            this.tlsCiphersOverride = PermanentFlags.TLS_CIPHERS_OVERRIDE.bindTo(flagSource)
                    .with(FetchVector.Dimension.APPLICATION_ID, applicationId.serializedForm()).value();
            this.zoneDnsSuffixes = configserverConfig.zoneDnsSuffixes();
            this.environmentVariables = PermanentFlags.ENVIRONMENT_VARIABLES.bindTo(flagSource)
                    .with(FetchVector.Dimension.APPLICATION_ID, applicationId.serializedForm()).value();
        }

        @Override public ModelContext.FeatureFlags featureFlags() { return featureFlags; }

        @Override
        public boolean multitenant() { return multitenant; }

        @Override
        public ApplicationId applicationId() { return applicationId; }

        @Override
        public List<ConfigServerSpec> configServerSpecs() { return configServerSpecs; }

        @Override
        public HostName loadBalancerName() { return loadBalancerName; }

        @Override
        public URI ztsUrl() {
            return ztsUrl;
        }

        @Override
        public String athenzDnsSuffix() {
            return athenzDnsSuffix;
        }

        @Override
        public boolean hostedVespa() { return hostedVespa; }

        @Override
        public Zone zone() { return zone; }

        @Override
        public Set<ContainerEndpoint> endpoints() { return endpoints; }

        @Override
        public boolean isBootstrap() { return isBootstrap; }

        @Override
        public boolean isFirstTimeDeployment() { return isFirstTimeDeployment; }

        @Override
        public Optional<EndpointCertificateSecrets> endpointCertificateSecrets() { return endpointCertificateSecrets; }

        @Override
        public Optional<AthenzDomain> athenzDomain() { return athenzDomain; }

        @Override public Quota quota() { return quota; }

        @Override
        public List<TenantSecretStore> tenantSecretStores() {
            return SecretStoreExternalIdRetriever.populateExternalId(secretStore, applicationId.tenant(), zone.system(), tenantSecretStores);
        }

        @Override public String jvmGCOptions(Optional<ClusterSpec.Type> clusterType) {
            return flagValueForClusterType(jvmGCOptionsFlag, clusterType);
        }

        @Override
        public boolean allowDisableMtls() {
            return allowDisableMtls;
        }

        @Override
        public List<X509Certificate> operatorCertificates() {
            return operatorCertificates;
        }

        @Override public List<String> tlsCiphersOverride() { return tlsCiphersOverride; }

        @Override
        public List<String> zoneDnsSuffixes() {
            return zoneDnsSuffixes;
        }

        public String flagValueForClusterType(StringFlag flag, Optional<ClusterSpec.Type> clusterType) {
            return clusterType.map(type -> flag.with(CLUSTER_TYPE, type.name()))
                              .orElse(flag)
                              .value();
        }

        @Override
        public List<String> environmentVariables() { return environmentVariables; }

    }

}
