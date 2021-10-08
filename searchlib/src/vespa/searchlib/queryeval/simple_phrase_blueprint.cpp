// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#include "simple_phrase_blueprint.h"
#include "simple_phrase_search.h"
#include "emptysearch.h"
#include "field_spec.hpp"
#include <vespa/searchlib/fef/termfieldmatchdata.h>
#include <vespa/vespalib/objects/visit.hpp>
#include <algorithm>
#include <map>

namespace search::queryeval {

SimplePhraseBlueprint::SimplePhraseBlueprint(const FieldSpec &field, const IRequestContext & requestContext, bool expensive)
    : ComplexLeafBlueprint(field),
      _doom(requestContext.getDoom()),
      _field(field),
      _estimate(),
      _layout(),
      _terms()
{
    if (expensive) {
        set_cost_tier(State::COST_TIER_EXPENSIVE);
    }
}

SimplePhraseBlueprint::~SimplePhraseBlueprint()
{
    while (!_terms.empty()) {
        delete _terms.back();
        _terms.pop_back();
    }
}

FieldSpec
SimplePhraseBlueprint::getNextChildField(const FieldSpec &outer)
{
    return FieldSpec(outer.getName(), outer.getFieldId(), _layout.allocTermField(outer.getFieldId()), false);
}

void
SimplePhraseBlueprint::addTerm(Blueprint::UP term)
{
    const State &childState = term->getState();
    assert(childState.numFields() == 1);
    const FieldSpecBase &childField = childState.field(0);
    assert(childField.getFieldId() == _field.getFieldId());
    (void) childField;

    HitEstimate childEst = childState.estimate();
    if (_terms.empty() ||  childEst < _estimate) {
        _estimate = childEst;
    }
    setEstimate(_estimate);
    _terms.push_back(term.release());
}

SearchIterator::UP
SimplePhraseBlueprint::createLeafSearch(const fef::TermFieldMatchDataArray &tfmda, bool strict) const
{
    assert(tfmda.size() == 1);
    fef::MatchData::UP md = _layout.createMatchData();
    fef::TermFieldMatchDataArray childMatch;
    SimplePhraseSearch::Children children;
    children.reserve(_terms.size());
    std::multimap<uint32_t, uint32_t> order_map;
    for (size_t i = 0; i < _terms.size(); ++i) {
        const State &childState = _terms[i]->getState();
        assert(childState.numFields() == 1);
        auto *child_term_field_match_data = childState.field(0).resolve(*md);
        child_term_field_match_data->setNeedInterleavedFeatures(tfmda[0]->needs_interleaved_features());
        child_term_field_match_data->setNeedNormalFeatures(true);
        childMatch.add(child_term_field_match_data);
        children.push_back(_terms[i]->createSearch(*md, strict));
        order_map.insert(std::make_pair(childState.estimate().estHits, i));
    }
    std::vector<uint32_t> eval_order;
    for (const auto & child : order_map) {
        eval_order.push_back(child.second);
    }
    
    auto phrase = std::make_unique<SimplePhraseSearch>(std::move(children),
                                                       std::move(md), childMatch,
                                                       eval_order, *tfmda[0], strict);
    phrase->setDoom(& _doom);
    return phrase;
}

SearchIterator::UP
SimplePhraseBlueprint::createFilterSearch(bool strict, FilterConstraint constraint) const
{
    if (constraint == FilterConstraint::UPPER_BOUND) {
        MultiSearch::Children children;
        children.reserve(_terms.size());
        for (size_t i = 0; i < _terms.size(); ++i) {
            bool child_strict = strict && (i == 0);
            children.push_back(_terms[i]->createFilterSearch(child_strict, constraint));
        }
        UnpackInfo unpack_info;
        return AndSearch::create(std::move(children), strict, unpack_info);
    } else {
        return std::make_unique<EmptySearch>();
    }
}

void
SimplePhraseBlueprint::fetchPostings(const ExecuteInfo &execInfo)
{
    for (auto & term : _terms) {
        term->fetchPostings(execInfo);
    }
}

void
SimplePhraseBlueprint::visitMembers(vespalib::ObjectVisitor &visitor) const
{
    LeafBlueprint::visitMembers(visitor);
    visit(visitor, "terms", _terms);
}

}
