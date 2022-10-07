// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#pragma once

#include "tensor_store.h"
#include <vespa/vespalib/datastore/datastore.h>

namespace vespalib::eval { struct Value; }

namespace search::tensor {

/**
 * Class for storing heap allocated tensors, referenced by EntryRefs.
 *
 * Shared pointers to the tensors are stored in an underlying data store.
 */
class DirectTensorStore : public TensorStore {
private:
    // Note: Must use SP (instead of UP) because of fallbackCopy() and initializeReservedElements() in BufferType,
    //       and implementation of move().
    using TensorSP = std::shared_ptr<vespalib::eval::Value>;
    using TensorStoreType = vespalib::datastore::DataStore<TensorSP>;

    class TensorBufferType : public vespalib::datastore::BufferType<TensorSP> {
    private:
        using ParentType = BufferType<TensorSP>;
        using ParentType::empty_entry;
        using CleanContext = typename ParentType::CleanContext;
    public:
        TensorBufferType();
        void cleanHold(void* buffer, size_t offset, ElemCount num_elems, CleanContext clean_ctx) override;
    };

    TensorStoreType _tensor_store;

    EntryRef add_entry(TensorSP tensor);

public:
    DirectTensorStore();
    ~DirectTensorStore() override;
    using RefType = TensorStoreType::RefType;

    const vespalib::eval::Value * get_tensor_ptr(EntryRef ref) const {
        if (!ref.valid()) {
            return nullptr;
        }
        return _tensor_store.getEntry(ref).get();
    }
    EntryRef store_tensor(std::unique_ptr<vespalib::eval::Value> tensor);

    void holdTensor(EntryRef ref) override;
    EntryRef move(EntryRef ref) override;
    vespalib::MemoryUsage update_stat(const vespalib::datastore::CompactionStrategy& compaction_strategy) override;
    std::unique_ptr<vespalib::datastore::ICompactionContext> start_compact(const vespalib::datastore::CompactionStrategy& compaction_strategy) override;
};

}
