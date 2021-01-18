// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#include "atomic_entry_ref.h"
#include "buffer_type.hpp"
#include <cassert>

namespace vespalib::datastore {

namespace {

constexpr float DEFAULT_ALLOC_GROW_FACTOR = 0.2;

}

void
BufferTypeBase::CleanContext::extraBytesCleaned(size_t value)
{
    assert(_extraUsedBytes >= value);
    assert(_extraHoldBytes >= value);
    _extraUsedBytes -= value;
    _extraHoldBytes -= value;
}

BufferTypeBase::BufferTypeBase(uint32_t arraySize,
                               uint32_t minArrays,
                               uint32_t maxArrays,
                               uint32_t numArraysForNewBuffer,
                               float allocGrowFactor) noexcept
    : _arraySize(arraySize),
      _minArrays(std::min(minArrays, maxArrays)),
      _maxArrays(maxArrays),
      _numArraysForNewBuffer(std::min(numArraysForNewBuffer, maxArrays)),
      _allocGrowFactor(allocGrowFactor),
      _activeBuffers(0),
      _holdBuffers(0),
      _activeUsedElems(0),
      _holdUsedElems(0),
      _lastUsedElems(nullptr)
{
}

BufferTypeBase::BufferTypeBase(uint32_t arraySize,
                               uint32_t minArrays,
                               uint32_t maxArrays) noexcept
    : BufferTypeBase(arraySize, minArrays, maxArrays, 0u, DEFAULT_ALLOC_GROW_FACTOR)
{
}

BufferTypeBase::~BufferTypeBase()
{
    assert(_activeBuffers == 0);
    assert(_holdBuffers == 0);
    assert(_activeUsedElems == 0);
    assert(_holdUsedElems == 0);
    assert(_lastUsedElems == nullptr);
}

ElemCount
BufferTypeBase::getReservedElements(uint32_t bufferId) const
{
    return bufferId == 0 ? _arraySize : 0u;
}

void
BufferTypeBase::flushLastUsed()
{
    if (_lastUsedElems != nullptr) {
        _activeUsedElems += *_lastUsedElems;
        _lastUsedElems = nullptr;
    }
}

void
BufferTypeBase::onActive(uint32_t bufferId, ElemCount *usedElems, ElemCount &deadElems, void *buffer)
{
    flushLastUsed();
    ++_activeBuffers;
    _lastUsedElems = usedElems;
    size_t reservedElems = getReservedElements(bufferId);
    if (reservedElems != 0u) {
        initializeReservedElements(buffer, reservedElems);
        *usedElems = reservedElems;
        deadElems = reservedElems;
    }
}

void
BufferTypeBase::onHold(const ElemCount *usedElems)
{
    if (usedElems == _lastUsedElems) {
        flushLastUsed();
    }
    --_activeBuffers;
    ++_holdBuffers;
    assert(_activeUsedElems >= *usedElems);
    _activeUsedElems -= *usedElems;
    _holdUsedElems += *usedElems;
}

void
BufferTypeBase::onFree(ElemCount usedElems)
{
    --_holdBuffers;
    assert(_holdUsedElems >= usedElems);
    _holdUsedElems -= usedElems;
}

void
BufferTypeBase::clampMaxArrays(uint32_t maxArrays)
{
    _maxArrays = std::min(_maxArrays, maxArrays);
    _minArrays = std::min(_minArrays, _maxArrays);
    _numArraysForNewBuffer = std::min(_numArraysForNewBuffer, _maxArrays);
}

size_t
BufferTypeBase::calcArraysToAlloc(uint32_t bufferId, ElemCount elemsNeeded, bool resizing) const
{
    size_t reservedElems = getReservedElements(bufferId);
    size_t usedElems = (resizing ? 0 : _activeUsedElems);
    if (_lastUsedElems != nullptr) {
        usedElems += *_lastUsedElems;
    }
    assert((usedElems % _arraySize) == 0);
    size_t usedArrays = usedElems / _arraySize;
    size_t neededArrays = (elemsNeeded + (resizing ? usedElems : reservedElems) + _arraySize - 1) / _arraySize;
    size_t growArrays = (usedArrays * _allocGrowFactor);
    size_t wantedArrays = std::max((resizing ? usedArrays : 0u) + growArrays,
                                   static_cast<size_t>(_minArrays));
    size_t result = wantedArrays;
    if (result < neededArrays) {
        result = neededArrays;
    }
    if (result > _maxArrays) {
        result = _maxArrays;
    }
    assert(result >= neededArrays);
    return result;
}

template class BufferType<char>;
template class BufferType<uint8_t>;
template class BufferType<uint32_t>;
template class BufferType<uint64_t>;
template class BufferType<int32_t>;
template class BufferType<std::string>;
template class BufferType<AtomicEntryRef>;

}

