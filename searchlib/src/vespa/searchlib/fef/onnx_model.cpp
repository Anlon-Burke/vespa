// Copyright Verizon Media. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#include "onnx_model.h"
#include <tuple>

namespace search::fef {

OnnxModel::OnnxModel(const vespalib::string &name_in,
                     const vespalib::string &file_path_in)
    : _name(name_in),
      _file_path(file_path_in),
      _input_features(),
      _output_names()
{
}

OnnxModel &
OnnxModel::input_feature(const vespalib::string &model_input_name, const vespalib::string &input_feature) {
    _input_features[model_input_name] = input_feature;
    return *this;
}

OnnxModel &
OnnxModel::output_name(const vespalib::string &model_output_name, const vespalib::string &output_name) {
    _output_names[model_output_name] = output_name;
    return *this;
}

std::optional<vespalib::string>
OnnxModel::input_feature(const vespalib::string &model_input_name) const {
    auto pos = _input_features.find(model_input_name);
    if (pos != _input_features.end()) {
        return pos->second;
    }
    return std::nullopt;
}

std::optional<vespalib::string>
OnnxModel::output_name(const vespalib::string &model_output_name) const {
    auto pos = _output_names.find(model_output_name);
    if (pos != _output_names.end()) {
        return pos->second;
    }
    return std::nullopt;
}

bool
OnnxModel::operator==(const OnnxModel &rhs) const {
    return (std::tie(_name, _file_path, _input_features, _output_names) ==
            std::tie(rhs._name, rhs._file_path, rhs._input_features, rhs._output_names));
}

OnnxModel::~OnnxModel() = default;

}
