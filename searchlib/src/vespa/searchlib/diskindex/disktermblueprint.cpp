// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#include "disktermblueprint.h"
#include <vespa/searchlib/common/bitvectoriterator.h>
#include <vespa/searchlib/queryeval/booleanmatchiteratorwrapper.h>
#include <vespa/searchlib/queryeval/intermediate_blueprints.h>
#include <vespa/searchlib/queryeval/filter_wrapper.h>
#include <vespa/vespalib/util/stringfmt.h>

#include <vespa/log/log.h>
LOG_SETUP(".diskindex.disktermblueprint");

using search::BitVectorIterator;
using search::fef::TermFieldMatchDataArray;
using search::index::Schema;
using search::queryeval::BooleanMatchIteratorWrapper;
using search::queryeval::FieldSpecBase;
using search::queryeval::FieldSpecBaseList;
using search::queryeval::SearchIterator;
using search::queryeval::LeafBlueprint;
using search::queryeval::Blueprint;

namespace search::diskindex {

namespace {

vespalib::string
getName(uint32_t indexId)
{
    return vespalib::make_string("fieldId(%u)", indexId);
}

}

DiskTermBlueprint::DiskTermBlueprint(const FieldSpecBase & field,
                                     const DiskIndex & diskIndex,
                                     DiskIndex::LookupResult::UP lookupRes,
                                     bool useBitVector) :
    SimpleLeafBlueprint(field),
    _field(field),
    _diskIndex(diskIndex),
    _lookupRes(std::move(lookupRes)),
    _useBitVector(useBitVector),
    _fetchPostingsDone(false),
    _postingHandle(),
    _bitVector()
{
    setEstimate(HitEstimate(_lookupRes->counts._numDocs,
                            _lookupRes->counts._numDocs == 0));
}

void
DiskTermBlueprint::fetchPostings(const queryeval::ExecuteInfo &execInfo)
{
    (void) execInfo;
    if (!_fetchPostingsDone) {
        _bitVector = _diskIndex.readBitVector(*_lookupRes);
        if (!_useBitVector || !_bitVector) {
            _postingHandle = _diskIndex.readPostingList(*_lookupRes);
        }
    }
    _fetchPostingsDone = true;
}

SearchIterator::UP
DiskTermBlueprint::createLeafSearch(const TermFieldMatchDataArray & tfmda, bool strict) const
{
    if (_bitVector && (_useBitVector || tfmda[0]->isNotNeeded())) {
        LOG(debug, "Return BitVectorIterator: %s, wordNum(%" PRIu64 "), docCount(%" PRIu64 ")",
            getName(_lookupRes->indexId).c_str(), _lookupRes->wordNum, _lookupRes->counts._numDocs);
        return BitVectorIterator::create(_bitVector.get(), *tfmda[0], strict);
    }
    SearchIterator::UP search(_postingHandle->createIterator(_lookupRes->counts, tfmda, _useBitVector));
    if (_useBitVector) {
        LOG(debug, "Return BooleanMatchIteratorWrapper: %s, wordNum(%" PRIu64 "), docCount(%" PRIu64 ")",
            getName(_lookupRes->indexId).c_str(), _lookupRes->wordNum, _lookupRes->counts._numDocs);
        return std::make_unique<BooleanMatchIteratorWrapper>(std::move(search), tfmda);
    }
    LOG(debug, "Return posting list iterator: %s, wordNum(%" PRIu64 "), docCount(%" PRIu64 ")",
        getName(_lookupRes->indexId).c_str(), _lookupRes->wordNum, _lookupRes->counts._numDocs);
    return search;
}

SearchIterator::UP
DiskTermBlueprint::createFilterSearch(bool strict, FilterConstraint) const
{
    auto wrapper = std::make_unique<queryeval::FilterWrapper>(getState().numFields());
    auto & tfmda = wrapper->tfmda();
    if (_bitVector) {
        wrapper->wrap(BitVectorIterator::create(_bitVector.get(), *tfmda[0], strict));
    } else {
        wrapper->wrap(_postingHandle->createIterator(_lookupRes->counts, tfmda, _useBitVector));
    }
    return wrapper;
}

} // namespace
