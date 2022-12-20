// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
#pragma once

#include <vespa/document/test/make_bucket_space.h>
#include <vespa/storage/distributor/maintenance/maintenanceprioritygenerator.h>
#include <vespa/storage/distributor/maintenance/maintenanceoperationgenerator.h>
#include <vespa/storage/distributor/maintenance/pending_window_checker.h>
#include <vespa/storage/distributor/operationstarter.h>
#include <vespa/storage/distributor/operations/operation.h>
#include <vespa/storageframework/defaultimplementation/clock/fakeclock.h>
#include <sstream>

using document::test::makeBucketSpace;

namespace storage::distributor {

class MockMaintenancePriorityGenerator
    : public MaintenancePriorityGenerator
{
    MaintenancePriorityAndType prioritize(
            const document::Bucket&,
            NodeMaintenanceStatsTracker& stats) const override
    {
        stats.incMovingOut(1, makeBucketSpace());
        stats.incCopyingIn(2, makeBucketSpace());
        return MaintenancePriorityAndType(
                MaintenancePriority(MaintenancePriority::VERY_HIGH),
                MaintenanceOperation::MERGE_BUCKET);
    }
};


class MockOperation : public MaintenanceOperation
{
    document::Bucket _bucket;
    std::string _reason;
    bool _shouldBlock;
    bool _was_blocked;
    bool _was_throttled;
public:
    MockOperation(const document::Bucket &bucket)
        : _bucket(bucket),
          _shouldBlock(false),
          _was_blocked(false),
          _was_throttled(false)
    {}

    std::string toString() const override {
        return _bucket.toString();
    }

    void onClose(DistributorStripeMessageSender&) override {}
    const char* getName() const noexcept override { return "MockOperation"; }
    const std::string& getDetailedReason() const override {
        return _reason;
    }
    void onStart(DistributorStripeMessageSender&) override {}
    void onReceive(DistributorStripeMessageSender&, const std::shared_ptr<api::StorageReply>&) override {}
    void on_blocked() override { _was_blocked = true; }
    void on_throttled() override { _was_throttled = true; }
    bool isBlocked(const DistributorStripeOperationContext&, const OperationSequencer&) const override {
        return _shouldBlock;
    }
    void setShouldBlock(bool shouldBlock) {
        _shouldBlock = shouldBlock;
    }
    bool get_was_blocked() const noexcept { return _was_blocked; }
    bool get_was_throttled() const noexcept { return _was_throttled; }
};

class MockMaintenanceOperationGenerator
    : public MaintenanceOperationGenerator
{
public:
    MaintenanceOperation::SP generate(const document::Bucket&bucket) const override {
        return std::make_shared<MockOperation>(bucket);
    }

    std::vector<MaintenanceOperation::SP> generateAll(
            const document::Bucket &bucket,
            NodeMaintenanceStatsTracker& tracker) const override
    {
        (void) tracker;
        std::vector<MaintenanceOperation::SP> ret;
        ret.emplace_back(std::make_shared<MockOperation>(bucket));
        return ret;
    }

};

class MockOperationStarter
    : public OperationStarter
{
    std::ostringstream _started;
    std::vector<Operation::SP> _operations;
    bool _shouldStart;
public:
    MockOperationStarter()
        : _shouldStart(true)
    {}

    bool start(const std::shared_ptr<Operation>& operation, Priority priority) override
    {
        if (_shouldStart) {
            _started << operation->toString()
                     << ", pri " << static_cast<int>(priority)
                     << "\n";
            _operations.push_back(operation);
        }
        return _shouldStart;
    }

    void setShouldStartOperations(bool shouldStart) {
        _shouldStart = shouldStart;
    }

    std::vector<Operation::SP>& getOperations() {
        return _operations;
    }

    std::string toString() const {
        return _started.str();
    }
};

class MockPendingWindowChecker : public PendingWindowChecker {
    bool _allow = true;
public:
    void allow_operations(bool allow) noexcept {
        _allow = allow;
    }

    bool may_allow_operation_with_priority(OperationStarter::Priority) const noexcept override {
        return _allow;
    }
};

}
