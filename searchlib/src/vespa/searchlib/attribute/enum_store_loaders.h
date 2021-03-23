// Copyright 2019 Oath Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#pragma once

#include "enum_store_types.h"
#include "loadedenumvalue.h"

namespace search { class IEnumStore; }

namespace search::enumstore {

/**
 * Base helper class used to load an enum store from enumerated save files.
 */
class EnumeratedLoaderBase {
protected:
    IEnumStore& _store;
    IndexVector _indexes;

    void release_enum_indexes();
public:
    EnumeratedLoaderBase(IEnumStore& store);
    const IndexVector& get_enum_indexes() const { return _indexes; }
    void load_unique_values(const void* src, size_t available);
    void free_unused_values();
};

/**
 * Helper class used to load an enum store from enumerated save files.
 */
class EnumeratedLoader : public EnumeratedLoaderBase {
private:
    EnumVector _enums_histogram;

public:
    EnumeratedLoader(IEnumStore& store);
    EnumVector& get_enums_histogram() { return _enums_histogram; }
    void allocate_enums_histogram() {
        EnumVector(_indexes.size(), 0).swap(_enums_histogram);
    }
    void set_ref_counts();
    void build_dictionary();
};

/**
 * Helper class used to load an enum store (with posting lists) from enumerated save files.
 */
class EnumeratedPostingsLoader : public EnumeratedLoaderBase {
private:
    attribute::LoadedEnumAttributeVector _loaded_enums;
    vespalib::Array<uint32_t>            _posting_indexes;

public:
    EnumeratedPostingsLoader(IEnumStore& store);
    ~EnumeratedPostingsLoader();
    attribute::LoadedEnumAttributeVector& get_loaded_enums() { return _loaded_enums; }
    void reserve_loaded_enums(size_t num_values) {
        _loaded_enums.reserve(num_values);
    }
    void sort_loaded_enums() {
        attribute::sortLoadedByEnum(_loaded_enums);
    }
    bool is_folded_change(Index lhs, Index rhs) const;
    void set_ref_count(Index idx, uint32_t ref_count);
    vespalib::ArrayRef<uint32_t> initialize_empty_posting_indexes();
    void build_dictionary();
};

}
