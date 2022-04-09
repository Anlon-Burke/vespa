// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#pragma once

#include "stringattribute.h"
#include "multistringattribute.h"
#include "enumattribute.hpp"
#include "multienumattribute.hpp"
#include "multi_string_enum_hint_search_context.h"
#include <vespa/vespalib/text/utf8.h>
#include <vespa/vespalib/text/lowercase.h>
#include <vespa/searchlib/util/bufferwriter.h>
#include <vespa/vespalib/util/regexp.h>
#include <vespa/searchlib/query/query_term_ucs4.h>

namespace search {

//-----------------------------------------------------------------------------
// MultiValueStringAttributeT public
//-----------------------------------------------------------------------------
template <typename B, typename M>
MultiValueStringAttributeT<B, M>::
MultiValueStringAttributeT(const vespalib::string &name,
                           const AttributeVector::Config &c)
    : MultiValueEnumAttribute<B, M>(name, c)
{ }

template <typename B, typename M>
MultiValueStringAttributeT<B, M>::~MultiValueStringAttributeT() = default;


template <typename B, typename M>
void
MultiValueStringAttributeT<B, M>::freezeEnumDictionary()
{
    this->getEnumStore().freeze_dictionary();
}


template <typename B, typename M>
std::unique_ptr<attribute::SearchContext>
MultiValueStringAttributeT<B, M>::getSearch(QueryTermSimpleUP qTerm,
                                            const attribute::SearchContextParams &) const
{
    bool cased = this->get_match_is_cased();
    auto doc_id_limit = this->getCommittedDocIdLimit();
    return std::make_unique<attribute::MultiStringEnumHintSearchContext<M>>(std::move(qTerm), cased, *this, this->_mvMapping.make_read_view(doc_id_limit), this->_enumStore, doc_id_limit, this->getStatus().getNumValues());
}

} // namespace search

