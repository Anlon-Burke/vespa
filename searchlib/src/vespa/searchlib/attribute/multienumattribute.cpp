// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#include "multienumattribute.h"
#include "multienumattribute.hpp"
#include <stdexcept>

namespace search {

uint32_t
IWeightedIndexVector::getEnumHandles(uint32_t, const WeightedIndex * &) const {
    throw std::runtime_error("IWeightedIndexVector::getEnumHandles() not implmented");
}

}

namespace search::multienumattribute {

using EnumIndex = IEnumStore::Index;
using EnumIndexRemapper = IEnumStore::EnumIndexRemapper;
using Value = multivalue::Value<vespalib::datastore::AtomicEntryRef>;
using WeightedValue = multivalue::WeightedValue<vespalib::datastore::AtomicEntryRef>;

template <typename WeightedIndex>
void
remap_enum_store_refs(const EnumIndexRemapper& remapper, AttributeVector& v, attribute::MultiValueMapping<WeightedIndex>& multi_value_mapping)
{
    // update multi_value_mapping with new EnumIndex values after enum store has been compacted.
    v.logEnumStoreEvent("compactfixup", "drain");
    {
        AttributeVector::EnumModifier enum_guard(v.getEnumModifier());
        auto& filter = remapper.get_entry_ref_filter();
        v.logEnumStoreEvent("compactfixup", "start");
        for (uint32_t doc = 0; doc < v.getNumDocs(); ++doc) {
            vespalib::ArrayRef<WeightedIndex> indices(multi_value_mapping.get_writable(doc));
            for (auto& entry : indices) {
                EnumIndex ref = entry.value_ref().load_relaxed();
                if (ref.valid() && filter.has(ref)) {
                    ref = remapper.remap(ref);
                    entry.value_ref().store_release(ref);
                }
            }
        }
    }
    v.logEnumStoreEvent("compactfixup", "complete");
}

template void remap_enum_store_refs(const EnumIndexRemapper&, AttributeVector&, attribute::MultiValueMapping<Value> &);
template void remap_enum_store_refs(const EnumIndexRemapper&, AttributeVector&, attribute::MultiValueMapping<WeightedValue> &);

}
