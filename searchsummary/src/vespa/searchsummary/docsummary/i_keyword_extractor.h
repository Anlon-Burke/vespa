// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#pragma once

#include <vespa/vespalib/stllike/string.h>

namespace search::docsummary {

/*
 * Interface class for checking if query term index name indicates that
 * related query term is useful from the perspective of juniper.
 */
class IKeywordExtractor
{
public:
    virtual ~IKeywordExtractor() = default;

    virtual bool isLegalIndex(vespalib::stringref idx) const = 0;
};

}
