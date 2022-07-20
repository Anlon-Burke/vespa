// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.flags;

import com.yahoo.component.Vtag;
import com.yahoo.vespa.defaults.Defaults;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;

import static com.yahoo.vespa.flags.FetchVector.Dimension.APPLICATION_ID;
import static com.yahoo.vespa.flags.FetchVector.Dimension.CONSOLE_USER_EMAIL;
import static com.yahoo.vespa.flags.FetchVector.Dimension.HOSTNAME;
import static com.yahoo.vespa.flags.FetchVector.Dimension.NODE_TYPE;
import static com.yahoo.vespa.flags.FetchVector.Dimension.TENANT_ID;
import static com.yahoo.vespa.flags.FetchVector.Dimension.VESPA_VERSION;
import static com.yahoo.vespa.flags.FetchVector.Dimension.ZONE_ID;

/**
 * Definitions of feature flags.
 *
 * <p>To use feature flags, define the flag in this class as an "unbound" flag, e.g. {@link UnboundBooleanFlag}
 * or {@link UnboundStringFlag}. At the location you want to get the value of the flag, you need the following:</p>
 *
 * <ol>
 *     <li>The unbound flag</li>
 *     <li>A {@link FlagSource}. The flag source is typically available as an injectable component. Binding
 *     an unbound flag to a flag source produces a (bound) flag, e.g. {@link BooleanFlag} and {@link StringFlag}.</li>
 *     <li>If you would like your flag value to be dependent on e.g. the application ID, then 1. you should
 *     declare this in the unbound flag definition in this file (referring to
 *     {@link FetchVector.Dimension#APPLICATION_ID}), and 2. specify the application ID when retrieving the value, e.g.
 *     {@link BooleanFlag#with(FetchVector.Dimension, String)}. See {@link FetchVector} for more info.</li>
 * </ol>
 *
 * <p>Once the code is in place, you can override the flag value. This depends on the flag source, but typically
 * there is a REST API for updating the flags in the config server, which is the root of all flag sources in the zone.</p>
 *
 * @author hakonhall
 */
public class Flags {

    private static volatile TreeMap<FlagId, FlagDefinition> flags = new TreeMap<>();

    public static final UnboundBooleanFlag MAIN_CHAIN_GRAPH = defineFeatureFlag(
            "main-chain-graph", false,
            List.of("hakonhall"), "2022-07-06", "2022-09-05",
            "Whether to run all tasks in the main task chain up to the one failing to converge (false), or " +
            "run all tasks in the main task chain whose dependencies have converged (true).  And when suspending, " +
            "whether to run the tasks in sequence (false) or in reverse sequence (true).",
            "On first tick of the main chain after (re)start of host admin.",
            ZONE_ID, NODE_TYPE, HOSTNAME);

    public static final UnboundDoubleFlag DEFAULT_TERM_WISE_LIMIT = defineDoubleFlag(
            "default-term-wise-limit", 1.0,
            List.of("baldersheim"), "2020-12-02", "2023-01-01",
            "Default limit for when to apply termwise query evaluation",
            "Takes effect at redeployment",
            ZONE_ID, APPLICATION_ID);

    public static final UnboundStringFlag FEED_SEQUENCER_TYPE = defineStringFlag(
            "feed-sequencer-type", "THROUGHPUT",
            List.of("baldersheim"), "2020-12-02", "2023-01-01",
            "Selects type of sequenced executor used for feeding in proton, valid values are LATENCY, ADAPTIVE, THROUGHPUT",
            "Takes effect at redeployment (requires restart)",
            ZONE_ID, APPLICATION_ID);

    public static final UnboundBooleanFlag KEEP_STORAGE_NODE_UP = defineFeatureFlag(
            "keep-storage-node-up", true,
            List.of("hakonhall"), "2022-07-07", "2022-08-07",
            "Whether to leave the storage node (with wanted state) UP while the node is permanently down.",
            "Takes effect immediately for nodes transitioning to permanently down.",
            ZONE_ID, APPLICATION_ID);

    public static final UnboundIntFlag MAX_UNCOMMITTED_MEMORY = defineIntFlag(
            "max-uncommitted-memory", 130000,
            List.of("geirst, baldersheim"), "2021-10-21", "2023-01-01",
            "Max amount of memory holding updates to an attribute before we do a commit.",
            "Takes effect at redeployment",
            ZONE_ID, APPLICATION_ID);

