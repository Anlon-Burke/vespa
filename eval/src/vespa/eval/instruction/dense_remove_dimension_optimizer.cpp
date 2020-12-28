// Copyright 2018 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#include "dense_remove_dimension_optimizer.h"
#include "dense_replace_type_function.h"
#include <vespa/eval/eval/value_type.h>

namespace vespalib::eval {

using namespace tensor_function;

namespace {

bool is_trivial_dim_list(const ValueType &type, const std::vector<vespalib::string> &dim_list) {
    size_t npos = ValueType::Dimension::npos;
    for (const vespalib::string &dim: dim_list) {
        size_t idx = type.dimension_index(dim);
        if ((idx == npos) || (type.dimensions()[idx].size != 1)) {
            return false;
        }
    }
    return true;
}

} // namespace vespalib::eval::<unnamed>

const TensorFunction &
DenseRemoveDimensionOptimizer::optimize(const TensorFunction &expr, Stash &stash)
{
    if (auto reduce = as<Reduce>(expr)) {
        const TensorFunction &child = reduce->child();
        if (expr.result_type().is_dense() &&
            child.result_type().is_dense() &&
            aggr::is_ident(reduce->aggr()) &&
            is_trivial_dim_list(child.result_type(), reduce->dimensions()))
        {
            assert(expr.result_type().cell_type() == child.result_type().cell_type());
            return DenseReplaceTypeFunction::create_compact(expr.result_type(), child, stash);
        }
    }
    return expr;
}

} // namespace vespalib::eval
