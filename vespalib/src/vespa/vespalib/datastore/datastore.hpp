// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#pragma once

#include "datastore.h"
#include "allocator.hpp"
#include "free_list_allocator.hpp"
#include "free_list_raw_allocator.hpp"
#include "raw_allocator.hpp"
#include <vespa/vespalib/util/array.hpp>

namespace vespalib::datastore {

template <typename RefT>
DataStoreT<RefT>::DataStoreT()
    : DataStoreBase(RefType::numBuffers(), RefType::offset_bits, RefType::offsetSize())
{
}

template <typename RefT>
DataStoreT<RefT>::~DataStoreT() = default;

template <typename RefT>
void
DataStoreT<RefT>::free_elem_internal(EntryRef ref, size_t numElems, bool was_held)
{
    RefType intRef(ref);
    BufferState &state = getBufferState(intRef.bufferId());
    state.free_elems(ref, numElems, was_held, intRef.offset());
}

template <typename RefT>
void
DataStoreT<RefT>::holdElem(EntryRef ref, size_t numElems, size_t extraBytes)
{
    RefType intRef(ref);
    BufferState &state = getBufferState(intRef.bufferId());
    if (!state.hold_elems(numElems, extraBytes)) {
        _elemHold1List.push_back(ElemHold1ListElem(ref, numElems));
    }
}

template <typename RefT>
void
DataStoreT<RefT>::trimElemHoldList(generation_t usedGen)
{
    auto it = _elemHold2List.begin();
    auto ite = _elemHold2List.end();
    uint32_t freed = 0;
    for (; it != ite; ++it) {
        if (static_cast<sgeneration_t>(it->_generation - usedGen) >= 0) {
            break;
        }
        free_elem_internal(it->_ref, it->_len, true);
        ++freed;
    }
    if (freed != 0) {
        _elemHold2List.erase(_elemHold2List.begin(), it);
    }
}

template <typename RefT>
void
DataStoreT<RefT>::clearElemHoldList()
{
    auto it = _elemHold2List.begin();
    auto ite = _elemHold2List.end();
    for (; it != ite; ++it) {
        free_elem_internal(it->_ref, it->_len, true);
    }
    _elemHold2List.clear();
}

template <typename RefT>
template <typename EntryT>
Allocator<EntryT, RefT>
DataStoreT<RefT>::allocator(uint32_t typeId)
{
    return Allocator<EntryT, RefT>(*this, typeId);
}

template <typename RefT>
template <typename EntryT, typename ReclaimerT>
FreeListAllocator<EntryT, RefT, ReclaimerT>
DataStoreT<RefT>::freeListAllocator(uint32_t typeId)
{
    return FreeListAllocator<EntryT, RefT, ReclaimerT>(*this, typeId);
}

template <typename RefT>
template <typename EntryT>
RawAllocator<EntryT, RefT>
DataStoreT<RefT>::rawAllocator(uint32_t typeId)
{
    return RawAllocator<EntryT, RefT>(*this, typeId);
}

template <typename RefT>
template <typename EntryT>
FreeListRawAllocator<EntryT, RefT>
DataStoreT<RefT>::freeListRawAllocator(uint32_t typeId)
{
    return FreeListRawAllocator<EntryT, RefT>(*this, typeId);
}

template <typename EntryType, typename RefT>
DataStore<EntryType, RefT>::DataStore()
    : DataStore(std::make_unique<BufferType<EntryType>>(1, RefType::offsetSize(), RefType::offsetSize()))
{
}

template <typename EntryType, typename RefT>
DataStore<EntryType, RefT>::DataStore(uint32_t min_arrays)
    : DataStore(std::make_unique<BufferType<EntryType>>(1, min_arrays, RefType::offsetSize()))
{
}

template <typename EntryType, typename RefT>
DataStore<EntryType, RefT>::DataStore(BufferTypeUP type)
    : ParentType(),
      _type(std::move(type))
{
    addType(_type.get());
    init_primary_buffers();
}

template <typename EntryType, typename RefT>
DataStore<EntryType, RefT>::~DataStore()
{
    dropBuffers();  // Drop buffers before type handlers are dropped
}

template <typename EntryType, typename RefT>
EntryRef
DataStore<EntryType, RefT>::addEntry(const EntryType &e)
{
    using NoOpReclaimer = DefaultReclaimer<EntryType>;
    // Note: This will fallback to regular allocation if free lists are not enabled.
    return FreeListAllocator<EntryType, RefT, NoOpReclaimer>(*this, 0).alloc(e).ref;
}

extern template class DataStoreT<EntryRefT<22> >;

}