    public static final UnboundStringFlag RESPONSE_SEQUENCER_TYPE = defineStringFlag(
            "response-sequencer-type", "ADAPTIVE",
            List.of("baldersheim"), "2020-12-02", "2023-01-01",
            "Selects type of sequenced executor used for mbus responses, valid values are LATENCY, ADAPTIVE, THROUGHPUT",
            "Takes effect at redeployment",
            ZONE_ID, APPLICATION_ID);

    public static final UnboundIntFlag RESPONSE_NUM_THREADS = defineIntFlag(
            "response-num-threads", 2,
            List.of("baldersheim"), "2020-12-02", "2023-01-01",
            "Number of threads used for mbus responses, default is 2, negative number = numcores/4",
            "Takes effect at redeployment",
            ZONE_ID, APPLICATION_ID);

    public static final UnboundBooleanFlag SKIP_COMMUNICATIONMANAGER_THREAD = defineFeatureFlag(
            "skip-communicationmanager-thread", false,
            List.of("baldersheim"), "2020-12-02", "2023-01-01",
            "Should we skip the communicationmanager thread",
            "Takes effect at redeployment",
            ZONE_ID, APPLICATION_ID);

    public static final UnboundBooleanFlag SKIP_MBUS_REQUEST_THREAD = defineFeatureFlag(
            "skip-mbus-request-thread", false,
            List.of("baldersheim"), "2020-12-02", "2023-01-01",
            "Should we skip the mbus request thread",
            "Takes effect at redeployment",
            ZONE_ID, APPLICATION_ID);

    public static final UnboundBooleanFlag SKIP_MBUS_REPLY_THREAD = defineFeatureFlag(
            "skip-mbus-reply-thread", false,
            List.of("baldersheim"), "2020-12-02", "2023-01-01",
            "Should we skip the mbus reply thread",
            "Takes effect at redeployment",
            ZONE_ID, APPLICATION_ID);

    public static final UnboundBooleanFlag USE_THREE_PHASE_UPDATES = defineFeatureFlag(
            "use-three-phase-updates", false,
            List.of("vekterli"), "2020-12-02", "2022-08-01",
            "Whether to enable the use of three-phase updates when bucket replicas are out of sync.",
            "Takes effect at redeployment",
            ZONE_ID, APPLICATION_ID);

    public static final UnboundBooleanFlag USE_ASYNC_MESSAGE_HANDLING_ON_SCHEDULE = defineFeatureFlag(
            "async-message-handling-on-schedule", false,
            List.of("baldersheim"), "2020-12-02", "2023-01-01",
            "Optionally deliver async messages in own thread",
            "Takes effect at redeployment",
            ZONE_ID, APPLICATION_ID);

    public static final UnboundDoubleFlag FEED_CONCURRENCY = defineDoubleFlag(
            "feed-concurrency", 0.5,
            List.of("baldersheim"), "2020-12-02", "2023-01-01",
            "How much concurrency should be allowed for feed",
            "Takes effect at redeployment",
            ZONE_ID, APPLICATION_ID);

    public static final UnboundDoubleFlag FEED_NICENESS = defineDoubleFlag(
            "feed-niceness", 0.0,
            List.of("baldersheim"), "2022-06-24", "2023-01-01",
            "How nice feeding shall be",
            "Takes effect at redeployment",
            ZONE_ID, APPLICATION_ID);

    public static final UnboundBooleanFlag MBUS_DISPATCH_ON_ENCODE = defineFeatureFlag(
            "mbus-dispatch-on-encode", true,
            List.of("baldersheim"), "2022-07-01", "2023-01-01",
            "Should we use mbus threadpool on encode",
            "Takes effect at redeployment",
            ZONE_ID, APPLICATION_ID);

    public static final UnboundBooleanFlag MBUS_DISPATCH_ON_DECODE = defineFeatureFlag(
            "mbus-dispatch-on-decode", true,
            List.of("baldersheim"), "2022-07-01", "2023-01-01",
            "Should we use mbus threadpool on decode",
            "Takes effect at redeployment",
            ZONE_ID, APPLICATION_ID);

