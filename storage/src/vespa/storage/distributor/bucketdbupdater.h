// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
#pragma once

#include "bucketlistmerger.h"
#include "distributor_stripe_component.h"
#include "distributormessagesender.h"
#include "messageguard.h"
#include "operation_routing_snapshot.h"
#include "outdated_nodes_map.h"
#include "pendingclusterstate.h"
#include <vespa/document/bucket/bucket.h>
#include <vespa/storage/common/storagelink.h>
#include <vespa/storageapi/message/bucket.h>
#include <vespa/storageapi/messageapi/messagehandler.h>
#include <vespa/storageframework/generic/clock/timer.h>
#include <vespa/storageframework/generic/status/statusreporter.h>
#include <vespa/vdslib/state/clusterstate.h>
#include <atomic>
#include <list>
#include <mutex>

namespace vespalib::xml {
class XmlOutputStream;
class XmlAttribute;
}

namespace storage::distributor {

class BucketSpaceDistributionConfigs;
class BucketSpaceDistributionContext;
class DistributorStripeInterface;
class StripeAccessor;
class StripeAccessGuard;

class BucketDBUpdater : public framework::StatusReporter,
                        public api::MessageHandler
{
public:
    using OutdatedNodesMap = dbtransition::OutdatedNodesMap;
    BucketDBUpdater(DistributorStripeInterface& owner,
                    DistributorMessageSender& sender,
                    DistributorComponentRegister& comp_reg,
                    StripeAccessor& stripe_accessor);
    ~BucketDBUpdater() override;

    void flush();

    bool onSetSystemState(const std::shared_ptr<api::SetSystemStateCommand>& cmd) override;
    bool onActivateClusterStateVersion(const std::shared_ptr<api::ActivateClusterStateVersionCommand>& cmd) override;
    bool onRequestBucketInfoReply(const std::shared_ptr<api::RequestBucketInfoReply> & repl) override;
    bool onMergeBucketReply(const std::shared_ptr<api::MergeBucketReply>& reply) override;

    vespalib::string getReportContentType(const framework::HttpUrlPath&) const override;
    bool reportStatus(std::ostream&, const framework::HttpUrlPath&) const override;

    void resend_delayed_messages();
    void storage_distribution_changed(const BucketSpaceDistributionConfigs& configs);
    void bootstrap_distribution_config(std::shared_ptr<const lib::Distribution>);

    vespalib::string report_xml_status(vespalib::xml::XmlOutputStream& xos, const framework::HttpUrlPath&) const;

    void print(std::ostream& out, bool verbose, const std::string& indent) const;
    const DistributorNodeContext& node_context() const { return _node_ctx; }
    DistributorOperationContext& operation_context() { return _op_ctx; }

    void set_stale_reads_enabled(bool enabled) noexcept {
        _stale_reads_enabled.store(enabled, std::memory_order_relaxed);
    }
    bool stale_reads_enabled() const noexcept {
        return _stale_reads_enabled.load(std::memory_order_relaxed);
    }

private:
    class MergeReplyGuard {
    public:
        MergeReplyGuard(DistributorStripeInterface& distributor_interface, const std::shared_ptr<api::MergeBucketReply>& reply) noexcept
            : _distributor_interface(distributor_interface), _reply(reply) {}

        ~MergeReplyGuard();

        // Used when we're flushing and simply want to drop the reply rather
        // than send it down
        void resetReply() { _reply.reset(); }
    private:
        DistributorStripeInterface& _distributor_interface;
        std::shared_ptr<api::MergeBucketReply> _reply;
    };

    struct BucketRequest {
        BucketRequest()
            : targetNode(0), bucket(), timestamp(0) {};

        BucketRequest(uint16_t t, uint64_t currentTime, const document::Bucket& b,
                      const std::shared_ptr<MergeReplyGuard>& guard)
            : targetNode(t),
              bucket(b),
              timestamp(currentTime),
              _mergeReplyGuard(guard) {};

        void print_xml_tag(vespalib::xml::XmlOutputStream &xos, const vespalib::xml::XmlAttribute &timestampAttribute) const;
        uint16_t targetNode;
        document::Bucket bucket;
        uint64_t timestamp;

        std::shared_ptr<MergeReplyGuard> _mergeReplyGuard;
    };

    struct EnqueuedBucketRecheck {
        uint16_t node;
        document::Bucket bucket;

        EnqueuedBucketRecheck() : node(0), bucket() {}

        EnqueuedBucketRecheck(uint16_t _node, const document::Bucket& _bucket)
          : node(_node),
            bucket(_bucket)
        {}

        bool operator<(const EnqueuedBucketRecheck& o) const {
            if (node != o.node) {
                return node < o.node;
            }
            return bucket < o.bucket;
        }
        bool operator==(const EnqueuedBucketRecheck& o) const {
            return node == o.node && bucket == o.bucket;
        }
    };

    friend class DistributorTestUtil;
    // Only to be used by tests that want to ensure both the BucketDBUpdater _and_ the Distributor
    // components agree on the currently active cluster state bundle.
    // Transitively invokes Distributor::enableClusterStateBundle
    void simulate_cluster_state_bundle_activation(const lib::ClusterStateBundle& activated_state);

    bool should_defer_state_enabling() const noexcept;
    bool has_pending_cluster_state() const;
    bool pending_cluster_state_accepted(const std::shared_ptr<api::RequestBucketInfoReply>& repl);
    bool process_single_bucket_info_reply(const std::shared_ptr<api::RequestBucketInfoReply>& repl);
    void handle_single_bucket_info_failure(const std::shared_ptr<api::RequestBucketInfoReply>& repl,
                                           const BucketRequest& req);
    bool is_pending_cluster_state_completed() const;
    void process_completed_pending_cluster_state(StripeAccessGuard& guard);
    void activate_pending_cluster_state(StripeAccessGuard& guard);
    void merge_bucket_info_with_database(const std::shared_ptr<api::RequestBucketInfoReply>& repl,
                                         const BucketRequest& req);
    void convert_bucket_info_to_bucket_list(const std::shared_ptr<api::RequestBucketInfoReply>& repl,
                                            uint16_t targetNode, BucketListMerger::BucketList& newList);
    void send_request_bucket_info(uint16_t node, const document::Bucket& bucket,
                                  const std::shared_ptr<MergeReplyGuard>& mergeReplyGuard);
    void add_bucket_info_for_node(const BucketDatabase::Entry& e, uint16_t node,
                                  BucketListMerger::BucketList& existing) const;
    void ensure_transition_timer_started();
    void complete_transition_timer();
    /**
     * Adds all buckets contained in the bucket database
     * that are either contained
     * in bucketId, or that bucketId is contained in, that have copies
     * on the given node.
     */
    void find_related_buckets_in_database(uint16_t node, const document::Bucket& bucket,
                                          BucketListMerger::BucketList& existing);

    /**
       Updates the bucket database from the information generated by the given
       bucket list merger.
    */
    void update_database(document::BucketSpace bucketSpace, uint16_t node, BucketListMerger& merger);

    void remove_superfluous_buckets(StripeAccessGuard& guard,
                                    const lib::ClusterStateBundle& new_state,
                                    bool is_distribution_config_change);

    void reply_to_previous_pending_cluster_state_if_any();
    void reply_to_activation_with_actual_version(
            const api::ActivateClusterStateVersionCommand& cmd,
            uint32_t actualVersion);

    void enable_current_cluster_state_bundle_in_distributor_and_stripes(StripeAccessGuard& guard);
    void add_current_state_to_cluster_state_history();
    void send_all_queued_bucket_rechecks();

    void propagate_active_state_bundle_internally();

    void maybe_inject_simulated_db_pruning_delay();
    void maybe_inject_simulated_db_merging_delay();

    // TODO STRIPE remove once distributor component dependencies have been pruned
    StripeAccessor& _stripe_accessor;
    lib::ClusterStateBundle _active_state_bundle;
    std::unique_ptr<DistributorBucketSpaceRepo> _dummy_mutable_bucket_space_repo;
    std::unique_ptr<DistributorBucketSpaceRepo> _dummy_read_only_bucket_space_repo;

    DistributorStripeComponent _distributor_component;
    const DistributorNodeContext& _node_ctx;
    DistributorOperationContext& _op_ctx;
    DistributorStripeInterface& _distributor_interface;
    std::deque<std::pair<framework::MilliSecTime, BucketRequest>> _delayed_requests;
    std::map<uint64_t, BucketRequest> _sent_messages;
    std::unique_ptr<PendingClusterState> _pending_cluster_state;
    std::list<PendingClusterState::Summary> _history;
    DistributorMessageSender& _sender;
    std::set<EnqueuedBucketRecheck> _enqueued_rechecks;
    OutdatedNodesMap         _outdated_nodes_map;
    framework::MilliSecTimer _transition_timer;
    std::atomic<bool> _stale_reads_enabled;
};

}
