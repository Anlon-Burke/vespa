// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#include "direct_tensor_store.h"
#include <vespa/eval/eval/value.h>
#include <vespa/vespalib/datastore/compacting_buffers.h>
#include <vespa/vespalib/datastore/compaction_context.h>
#include <vespa/vespalib/datastore/compaction_strategy.h>
#include <vespa/vespalib/datastore/datastore.hpp>
#include <vespa/vespalib/datastore/buffer_type.hpp>
#include <vespa/vespalib/util/size_literals.h>

using vespalib::datastore::CompactionContext;
using vespalib::datastore::CompactionSpec;
using vespalib::datastore::CompactionStrategy;
using vespalib::datastore::EntryRef;
using vespalib::datastore::ICompactionContext;

namespace search::tensor {

constexpr size_t MIN_BUFFER_ARRAYS = 8_Ki;

DirectTensorStore::TensorBufferType::TensorBufferType()
    : ParentType(1, MIN_BUFFER_ARRAYS, TensorStoreType::RefType::offsetSize())
{
}

void
DirectTensorStore::TensorBufferType::cleanHold(void* buffer, size_t offset, ElemCount num_elems, CleanContext clean_ctx)
{
    TensorSP* elem = static_cast<TensorSP*>(buffer) + offset;
    const auto& empty = empty_entry();
    for (size_t i = 0; i < num_elems; ++i) {
        clean_ctx.extraBytesCleaned((*elem)->get_memory_usage().allocatedBytes());
        *elem = empty;
        ++elem;
    }
}

EntryRef
DirectTensorStore::add_entry(TensorSP tensor)
{
    auto ref = _tensor_store.addEntry(tensor);
    auto& state = _tensor_store.getBufferState(RefType(ref).bufferId());
    state.stats().inc_extra_used_bytes(tensor->get_memory_usage().allocatedBytes());
    return ref;
}

DirectTensorStore::DirectTensorStore()
    : TensorStore(_tensor_store),
      _tensor_store(std::make_unique<TensorBufferType>())
{
    _tensor_store.enableFreeLists();
}

DirectTensorStore::~DirectTensorStore() = default;

EntryRef
DirectTensorStore::store_tensor(std::unique_ptr<vespalib::eval::Value> tensor)
{
    assert(tensor);
    return add_entry(TensorSP(std::move(tensor)));
}

void
DirectTensorStore::holdTensor(EntryRef ref)
{
    if (!ref.valid()) {
        return;
    }
    const auto& tensor = _tensor_store.getEntry(ref);
    assert(tensor);
    _tensor_store.holdElem(ref, 1, tensor->get_memory_usage().allocatedBytes());
}

EntryRef
DirectTensorStore::move(EntryRef ref)
{
    if (!ref.valid()) {
        return EntryRef();
    }
    const auto& old_tensor = _tensor_store.getEntry(ref);
    assert(old_tensor);
    auto new_ref = add_entry(old_tensor);
    _tensor_store.holdElem(ref, 1, old_tensor->get_memory_usage().allocatedBytes());
    return new_ref;
}

vespalib::MemoryUsage
DirectTensorStore::update_stat(const CompactionStrategy& compaction_strategy)
{
    auto memory_usage = _store.getMemoryUsage();
    _compaction_spec = CompactionSpec(compaction_strategy.should_compact_memory(memory_usage), false);
    return memory_usage;
}

std::unique_ptr<ICompactionContext>
DirectTensorStore::start_compact(const CompactionStrategy& compaction_strategy)
{
    auto compacting_buffers = _store.start_compact_worst_buffers(_compaction_spec, compaction_strategy);
    return std::make_unique<CompactionContext>(*this, std::move(compacting_buffers));
}

}
