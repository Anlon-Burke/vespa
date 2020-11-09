// Copyright Verizon Media. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#pragma once

#include <vespa/eval/eval/nested_loop.h>
#include <vespa/eval/eval/value_type.h>
#include <vespa/eval/eval/interpreted_function.h>
#include <vespa/vespalib/stllike/string.h>
#include <vector>

namespace vespalib::eval { struct ValueBuilderFactory; }

namespace vespalib::eval::instruction {
 
struct DenseRenamePlan {
    std::vector<size_t> loop_cnt;
    std::vector<size_t> stride;
    const size_t subspace_size;
    DenseRenamePlan(const ValueType &lhs_type,
                    const ValueType &output_type,
                    const std::vector<vespalib::string> &from,
                    const std::vector<vespalib::string> &to);
    ~DenseRenamePlan();
    template <typename F> void execute(size_t offset, const F &f) const {
        run_nested_loop(offset, loop_cnt, stride, f);
    }
};

struct SparseRenamePlan {
    size_t mapped_dims;
    std::vector<size_t> output_dimensions;
    SparseRenamePlan(const ValueType &input_type,
                     const ValueType &output_type,
                     const std::vector<vespalib::string> &from,
                     const std::vector<vespalib::string> &to);
    ~SparseRenamePlan();
};

//-----------------------------------------------------------------------------

struct GenericRename {
    static InterpretedFunction::Instruction
    make_instruction(const ValueType &lhs_type,
                     const std::vector<vespalib::string> &rename_dimension_from,
                     const std::vector<vespalib::string> &rename_dimension_to,
                     const ValueBuilderFactory &factory, Stash &stash);

    static Value::UP
    perform_rename(const Value &a,
                   const std::vector<vespalib::string> &rename_dimension_from,
                   const std::vector<vespalib::string> &rename_dimension_to,
                   const ValueBuilderFactory &factory);
};

} // namespace
