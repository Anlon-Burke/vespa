// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#pragma once

#include "postinglisttraits.h"

#include <functional>

namespace search {

namespace query { class Node; }

using DocumentWeightIterator = attribute::PostingListTraits<int32_t>::const_iterator;

struct IDocumentWeightAttribute
{
    struct LookupResult {
        const vespalib::datastore::EntryRef posting_idx;
        const uint32_t posting_size;
        const int32_t min_weight;
        const int32_t max_weight;
        const vespalib::datastore::EntryRef enum_idx;
        LookupResult() : posting_idx(), posting_size(0), min_weight(0), max_weight(0), enum_idx() {}
        LookupResult(vespalib::datastore::EntryRef posting_idx_in, uint32_t posting_size_in, int32_t min_weight_in, int32_t max_weight_in, vespalib::datastore::EntryRef enum_idx_in)
            : posting_idx(posting_idx_in), posting_size(posting_size_in), min_weight(min_weight_in), max_weight(max_weight_in), enum_idx(enum_idx_in) {}
    };
    virtual vespalib::datastore::EntryRef get_dictionary_snapshot() const = 0;
    virtual LookupResult lookup(const vespalib::string &term, vespalib::datastore::EntryRef dictionary_snapshot) const = 0;
    /*
     * Collect enum indexes (via callback) where folded
     * (e.g. lowercased) value equals the folded value for enum_idx.
     */
    virtual void collect_folded(vespalib::datastore::EntryRef enum_idx, vespalib::datastore::EntryRef dictionary_snapshot, const std::function<void(vespalib::datastore::EntryRef)>& callback) const = 0;
    virtual void create(vespalib::datastore::EntryRef idx, std::vector<DocumentWeightIterator> &dst) const = 0;
    virtual DocumentWeightIterator create(vespalib::datastore::EntryRef idx) const = 0;
    virtual ~IDocumentWeightAttribute() {}
};

}

