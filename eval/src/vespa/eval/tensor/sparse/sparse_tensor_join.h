// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#pragma once

namespace vespalib::tensor {
    class Tensor;
    class SparseTensor;
}

namespace vespalib::tensor::sparse {

/**
 * Create new tensor using all combinations of input tensor cells with matching
 * labels for common dimensions, using func to calculate new cell value
 * based on the cell values in the input tensors.
 */
template <typename LCT, typename RCT, typename OCT, typename Function>
std::unique_ptr<Tensor>
join(const SparseTensor &lhs, const SparseTensor &rhs, eval::ValueType res_type, Function &&func);

}

