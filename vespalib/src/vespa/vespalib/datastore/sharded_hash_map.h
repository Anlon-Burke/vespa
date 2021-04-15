// Copyright Verizon Media. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#pragma once

#include "atomic_entry_ref.h"
#include <atomic>
#include <vespa/vespalib/util/generationholder.h>
#include <functional>

namespace vespalib { class MemoryUsage; }
namespace vespalib::datastore {

class FixedSizeHashMap;
class EntryComparator;

/*
 * Hash map over keys in data store, meant to support a faster
 * dictionary for unique store with relation to lookups.
 *
 * Currently hardcoded key and data types, where key references an entry
 * in a UniqueStore and value references a posting list
 * (cf. search::attribute::PostingStore).
 *
 * This structure supports one writer and many readers.
 *
 * A reader must own an appropriate GenerationHandler::Guard to ensure
 * that memory is held while it can be accessed by reader.
 *
 * The writer must update generation and call transfer_hold_lists and
 * trim_hold_lists as needed to free up memory no longer needed by any
 * readers.
 */
class ShardedHashMap {
public:
    using KvType = std::pair<AtomicEntryRef, AtomicEntryRef>;
    using generation_t = GenerationHandler::generation_t;
    using sgeneration_t = GenerationHandler::sgeneration_t;
private:
    GenerationHolder _gen_holder;
    static constexpr size_t num_shards = 3;
    std::atomic<FixedSizeHashMap *> _maps[num_shards];
    std::unique_ptr<const EntryComparator> _comp;

    void alloc_shard(size_t shard_idx);
    void hold_shard(std::unique_ptr<const FixedSizeHashMap> map);
public:
    ShardedHashMap(std::unique_ptr<const EntryComparator> comp);
    ~ShardedHashMap();
    KvType& add(const EntryComparator& comp, EntryRef key_ref, std::function<EntryRef(void)> &insert_entry);
    KvType* remove(const EntryComparator& comp, EntryRef key_ref);
    KvType* find(const EntryComparator& comp, EntryRef key_ref);
    const KvType* find(const EntryComparator& comp, EntryRef key_ref) const;
    void transfer_hold_lists(generation_t generation);
    void trim_hold_lists(generation_t first_used);
    size_t size() const noexcept;
    const EntryComparator &get_default_comparator() const noexcept { return *_comp; }
    MemoryUsage get_memory_usage() const;
    void foreach_key(std::function<void(EntryRef)> callback) const;
    void move_keys(std::function<EntryRef(EntryRef)> callback);
    bool normalize_values(std::function<EntryRef(EntryRef)> normalize);
    bool has_held_buffers() const;
    void compact_worst_shard();
};

}
