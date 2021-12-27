// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#pragma once

#include "tensor_store.h"
#include <vespa/eval/eval/value_type.h>
#include <vespa/eval/eval/typed_cells.h>

namespace vespalib::eval { struct Value; }

namespace search::tensor {

/**
 * Class for storing dense tensors with known bounds in memory, used
 * by DenseTensorAttribute.
 */
class DenseTensorStore : public TensorStore
{
public:
    using RefType = vespalib::datastore::EntryRefT<22>;
    using DataStoreType = vespalib::datastore::DataStoreT<RefType>;
    using ValueType = vespalib::eval::ValueType;

    struct TensorSizeCalc
    {
        size_t   _numCells; // product of dimension sizes
        vespalib::eval::CellType _cell_type;
        size_t   _aligned_size;

        TensorSizeCalc(const ValueType &type);
        size_t bufSize() const {
            return vespalib::eval::CellTypeUtils::mem_size(_cell_type, _numCells);
        }
        size_t alignedSize() const noexcept { return _aligned_size; }
    };

    class BufferType : public vespalib::datastore::BufferType<char>
    {
        using CleanContext = vespalib::datastore::BufferType<char>::CleanContext;
        std::unique_ptr<vespalib::alloc::MemoryAllocator> _allocator;
    public:
        BufferType(const TensorSizeCalc &tensorSizeCalc, std::unique_ptr<vespalib::alloc::MemoryAllocator> allocator);
        ~BufferType() override;
        void cleanHold(void *buffer, size_t offset, ElemCount numElems, CleanContext cleanCtx) override;
        const vespalib::alloc::MemoryAllocator* get_memory_allocator() const override;
    };
private:
    DataStoreType _concreteStore;
    TensorSizeCalc _tensorSizeCalc;
    BufferType _bufferType;
    ValueType _type; // type of dense tensor
    std::vector<char> _emptySpace;

    template <class TensorType>
    TensorStore::EntryRef
    setDenseTensor(const TensorType &tensor);
public:
    DenseTensorStore(const ValueType &type, std::unique_ptr<vespalib::alloc::MemoryAllocator> allocator);
    ~DenseTensorStore() override;

    const ValueType &type() const { return _type; }
    size_t getNumCells() const { return _tensorSizeCalc._numCells; }
    size_t getBufSize() const { return _tensorSizeCalc.bufSize(); }
    const void *getRawBuffer(RefType ref) const {
        return _store.getEntryArray<char>(ref, _bufferType.getArraySize());
    }
    vespalib::datastore::Handle<char> allocRawBuffer();
    void holdTensor(EntryRef ref) override;
    EntryRef move(EntryRef ref) override;
    std::unique_ptr<vespalib::eval::Value> getTensor(EntryRef ref) const;
    vespalib::eval::TypedCells get_typed_cells(EntryRef ref) const {
        return vespalib::eval::TypedCells(ref.valid() ? getRawBuffer(ref) : &_emptySpace[0],
                                          _type.cell_type(), getNumCells());
    }
    EntryRef setTensor(const vespalib::eval::Value &tensor);
    // The following method is meant to be used only for unit tests.
    uint32_t getArraySize() const { return _bufferType.getArraySize(); }
};

}