    public static final UnboundIntFlag MBUS_JAVA_NUM_TARGETS = defineIntFlag(
            "mbus-java-num-targets", 1,
            List.of("baldersheim"), "2022-07-05", "2023-01-01",
            "Number of rpc targets per service",
            "Takes effect at redeployment",
            ZONE_ID, APPLICATION_ID);
    public static final UnboundIntFlag MBUS_CPP_NUM_TARGETS = defineIntFlag(
            "mbus-cpp-num-targets", 1,
            List.of("baldersheim"), "2022-07-05", "2023-01-01",
            "Number of rpc targets per service",
            "Takes effect at redeployment",
            ZONE_ID, APPLICATION_ID);
    public static final UnboundIntFlag RPC_NUM_TARGETS = defineIntFlag(
            "rpc-num-targets", 1,
            List.of("baldersheim"), "2022-07-05", "2023-01-01",
            "Number of rpc targets per content node",
            "Takes effect at redeployment",
            ZONE_ID, APPLICATION_ID);
    public static final UnboundIntFlag MBUS_JAVA_EVENTS_BEFORE_WAKEUP = defineIntFlag(
            "mbus-java-events-before-wakeup", 1,
            List.of("baldersheim"), "2022-07-05", "2023-01-01",
            "Number write events before waking up transport thread",
            "Takes effect at redeployment",
            ZONE_ID, APPLICATION_ID);
    public static final UnboundIntFlag MBUS_CPP_EVENTS_BEFORE_WAKEUP = defineIntFlag(
            "mbus-cpp-events-before-wakeup", 1,
            List.of("baldersheim"), "2022-07-05", "2023-01-01",
            "Number write events before waking up transport thread",
            "Takes effect at redeployment",
            ZONE_ID, APPLICATION_ID);
    public static final UnboundIntFlag RPC_EVENTS_BEFORE_WAKEUP = defineIntFlag(
            "rpc-events-before-wakeup", 1,
            List.of("baldersheim"), "2022-07-05", "2023-01-01",
            "Number write events before waking up transport thread",
            "Takes effect at redeployment",
            ZONE_ID, APPLICATION_ID);

    public static final UnboundIntFlag MBUS_NUM_THREADS = defineIntFlag(
            "mbus-num-threads", 4,
            List.of("baldersheim"), "2022-07-01", "2023-01-01",
            "Number of threads used for mbus threadpool",
            "Takes effect at redeployment",
            ZONE_ID, APPLICATION_ID);

    public static final UnboundIntFlag MBUS_NUM_NETWORK_THREADS = defineIntFlag(
            "mbus-num-network-threads", 1,
            List.of("baldersheim"), "2022-07-01", "2023-01-01",
            "Number of threads used for mbus network",
            "Takes effect at redeployment",
            ZONE_ID, APPLICATION_ID);

    public static final UnboundBooleanFlag SHARED_STRING_REPO_NO_RECLAIM = defineFeatureFlag(
            "shared-string-repo-no-reclaim", false,
            List.of("baldersheim"), "2022-06-14", "2023-01-01",
            "Controls whether we do track usage and reclaim unused enum values in shared string repo",
            "Takes effect at redeployment",
            ZONE_ID, APPLICATION_ID);

    public static final UnboundBooleanFlag CONTAINER_DUMP_HEAP_ON_SHUTDOWN_TIMEOUT = defineFeatureFlag(
            "container-dump-heap-on-shutdown-timeout", false,
            List.of("baldersheim"), "2021-09-25", "2023-01-01",
            "Will trigger a heap dump during if container shutdown times out",
            "Takes effect at redeployment",
            ZONE_ID, APPLICATION_ID);
    public static final UnboundBooleanFlag LOAD_CODE_AS_HUGEPAGES = defineFeatureFlag(
            "load-code-as-hugepages", false,
            List.of("baldersheim"), "2022-05-13", "2023-01-01",
            "Will try to map the code segment with huge (2M) pages",
            "Takes effect at redeployment",
            ZONE_ID, APPLICATION_ID);

    public static final UnboundDoubleFlag CONTAINER_SHUTDOWN_TIMEOUT = defineDoubleFlag(
            "container-shutdown-timeout", 50.0,
            List.of("baldersheim"), "2021-09-25", "2023-05-01",
            "Timeout for shutdown of a jdisc container",
            "Takes effect at redeployment",
            ZONE_ID, APPLICATION_ID);

