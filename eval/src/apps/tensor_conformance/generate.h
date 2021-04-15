// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#pragma once

#include <vespa/eval/eval/tensor_spec.h>
#include <map>

struct TestBuilder {
    bool full;
    TestBuilder(bool full_in) : full(full_in) {}
    using TensorSpec = vespalib::eval::TensorSpec;
    virtual void add(const vespalib::string &expression,
                     const std::map<vespalib::string,TensorSpec> &inputs) = 0;
    virtual ~TestBuilder() {}
};

struct Generator {
    static void generate(TestBuilder &out);
};
