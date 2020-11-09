// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#pragma once

#include "aggr.h"
#include "operation.h"
#include <vespa/vespalib/stllike/string.h>
#include <memory>
#include <vector>
#include <functional>

namespace vespalib {

class Stash;
class nbostream;

namespace eval {

struct Value;
class ValueType;
class TensorSpec;
struct TensorFunction;

/**
 * Top-level API for a tensor implementation. All Tensor operations
 * are defined by the TensorEngine interface. The Tensor class itself
 * is used as a tagged transport mechanism. Each Tensor is connected
 * to a distinct engine which can be used to operate on it. When
 * operating on multiple tensors at the same time they all need to be
 * connected to the same engine. TensorEngines should only have a
 * single static instance per implementation.
 **/
struct TensorEngine
{
    using Aggr = eval::Aggr;
    using TensorFunction = eval::TensorFunction;
    using TensorSpec = eval::TensorSpec;
    using Value = eval::Value;
    using ValueType = eval::ValueType;
    using map_fun_t = vespalib::eval::operation::op1_t;
    using join_fun_t = vespalib::eval::operation::op2_t;

    virtual TensorSpec to_spec(const Value &value) const = 0;
    virtual std::unique_ptr<Value> from_spec(const TensorSpec &spec) const = 0;

    virtual void encode(const Value &value, nbostream &output) const = 0;
    virtual std::unique_ptr<Value> decode(nbostream &input) const = 0;

    virtual const TensorFunction &optimize(const TensorFunction &expr, Stash &) const { return expr; }

    virtual const Value &map(const Value &a, map_fun_t function, Stash &stash) const = 0;
    virtual const Value &join(const Value &a, const Value &b, join_fun_t function, Stash &stash) const = 0;
    virtual const Value &merge(const Value &a, const Value &b, join_fun_t function, Stash &stash) const = 0;
    virtual const Value &reduce(const Value &a, Aggr aggr, const std::vector<vespalib::string> &dimensions, Stash &stash) const = 0;
    virtual const Value &concat(const Value &a, const Value &b, const vespalib::string &dimension, Stash &stash) const = 0;
    virtual const Value &rename(const Value &a, const std::vector<vespalib::string> &from, const std::vector<vespalib::string> &to, Stash &stash) const = 0;

    virtual ~TensorEngine() {}
};

} // namespace vespalib::eval
} // namespace vespalib
