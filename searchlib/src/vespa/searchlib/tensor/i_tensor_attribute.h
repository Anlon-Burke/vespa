// Copyright 2018 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#pragma once

#include <memory>

namespace vespalib::tensor {
class MutableDenseTensorView;
}
namespace vespalib::eval { class ValueType; struct Value; }
namespace vespalib::slime { struct Inserter; }

namespace search::tensor {

/**
 * Interface for tensor attribute used by feature executors to get information.
 */
class ITensorAttribute
{
public:
    virtual ~ITensorAttribute() {}
    virtual std::unique_ptr<vespalib::eval::Value> getTensor(uint32_t docId) const = 0;
    virtual std::unique_ptr<vespalib::eval::Value> getEmptyTensor() const = 0;
    virtual void extract_dense_view(uint32_t docid, vespalib::tensor::MutableDenseTensorView& tensor) const = 0;
    virtual const vespalib::eval::Value& get_tensor_ref(uint32_t docid) const = 0;
    virtual bool supports_extract_dense_view() const = 0;
    virtual bool supports_get_tensor_ref() const = 0;

    virtual const vespalib::eval::ValueType & getTensorType() const = 0;

    /**
     * Gets custom state for this tensor attribute by inserting it into the given Slime inserter.
     * This function is only called by the writer thread or when the writer thread is blocked.
     */
    virtual void get_state(const vespalib::slime::Inserter& inserter) const = 0;
};

}  // namespace search::tensor
