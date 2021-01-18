// Copyright 2020 Oath Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#pragma once

#include <memory>

namespace vespalib::eval { struct TypedCells; }

namespace search::tensor {

/**
 * Interface used to calculate the distance between two n-dimensional vectors.
 *
 * The vectors must be of same size and same cell type (float or double).
 * The actual implementation must know which type the vectors are.
 */
class DistanceFunction {
public:
    using UP = std::unique_ptr<DistanceFunction>;
    virtual ~DistanceFunction() {}

    // calculate internal distance (comparable)
    virtual double calc(const vespalib::eval::TypedCells& lhs, const vespalib::eval::TypedCells& rhs) const = 0;

    // convert threshold (external distance units) to internal units
    virtual double convert_threshold(double threshold) const = 0;

    // convert internal distance to rawscore (1.0 / (1.0 + d))
    virtual double to_rawscore(double distance) const = 0;

    // calculate internal distance, early return allowed if > limit
    virtual double calc_with_limit(const vespalib::eval::TypedCells& lhs,
                                   const vespalib::eval::TypedCells& rhs,
                                   double limit) const = 0;
};

}
