// Copyright Verizon Media. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#include "hnsw_index_loader.h"
#include "hnsw_graph.h"
#include <vespa/searchlib/util/fileutil.h>

namespace search::tensor {

HnswIndexLoader::~HnswIndexLoader() {}

HnswIndexLoader::HnswIndexLoader(HnswGraph &graph)
    : _graph(graph), _ptr(nullptr), _end(nullptr), _failed(false)
{
}

bool
HnswIndexLoader::load(const fileutil::LoadedBuffer& buf)
{
    size_t num_readable = buf.size(sizeof(uint32_t));
    _ptr = static_cast<const uint32_t *>(buf.buffer());
    _end = _ptr + num_readable;
    uint32_t entry_docid = next_int();
    int32_t entry_level = next_int();
    uint32_t num_nodes = next_int();
    std::vector<uint32_t> link_array;
    for (uint32_t docid = 0; docid < num_nodes; ++docid) {
        uint32_t num_levels = next_int();
        if (num_levels > 0) {
            _graph.make_node_for_document(docid, num_levels);
            for (uint32_t level = 0; level < num_levels; ++level) {
                uint32_t num_links = next_int();
                link_array.clear();
                while (num_links-- > 0) {
                    link_array.push_back(next_int());
                }
                _graph.set_link_array(docid, level, link_array);
            }
        }
    }
    if (_failed) return false;
    _graph.node_refs.ensure_size(std::max(num_nodes, 1u));
    _graph.node_refs_size.store(std::max(num_nodes, 1u), std::memory_order_release);
    _graph.trim_node_refs_size();
    auto entry_node_ref = _graph.get_node_ref(entry_docid);
    _graph.set_entry_node({entry_docid, entry_node_ref, entry_level});
    return true;
}


}
