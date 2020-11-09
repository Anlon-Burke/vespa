// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#pragma once

#include <vespa/vespalib/util/hdr_abort.h>
#include "sparse_tensor.h"
#include "sparse_tensor_t.h"
#include "sparse_tensor_address_builder.h"

namespace vespalib::tensor {

/**
 * Utility class to build tensors of type SparseTensor, to be used by
 * tensor operations.
 */
template<typename T>
class DirectSparseTensorBuilder
{
public:
    using AddressBuilderType = SparseTensorAddressBuilder;
    using AddressRefType = SparseTensorAddressRef;

private:
    eval::ValueType _type;
    SparseTensorIndex _index;
    std::vector<T> _values;

public:
    DirectSparseTensorBuilder();
    DirectSparseTensorBuilder(const eval::ValueType &type_in);
    ~DirectSparseTensorBuilder();

    std::unique_ptr<SparseTensorT<T>> build();

    template <class Function>
    void insertCell(SparseTensorAddressRef address, T value, Function &&func)
    {
        size_t idx;
        if (_index.lookup_address(address, idx)) {
            _values[idx] = func(_values[idx], value);
        } else {
            idx = _index.lookup_or_add(address);
            assert(idx == _values.size());
            _values.push_back(value);
        }
    }

    void insertCell(SparseTensorAddressRef address, T value) {
        // This address should not already exist and a new cell should be inserted.
        _index.add_address(address);
        _values.push_back(value);
    }

    template <class Function>
    void insertCell(SparseTensorAddressBuilder &address, T value, Function &&func) {
        insertCell(address.getAddressRef(), value, func);
    }

    void insertCell(SparseTensorAddressBuilder &address, T value) {
        // This address should not already exist and a new cell should be inserted.
        insertCell(address.getAddressRef(), value);
    }

    eval::ValueType &fast_type() { return _type; }

    void reserve(uint32_t estimatedCells);
};

}
