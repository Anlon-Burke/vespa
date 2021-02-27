// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#include "documentbucketmover.h"
#include "i_move_operation_limiter.h"
#include "idocumentmovehandler.h"
#include "maintenancedocumentsubdb.h"
#include <vespa/searchcore/proton/documentmetastore/i_document_meta_store.h>
#include <vespa/searchcore/proton/feedoperation/moveoperation.h>
#include <vespa/searchcore/proton/persistenceengine/i_document_retriever.h>
#include <vespa/searchcore/proton/bucketdb/bucket_db_owner.h>
#include <vespa/document/fieldvalue/document.h>
#include <vespa/vespalib/util/destructor_callbacks.h>

using document::BucketId;
using document::Document;
using document::GlobalId;
using storage::spi::Timestamp;

namespace proton::bucketdb {

typedef IDocumentMetaStore::Iterator Iterator;

BucketMover::GuardedMoveOp
BucketMover::createMoveOperation(MoveKey &key) {
    if (_source->lidNeedsCommit(key._lid)) return {};

    const RawDocumentMetaData &metaNow = _source->meta_store()->getRawMetaData(key._lid);
    if (metaNow.getGid() != key._gid) return {};
    if (metaNow.getTimestamp() != key._timestamp) return {};

    Document::SP doc(_source->retriever()->getFullDocument(key._lid));
    if (!doc || doc->getId().getGlobalId() != key._gid)
        return {}; // Failed to retrieve document, removed or changed identity
    BucketId bucketId = _bucket.stripUnused();
    return BucketMover::GuardedMoveOp(std::make_unique<MoveOperation>(bucketId, key._timestamp, std::move(doc),
                                                                      DbDocumentId(_source->sub_db_id(), key._lid),
                                                                      _targetSubDbId),
                                      std::move(key._guard));
}

void
BucketMover::moveDocument(MoveOperationUP moveOp, IDestructorCallbackSP onDone) {
    _handler->handleMove(*moveOp, std::move(onDone));
}

BucketMover::MoveKey::MoveKey(uint32_t lid, const document::GlobalId &gid, Timestamp timestamp, MoveGuard guard) noexcept
    : _lid(lid),
      _gid(gid),
      _timestamp(timestamp),
      _guard(std::move(guard))
{ }

BucketMover::MoveKey::~MoveKey() = default;

BucketMover::BucketMover(const BucketId &bucket, const MaintenanceDocumentSubDB *source,
                         uint32_t targetSubDbId, IDocumentMoveHandler &handler) noexcept
    : _source(source),
      _handler(&handler),
      _bucket(bucket),
      _targetSubDbId(targetSubDbId),
      _started(0),
      _completed(0),
      _bucketDone(false),
      _lastGidValid(false),
      _lastGid()
{ }

BucketMover::~BucketMover() {
    assert(inSync());
}

std::pair<std::vector<BucketMover::MoveKey>, bool>
BucketMover::getKeysToMove(size_t maxDocsToMove) {
    std::pair<std::vector<BucketMover::MoveKey>, bool> result;
    Iterator itr = (_lastGidValid ? _source->meta_store()->upperBound(_lastGid)
                                  : _source->meta_store()->lowerBound(_bucket));
    const Iterator end = _source->meta_store()->upperBound(_bucket);
    std::vector<MoveKey> toMove;
    for (size_t docsMoved(0); itr != end && docsMoved < maxDocsToMove; ++itr) {
        uint32_t lid = itr.getKey().get_lid();
        const RawDocumentMetaData &metaData = _source->meta_store()->getRawMetaData(lid);
        if (metaData.getBucketUsedBits() == _bucket.getUsedBits()) {
            result.first.emplace_back(lid, metaData.getGid(), metaData.getTimestamp(), MoveGuard(*this));
            ++docsMoved;
        }
    }
    result.second = (itr == end);
    return result;
}

std::vector<BucketMover::GuardedMoveOp>
BucketMover::createMoveOperations(std::vector<MoveKey> toMove) {
    std::vector<GuardedMoveOp> successfulReads;
    successfulReads.reserve(toMove.size());
    for (MoveKey &key : toMove) {
        auto moveOp = createMoveOperation(key);
        if (!moveOp.first) {
            break;
        }
        successfulReads.push_back(std::move(moveOp));
    }
    return successfulReads;
}

void
BucketMover::moveDocuments(std::vector<GuardedMoveOp> moveOps, IDestructorCallbackSP onDone) {
    for (auto & moveOp : moveOps) {
        moveDocument(std::move(moveOp.first), std::move(onDone));
    }
}

}

namespace proton {

using bucketdb::BucketMover;
using bucketdb::BucketDBOwner;

DocumentBucketMover::DocumentBucketMover(IMoveOperationLimiter &limiter, BucketDBOwner &bucketDb) noexcept
    : _limiter(limiter),
      _bucketDb(&bucketDb),
      _impl()
{}

void
DocumentBucketMover::setupForBucket(const document::BucketId &bucket, const MaintenanceDocumentSubDB *source,
                                    uint32_t targetSubDbId, IDocumentMoveHandler &handler)
{
    _impl = std::make_unique<BucketMover>(bucket, source, targetSubDbId, handler);
}

bool
DocumentBucketMover::moveDocuments(size_t maxDocsToMove) {
    return !_impl || moveDocuments(maxDocsToMove, _limiter);
}

bool
DocumentBucketMover::moveDocuments(size_t maxDocsToMove, IMoveOperationLimiter &limiter)
{
    if (_impl->bucketDone()) {
        return true;
    }
    auto [keys, done] = _impl->getKeysToMove(maxDocsToMove);
    size_t numKeys = keys.size();
    auto moveOps = _impl->createMoveOperations(std::move(keys));
    bool allOk = (numKeys == moveOps.size());
    if (done && allOk) {
        _impl->setBucketDone();
    }
    if (moveOps.empty()) return allOk;

    _impl->updateLastValidGid(moveOps.back().first->getDocument()->getId().getGlobalId());

    for (auto & moveOp : moveOps) {
        // We cache the bucket for the document we are going to move to avoid getting
        // inconsistent bucket info (getBucketInfo()) while moving between ready and not-ready
        // sub dbs as the bucket info is not updated atomically in this case.
        _bucketDb->takeGuard()->cacheBucket(moveOp.first->getBucketId());
        _impl->moveDocument(std::move(moveOp.first), limiter.beginOperation());
        _bucketDb->takeGuard()->uncacheBucket();
    }
    return allOk;
}

}