    public static final UnboundListFlag<String> ALLOWED_ATHENZ_PROXY_IDENTITIES = defineListFlag(
            "allowed-athenz-proxy-identities", List.of(), String.class,
            List.of("bjorncs", "tokle"), "2021-02-10", "2022-09-01",
            "Allowed Athenz proxy identities",
            "takes effect at redeployment");

    public static final UnboundIntFlag MAX_ACTIVATION_INHIBITED_OUT_OF_SYNC_GROUPS = defineIntFlag(
            "max-activation-inhibited-out-of-sync-groups", 0,
            List.of("vekterli"), "2021-02-19", "2022-08-01",
            "Allows replicas in up to N content groups to not be activated " +
            "for query visibility if they are out of sync with a majority of other replicas",
            "Takes effect at redeployment",
            ZONE_ID, APPLICATION_ID);

    public static final UnboundIntFlag MAX_CONCURRENT_MERGES_PER_NODE = defineIntFlag(
            "max-concurrent-merges-per-node", 16,
            List.of("balder", "vekterli"), "2021-06-06", "2022-08-01",
            "Specifies max concurrent merges per content node.",
            "Takes effect at redeploy",
            ZONE_ID, APPLICATION_ID);

    public static final UnboundIntFlag MAX_MERGE_QUEUE_SIZE = defineIntFlag(
            "max-merge-queue-size", 100,
            List.of("balder", "vekterli"), "2021-06-06", "2022-08-01",
            "Specifies max size of merge queue.",
            "Takes effect at redeploy",
            ZONE_ID, APPLICATION_ID);

    public static final UnboundDoubleFlag MIN_NODE_RATIO_PER_GROUP = defineDoubleFlag(
            "min-node-ratio-per-group", 0.0,
            List.of("geirst", "vekterli"), "2021-07-16", "2022-09-01",
            "Minimum ratio of nodes that have to be available (i.e. not Down) in any hierarchic content cluster group for the group to be Up",
            "Takes effect at redeployment",
            ZONE_ID, APPLICATION_ID);

    public static final UnboundIntFlag METRICSPROXY_NUM_THREADS = defineIntFlag(
            "metricsproxy-num-threads", 2,
            List.of("balder"), "2021-09-01", "2023-01-01",
            "Number of threads for metrics proxy",
            "Takes effect at redeployment",
            ZONE_ID, APPLICATION_ID);

    public static final UnboundIntFlag AVAILABLE_PROCESSORS = defineIntFlag(
            "available-processors", 2,
            List.of("balder"), "2022-01-18", "2023-01-01",
            "Number of processors the jvm sees in non-application clusters",
            "Takes effect at redeployment",
            ZONE_ID, APPLICATION_ID);

    public static final UnboundBooleanFlag ENABLED_HORIZON_DASHBOARD = defineFeatureFlag(
            "enabled-horizon-dashboard", false,
            List.of("olaa"), "2021-09-13", "2022-10-01",
            "Enable Horizon dashboard",
            "Takes effect immediately",
            TENANT_ID, CONSOLE_USER_EMAIL
    );

    public static final UnboundBooleanFlag UNORDERED_MERGE_CHAINING = defineFeatureFlag(
            "unordered-merge-chaining", true,
            List.of("vekterli", "geirst"), "2021-11-15", "2022-09-01",
            "Enables the use of unordered merge chains for data merge operations",
            "Takes effect at redeploy",
            ZONE_ID, APPLICATION_ID);

    public static final UnboundBooleanFlag IGNORE_THREAD_STACK_SIZES = defineFeatureFlag(
            "ignore-thread-stack-sizes", false,
            List.of("arnej"), "2021-11-12", "2022-12-01",
            "Whether C++ thread creation should ignore any requested stack size",
            "Triggers restart, takes effect immediately",
            ZONE_ID, APPLICATION_ID);

    public static final UnboundBooleanFlag USE_V8_GEO_POSITIONS = defineFeatureFlag(
            "use-v8-geo-positions", true,
            List.of("arnej"), "2021-11-15", "2022-12-31",
            "Use Vespa 8 types and formats for geographical positions",
            "Takes effect at redeployment",
            ZONE_ID, APPLICATION_ID);


