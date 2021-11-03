// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
#pragma once

#include "primitivedatatype.h"
#include <vespa/eval/eval/value_type.h>

namespace document {

/*
 * This class describes a tensor type.
 */
class TensorDataType : public PrimitiveDataType {
    vespalib::eval::ValueType _tensorType;
public:
    TensorDataType();
    TensorDataType(vespalib::eval::ValueType tensorType);
    ~TensorDataType();

    std::unique_ptr<FieldValue> createFieldValue() const override;
    TensorDataType* clone() const override;
    void print(std::ostream&, bool verbose, const std::string& indent) const override;
    static std::unique_ptr<const TensorDataType> fromSpec(const vespalib::string &spec);
    
    DECLARE_IDENTIFIABLE_ABSTRACT(TensorDataType);

    const vespalib::eval::ValueType &getTensorType() const { return _tensorType; }
    bool isAssignableType(const vespalib::eval::ValueType &tensorType) const;
    static bool isAssignableType(const vespalib::eval::ValueType &fieldTensorType, const vespalib::eval::ValueType &tensorType);
};

}
