// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#include "reusable_set_handle.h"
#include "reusable_set_pool.h"

namespace vespalib {

ReusableSetHandle::~ReusableSetHandle()
{
    _pool.reuse(std::move(_owned));
}

} // namespace