    public static final UnboundIntFlag MAX_COMPACT_BUFFERS = defineIntFlag(
                "max-compact-buffers", 1,
                List.of("baldersheim", "geirst", "toregge"), "2021-12-15", "2023-01-01",
                "Upper limit of buffers to compact in a data store at the same time for each reason (memory usage, address space usage)",
                "Takes effect at redeployment",
            ZONE_ID, APPLICATION_ID);

    public static final UnboundBooleanFlag ENABLE_SERVER_OCSP_STAPLING = defineFeatureFlag(
            "enable-server-ocsp-stapling", false,
            List.of("bjorncs"), "2021-12-17", "2022-09-01",
            "Enable server OCSP stapling for jdisc containers",
            "Takes effect on redeployment",
            ZONE_ID, APPLICATION_ID);

    public static final UnboundBooleanFlag ENABLE_DATA_HIGHWAY_IN_AWS = defineFeatureFlag(
            "enable-data-highway-in-aws", false,
            List.of("hmusum"), "2022-01-06", "2022-09-01",
            "Enable Data Highway in AWS",
            "Takes effect on restart of Docker container",
            ZONE_ID, APPLICATION_ID);

    public static final UnboundStringFlag MERGE_THROTTLING_POLICY = defineStringFlag(
            "merge-throttling-policy", "STATIC",
            List.of("vekterli"), "2022-01-25", "2022-08-01",
            "Sets the policy used for merge throttling on the content nodes. " +
            "Valid values: STATIC, DYNAMIC",
            "Takes effect at redeployment",
            ZONE_ID, APPLICATION_ID);

    public static final UnboundDoubleFlag PERSISTENCE_THROTTLING_WS_DECREMENT_FACTOR = defineDoubleFlag(
            "persistence-throttling-ws-decrement-factor", 1.2,
            List.of("vekterli"), "2022-01-27", "2022-08-01",
            "Sets the dynamic throttle policy window size decrement factor for persistence " +
            "async throttling. Only applies if DYNAMIC policy is used.",
            "Takes effect on redeployment",
            ZONE_ID, APPLICATION_ID);

    public static final UnboundDoubleFlag PERSISTENCE_THROTTLING_WS_BACKOFF = defineDoubleFlag(
            "persistence-throttling-ws-backoff", 0.95,
            List.of("vekterli"), "2022-01-27", "2022-08-01",
            "Sets the dynamic throttle policy window size backoff for persistence " +
            "async throttling. Only applies if DYNAMIC policy is used. Valid range [0, 1]",
            "Takes effect on redeployment",
            ZONE_ID, APPLICATION_ID);

    public static final UnboundIntFlag PERSISTENCE_THROTTLING_WINDOW_SIZE = defineIntFlag(
            "persistence-throttling-window-size", -1,
            List.of("vekterli"), "2022-02-23", "2022-09-01",
            "If greater than zero, sets both min and max window size to the given number, effectively " +
            "turning dynamic throttling into a static throttling policy. " +
            "Only applies if DYNAMIC policy is used.",
            "Takes effect on redeployment",
            ZONE_ID, APPLICATION_ID);

    public static final UnboundDoubleFlag PERSISTENCE_THROTTLING_WS_RESIZE_RATE = defineDoubleFlag(
            "persistence-throttling-ws-resize-rate", 3.0,
            List.of("vekterli"), "2022-02-23", "2022-09-01",
            "Sets the dynamic throttle policy resize rate. Only applies if DYNAMIC policy is used.",
            "Takes effect on redeployment",
            ZONE_ID, APPLICATION_ID);

    public static final UnboundBooleanFlag PERSISTENCE_THROTTLING_OF_MERGE_FEED_OPS = defineFeatureFlag(
            "persistence-throttling-of-merge-feed-ops", true,
            List.of("vekterli"), "2022-02-24", "2022-09-01",
            "If true, each put/remove contained within a merge is individually throttled as if it " +
            "were a put/remove from a client. If false, merges are throttled at a persistence thread " +
            "level, i.e. per ApplyBucketDiff message, regardless of how many document operations " +
            "are contained within. Only applies if DYNAMIC policy is used.",
            "Takes effect on redeployment",
            ZONE_ID, APPLICATION_ID);

