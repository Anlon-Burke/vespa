// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#pragma once

#include <vespa/vespalib/datastore/compaction_spec.h>
#include <vespa/vespalib/datastore/datastorebase.h>
#include <vespa/vespalib/datastore/entryref.h>
#include <vespa/vespalib/datastore/i_compactable.h>
#include <vespa/vespalib/util/generationhandler.h>

namespace vespalib::datastore { struct ICompactionContext; }
namespace vespalib::eval { struct Value; }

namespace search::tensor {

/**
 * Class for storing serialized tensors in memory, used by TensorAttribute.
 *
 * Serialization format is subject to change.  Changes to serialization format
 * might also require corresponding changes to implemented optimized tensor
 * operations that use the serialized tensor as argument.
 */
class TensorStore : public vespalib::datastore::ICompactable
{
public:
    using EntryRef = vespalib::datastore::EntryRef;
    typedef vespalib::GenerationHandler::generation_t generation_t;

protected:
    vespalib::datastore::DataStoreBase& _store;
    vespalib::datastore::CompactionSpec _compaction_spec;

public:
    TensorStore(vespalib::datastore::DataStoreBase &store);

    virtual ~TensorStore();

    virtual void holdTensor(EntryRef ref) = 0;

    virtual vespalib::MemoryUsage update_stat(const vespalib::datastore::CompactionStrategy& compaction_strategy) = 0;

    virtual std::unique_ptr<vespalib::datastore::ICompactionContext> start_compact(const vespalib::datastore::CompactionStrategy& compaction_strategy) = 0;

    // Inherit doc from DataStoreBase
    void trimHoldLists(generation_t usedGen) {
        _store.trimHoldLists(usedGen);
    }

    // Inherit doc from DataStoreBase
    void transferHoldLists(generation_t generation) {
        _store.transferHoldLists(generation);
    }

    void clearHoldLists() {
        _store.clearHoldLists();
    }

    vespalib::MemoryUsage getMemoryUsage() const {
        return _store.getMemoryUsage();
    }

    vespalib::AddressSpace get_address_space_usage() const {
        return _store.getAddressSpaceUsage();
    }

    bool consider_compact() const noexcept {
        return _compaction_spec.compact() && !_store.has_held_buffers();
    }
};

}
