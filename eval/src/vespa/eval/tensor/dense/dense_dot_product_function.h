// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#pragma once

#include <vespa/eval/eval/tensor_function.h>

namespace vespalib::tensor {

/**
 * Tensor function for a dot product between two 1-dimensional dense tensors.
 */
class DenseDotProductFunction : public eval::tensor_function::Op2
{
private:
    using ValueType = eval::ValueType;
public:
    DenseDotProductFunction(const eval::TensorFunction &lhs_in,
                            const eval::TensorFunction &rhs_in);
    eval::InterpretedFunction::Instruction compile_self(eval::EngineOrFactory engine, Stash &stash) const override;
    bool result_is_mutable() const override { return true; }
    static bool compatible_types(const ValueType &res, const ValueType &lhs, const ValueType &rhs);
    static const eval::TensorFunction &optimize(const eval::TensorFunction &expr, Stash &stash);
};

} // namespace vespalib::tensor