    public static final UnboundBooleanFlag USE_QRSERVER_SERVICE_NAME = defineFeatureFlag(
            "use-qrserver-service-name", false,
            List.of("arnej"), "2022-01-18", "2022-12-31",
            "Use backwards-compatible 'qrserver' service name for containers with only 'search' API",
            "Takes effect at redeployment",
            ZONE_ID, APPLICATION_ID);

    public static final UnboundBooleanFlag AVOID_RENAMING_SUMMARY_FEATURES = defineFeatureFlag(
            "avoid-renaming-summary-features", true,
            List.of("arnej"), "2022-01-15", "2023-12-31",
            "Tell backend about the original name of summary-features that were wrapped in a rankingExpression feature",
            "Takes effect at redeployment",
            ZONE_ID, APPLICATION_ID);

    public static final UnboundBooleanFlag ENABLE_BIT_VECTORS = defineFeatureFlag(
            "enable-bit-vectors", false,
            List.of("baldersheim"), "2022-05-03", "2022-12-31",
            "Enables bit vector by default for fast-search attributes",
            "Takes effect at redeployment",
            ZONE_ID, APPLICATION_ID);

    public static final UnboundStringFlag APPLICATION_FILES_WITH_UNKNOWN_EXTENSION = defineStringFlag(
            "fail-deployment-for-files-with-unknown-extension", "LOG",
            List.of("hmusum"), "2022-04-27", "2022-09-01",
            "Whether to log, fail or do nothing for deployments when app has a file with unknown extension (valid values LOG, FAIL, NOOP)",
            "Takes effect at redeployment",
            ZONE_ID, APPLICATION_ID);

    public static final UnboundBooleanFlag NOTIFICATION_DISPATCH_FLAG = defineFeatureFlag(
            "dispatch-notifications", false,
            List.of("enygaard"), "2022-05-02", "2022-09-30",
            "Whether we should send notification for a given tenant",
            "Takes effect immediately",
            TENANT_ID);

    public static final UnboundBooleanFlag ENABLE_PROXY_PROTOCOL_MIXED_MODE = defineFeatureFlag(
            "enable-proxy-protocol-mixed-mode", true,
            List.of("tokle"), "2022-05-09", "2022-09-01",
            "Enable or disable proxy protocol mixed mode",
            "Takes effect on redeployment",
            APPLICATION_ID);

    public static final UnboundListFlag<String> FILE_DISTRIBUTION_ACCEPTED_COMPRESSION_TYPES = defineListFlag(
            "file-distribution-accepted-compression-types", List.of("gzip", "lz4"), String.class,
            List.of("hmusum"), "2022-07-05", "2022-09-05",
            "´List of accepted compression types used when asking for a file reference. Valid values: gzip, lz4",
            "Takes effect on restart of service",
            APPLICATION_ID);

    public static final UnboundListFlag<String> FILE_DISTRIBUTION_COMPRESSION_TYPES_TO_SERVE = defineListFlag(
            "file-distribution-compression-types-to-use", List.of("lz4", "gzip"), String.class,
            List.of("hmusum"), "2022-07-05", "2022-09-05",
            "List of compression types to use (in preferred order), matched with accepted compression types when serving file references. Valid values: gzip, lz4",
            "Takes effect on restart of service",
            APPLICATION_ID);

    public static final UnboundBooleanFlag USE_YUM_PROXY_V2 = defineFeatureFlag(
            "use-yumproxy-v2", false,
            List.of("tokle"), "2022-05-05", "2022-09-01",
            "Use yumproxy-v2",
            "Takes effect on host admin restart",
            HOSTNAME);

    public static final UnboundStringFlag LOG_FILE_COMPRESSION_ALGORITHM = defineStringFlag(
            "log-file-compression-algorithm", "",
            List.of("arnej"), "2022-06-14", "2024-12-31",
            "Which algorithm to use for compressing log files. Valid values: empty string (default), gzip, zstd",
            "Takes effect immediately",
            ZONE_ID, APPLICATION_ID);

    public static final UnboundBooleanFlag FIX_IPV6_GATEWAY = defineFeatureFlag(
            "fix-ipv6-gateway", true,
            List.of("mpolden"), "2022-07-04", "2022-09-01",
            "Fix a misconfigured IPv6 gateway automatically",
            "Takes effect on first host admin resume",
            HOSTNAME);

