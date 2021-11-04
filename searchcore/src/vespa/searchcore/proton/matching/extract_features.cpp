// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#include "extract_features.h"
#include "match_tools.h"
#include <vespa/eval/eval/value_codec.h>
#include <vespa/vespalib/objects/nbostream.h>
#include <vespa/vespalib/util/runnable.h>
#include <vespa/vespalib/util/thread_bundle.h>
#include <vespa/searchlib/fef/feature_resolver.h>
#include <vespa/searchlib/fef/rank_program.h>
#include <vespa/searchlib/queryeval/searchiterator.h>

using vespalib::Runnable;
using vespalib::ThreadBundle;
using search::FeatureSet;
using search::FeatureValues;
using search::fef::FeatureResolver;
using search::fef::RankProgram;
using search::queryeval::SearchIterator;

namespace proton::matching {

using OrderedDocs = ExtractFeatures::OrderedDocs;

namespace {

struct MyChunk : Runnable {
    const std::pair<uint32_t,uint32_t> *begin;
    const std::pair<uint32_t,uint32_t> *end;
    FeatureValues &result;
    MyChunk(const std::pair<uint32_t,uint32_t> *begin_in,
           const std::pair<uint32_t,uint32_t> *end_in,
           FeatureValues &result_in)
      : begin(begin_in), end(end_in), result(result_in) {}
    void calculate_features(SearchIterator &search, FeatureResolver &resolver) {
        size_t num_features = result.names.size();
        assert(end > begin);
        assert(num_features == resolver.num_features());
        search.initRange(begin[0].first, end[-1].first + 1);
        for (auto pos = begin; pos != end; ++pos) {
            uint32_t docid = pos->first;
            search.unpack(docid);
            auto * f = &result.values[pos->second * num_features];
            for (uint32_t i = 0; i < num_features; ++i) {
                if (resolver.is_object(i)) {
                    auto obj = resolver.resolve(i).as_object(docid);
                    if (!obj.get().type().is_double()) {
                        vespalib::nbostream buf;
                        encode_value(obj.get(), buf);
                        f[i].set_data(vespalib::Memory(buf.peek(), buf.size()));
                    } else {
                        f[i].set_double(obj.get().as_double());
                    }
                } else {
                    f[i].set_double(resolver.resolve(i).as_number(docid));
                }
            }
        }
    }
};

struct FirstChunk : MyChunk {
    SearchIterator &search;
    FeatureResolver &resolver;
    FirstChunk(const std::pair<uint32_t,uint32_t> *begin_in,
               const std::pair<uint32_t,uint32_t> *end_in,
               FeatureValues &result_in,
               SearchIterator &search_in,
               FeatureResolver &resolver_in)
      : MyChunk(begin_in, end_in, result_in),
        search(search_in),
        resolver(resolver_in) {}
    void run() override { calculate_features(search, resolver); }
};

struct LaterChunk : MyChunk {
    const MatchToolsFactory &mtf;
    LaterChunk(const std::pair<uint32_t,uint32_t> *begin_in,
               const std::pair<uint32_t,uint32_t> *end_in,
               FeatureValues &result_in,
               const MatchToolsFactory &mtf_in)
      : MyChunk(begin_in, end_in, result_in),
        mtf(mtf_in) {}
    void run() override {
        auto tools = mtf.createMatchTools();
        tools->setup_match_features();
        FeatureResolver resolver(tools->rank_program().get_seeds(false));
        calculate_features(tools->search(), resolver);
    }
};

struct MyWork {
    size_t num_threads;
    std::vector<Runnable::UP> chunks;
    MyWork(ThreadBundle &thread_bundle) : num_threads(thread_bundle.size()), chunks() {
        chunks.reserve(num_threads);
    }
    void run(ThreadBundle &thread_bundle) {
        std::vector<Runnable*> refs;
        refs.reserve(chunks.size());
        for (const auto &task: chunks) {
            refs.push_back(task.get());
        }
        thread_bundle.run(refs);
    }
};

} // unnamed

FeatureSet::UP
ExtractFeatures::get_feature_set(SearchIterator &search, RankProgram &rank_program, const std::vector<uint32_t> &docs)
{
    std::vector<vespalib::string> featureNames;
    FeatureResolver resolver(rank_program.get_seeds(false));
    featureNames.reserve(resolver.num_features());
    for (size_t i = 0; i < resolver.num_features(); ++i) {
        featureNames.emplace_back(resolver.name_of(i));
    }
    auto result = std::make_unique<FeatureSet>(featureNames, docs.size());
    if (!docs.empty()) {
        search.initRange(docs.front(), docs.back()+1);
        for (uint32_t docid: docs) {
            search.unpack(docid);
            auto * f = result->getFeaturesByIndex(result->addDocId(docid));
            for (uint32_t i = 0; i < featureNames.size(); ++i) {
                if (resolver.is_object(i)) {
                    auto obj = resolver.resolve(i).as_object(docid);
                    if (!obj.get().type().is_double()) {
                        vespalib::nbostream buf;
                        encode_value(obj.get(), buf);
                        f[i].set_data(vespalib::Memory(buf.peek(), buf.size()));
                    } else {
                        f[i].set_double(obj.get().as_double());
                    }
                } else {
                    f[i].set_double(resolver.resolve(i).as_number(docid));
                }
            }
        }
    }
    return result;
}

FeatureValues
ExtractFeatures::get_match_features(const MatchToolsFactory &mtf, const OrderedDocs &docs, ThreadBundle &thread_bundle)
{
    FeatureValues result;
    auto tools = mtf.createMatchTools();
    tools->setup_match_features();
    FeatureResolver resolver(tools->rank_program().get_seeds(false));
    result.names.reserve(resolver.num_features());
    for (size_t i = 0; i < resolver.num_features(); ++i) {
        result.names.emplace_back(resolver.name_of(i));
    }
    result.values.resize(result.names.size() * docs.size());
    MyWork work(thread_bundle);
    size_t per_thread = docs.size() / work.num_threads;
    size_t rest_docs = docs.size() % work.num_threads;
    size_t idx = 0;
    for (size_t i = 0; i < work.num_threads; ++i) {
        size_t chunk_size = per_thread + (i < rest_docs);
        if (chunk_size == 0) {
            break;
        }
        if (i == 0) {
            work.chunks.push_back(std::make_unique<FirstChunk>(&docs[idx], &docs[idx + chunk_size], result, tools->search(), resolver));
        } else {
            work.chunks.push_back(std::make_unique<LaterChunk>(&docs[idx], &docs[idx + chunk_size], result, mtf));
        }
        idx += chunk_size;
    }
    assert(idx == docs.size());
    work.run(thread_bundle);
    return result;
}

}