    public static final UnboundBooleanFlag SEPARATE_METRIC_CHECK_CONFIG = defineFeatureFlag(
            "separate-metric-check-config", false,
            List.of("olaa"), "2022-07-04", "2022-09-01",
            "Determines whether one metrics config check should be written per Vespa node",
            "Takes effect on next tick",
            HOSTNAME);

    /** WARNING: public for testing: All flags should be defined in {@link Flags}. */
    public static UnboundBooleanFlag defineFeatureFlag(String flagId, boolean defaultValue, List<String> owners,
                                                       String createdAt, String expiresAt, String description,
                                                       String modificationEffect, FetchVector.Dimension... dimensions) {
        return define(UnboundBooleanFlag::new, flagId, defaultValue, owners, createdAt, expiresAt, description, modificationEffect, dimensions);
    }

    /** WARNING: public for testing: All flags should be defined in {@link Flags}. */
    public static UnboundStringFlag defineStringFlag(String flagId, String defaultValue, List<String> owners,
                                                     String createdAt, String expiresAt, String description,
                                                     String modificationEffect, FetchVector.Dimension... dimensions) {
        return define(UnboundStringFlag::new, flagId, defaultValue, owners, createdAt, expiresAt, description, modificationEffect, dimensions);
    }

    /** WARNING: public for testing: All flags should be defined in {@link Flags}. */
    public static UnboundIntFlag defineIntFlag(String flagId, int defaultValue, List<String> owners,
                                               String createdAt, String expiresAt, String description,
                                               String modificationEffect, FetchVector.Dimension... dimensions) {
        return define(UnboundIntFlag::new, flagId, defaultValue, owners, createdAt, expiresAt, description, modificationEffect, dimensions);
    }

    /** WARNING: public for testing: All flags should be defined in {@link Flags}. */
    public static UnboundLongFlag defineLongFlag(String flagId, long defaultValue, List<String> owners,
                                                 String createdAt, String expiresAt, String description,
                                                 String modificationEffect, FetchVector.Dimension... dimensions) {
        return define(UnboundLongFlag::new, flagId, defaultValue, owners, createdAt, expiresAt, description, modificationEffect, dimensions);
    }

    /** WARNING: public for testing: All flags should be defined in {@link Flags}. */
    public static UnboundDoubleFlag defineDoubleFlag(String flagId, double defaultValue, List<String> owners,
                                                     String createdAt, String expiresAt, String description,
                                                     String modificationEffect, FetchVector.Dimension... dimensions) {
        return define(UnboundDoubleFlag::new, flagId, defaultValue, owners, createdAt, expiresAt, description, modificationEffect, dimensions);
    }

    /** WARNING: public for testing: All flags should be defined in {@link Flags}. */
    public static <T> UnboundJacksonFlag<T> defineJacksonFlag(String flagId, T defaultValue, Class<T> jacksonClass, List<String> owners,
                                                              String createdAt, String expiresAt, String description,
                                                              String modificationEffect, FetchVector.Dimension... dimensions) {
        return define((id2, defaultValue2, vector2) -> new UnboundJacksonFlag<>(id2, defaultValue2, vector2, jacksonClass),
                flagId, defaultValue, owners, createdAt, expiresAt, description, modificationEffect, dimensions);
    }

    /** WARNING: public for testing: All flags should be defined in {@link Flags}. */
    public static <T> UnboundListFlag<T> defineListFlag(String flagId, List<T> defaultValue, Class<T> elementClass,
                                                        List<String> owners, String createdAt, String expiresAt,
                                                        String description, String modificationEffect, FetchVector.Dimension... dimensions) {
        return define((fid, dval, fvec) -> new UnboundListFlag<>(fid, dval, elementClass, fvec),
                flagId, defaultValue, owners, createdAt, expiresAt, description, modificationEffect, dimensions);
    }

    @FunctionalInterface
    private interface TypedUnboundFlagFactory<T, U extends UnboundFlag<?, ?, ?>> {
        U create(FlagId id, T defaultVale, FetchVector defaultFetchVector);
    }

    /**
     * Defines a Flag.
     *
     * @param factory            Factory for creating unbound flag of type U
     * @param flagId             The globally unique FlagId.
     * @param defaultValue       The default value if none is present after resolution.
     * @param description        Description of how the flag is used.
     * @param modificationEffect What is required for the flag to take effect? A restart of process? immediately? etc.
     * @param dimensions         What dimensions will be set in the {@link FetchVector} when fetching
     *                           the flag value in
     *                           {@link FlagSource#fetch(FlagId, FetchVector) FlagSource::fetch}.
     *                           For instance, if APPLICATION is one of the dimensions here, you should make sure
     *                           APPLICATION is set to the ApplicationId in the fetch vector when fetching the RawFlag
     *                           from the FlagSource.
     * @param <T>                The boxed type of the flag value, e.g. Boolean for flags guarding features.
     * @param <U>                The type of the unbound flag, e.g. UnboundBooleanFlag.
     * @return An unbound flag with {@link FetchVector.Dimension#HOSTNAME HOSTNAME} and
     *         {@link FetchVector.Dimension#VESPA_VERSION VESPA_VERSION} already set. The ZONE environment
     *         is typically implicit.
     */
    private static <T, U extends UnboundFlag<?, ?, ?>> U define(TypedUnboundFlagFactory<T, U> factory,
                                                                String flagId,
                                                                T defaultValue,
                                                                List<String> owners,
                                                                String createdAt,
                                                                String expiresAt,
                                                                String description,
                                                                String modificationEffect,
                                                                FetchVector.Dimension[] dimensions) {
        FlagId id = new FlagId(flagId);
        FetchVector vector = new FetchVector()
                .with(HOSTNAME, Defaults.getDefaults().vespaHostname())
                // Warning: In unit tests and outside official Vespa releases, the currentVersion is e.g. 7.0.0
                // (determined by the current major version). Consider not setting VESPA_VERSION if minor = micro = 0.
                .with(VESPA_VERSION, Vtag.currentVersion.toFullString());
        U unboundFlag = factory.create(id, defaultValue, vector);
        FlagDefinition definition = new FlagDefinition(
                unboundFlag, owners, parseDate(createdAt), parseDate(expiresAt), description, modificationEffect, dimensions);
        flags.put(id, definition);
        return unboundFlag;
    }

    private static Instant parseDate(String rawDate) {
        return DateTimeFormatter.ISO_DATE.parse(rawDate, LocalDate::from).atStartOfDay().toInstant(ZoneOffset.UTC);
    }

    public static List<FlagDefinition> getAllFlags() {
        return List.copyOf(flags.values());
    }

    public static Optional<FlagDefinition> getFlag(FlagId flagId) {
        return Optional.ofNullable(flags.get(flagId));
    }

    /**
     * Allows the statically defined flags to be controlled in a test.
     *
     * <p>Returns a Replacer instance to be used with e.g. a try-with-resources block. Within the block,
     * the flags starts out as cleared. Flags can be defined, etc. When leaving the block, the flags from
     * before the block is reinserted.
     *
     * <p>NOT thread-safe. Tests using this cannot run in parallel.
     */
    public static Replacer clearFlagsForTesting(FlagId... flagsToKeep) {
        return new Replacer(flagsToKeep);
    }

    public static class Replacer implements AutoCloseable {
        private static volatile boolean flagsCleared = false;

        private final TreeMap<FlagId, FlagDefinition> savedFlags;

        private Replacer(FlagId... flagsToKeep) {
            verifyAndSetFlagsCleared(true);
            this.savedFlags = Flags.flags;
            Flags.flags = new TreeMap<>();
            List.of(flagsToKeep).forEach(id -> Flags.flags.put(id, savedFlags.get(id)));
        }

        @Override
        public void close() {
            verifyAndSetFlagsCleared(false);
            Flags.flags = savedFlags;
        }

        /**
         * Used to implement a simple verification that Replacer is not used by multiple threads.
         * For instance two different tests running in parallel cannot both use Replacer.
         */
        private static void verifyAndSetFlagsCleared(boolean newValue) {
            if (flagsCleared == newValue) {
                throw new IllegalStateException("clearFlagsForTesting called while already cleared - running tests in parallell!?");
            }
            flagsCleared = newValue;
        }
    }
}
