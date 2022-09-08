// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#include "docsumfilter.h"
#include "slimefieldwriter.h"
#include <vespa/juniper/juniper_separators.h>
#include <vespa/searchsummary/docsummary/check_undefined_value_visitor.h>
#include <vespa/searchsummary/docsummary/i_docsum_store_document.h>
#include <vespa/searchsummary/docsummary/summaryfieldconverter.h>
#include <vespa/document/base/exceptions.h>
#include <vespa/document/fieldvalue/iteratorhandler.h>
#include <vespa/vespalib/data/slime/inserter.h>

#include <vespa/log/log.h>
LOG_SETUP(".vsm.docsumfilter");

using namespace search::docsummary;


namespace vsm {

namespace {

/**
 * Class providing access to a document retrieved from an IDocsumStore
 * (vsm::DocsumFilter). VSM specific transforms might be applied when
 * accessing some fields.
 **/
class DocsumStoreVsmDocument : public IDocsumStoreDocument
{
    DocsumFilter&             _docsum_filter;
    const ResultClass&        _result_class;
    const Document&           _vsm_document;
    const document::Document* _document;

    static const document::Document *get_document_document(const Document& vsm_document) {
        const auto* storage_doc = dynamic_cast<const StorageDocument *>(&vsm_document);
        return (storage_doc != nullptr && storage_doc->valid()) ? &storage_doc->docDoc() : nullptr;
    }
    static const ResultClass& get_result_class(const DocsumFilter& docsum_filter) {
        auto result_class = docsum_filter.getTools()->getResultClass();
        assert(result_class != nullptr);
        return *result_class;
    }
public:
    DocsumStoreVsmDocument(DocsumFilter& docsum_filter, const Document& vsm_document);
    ~DocsumStoreVsmDocument() override;
    DocsumStoreFieldValue get_field_value(const vespalib::string& field_name) const override;
    JuniperInput get_juniper_input(const vespalib::string& field_name) const override;
    void insert_summary_field(const vespalib::string& field_name, vespalib::slime::Inserter& inserter) const override;
    void insert_document_id(vespalib::slime::Inserter& inserter) const override;
};

DocsumStoreVsmDocument::DocsumStoreVsmDocument(DocsumFilter& docsum_filter, const Document& vsm_document)
    : _docsum_filter(docsum_filter),
      _result_class(get_result_class(docsum_filter)),
      _vsm_document(vsm_document),
      _document(get_document_document(vsm_document))
{
}

DocsumStoreVsmDocument::~DocsumStoreVsmDocument() = default;

DocsumStoreFieldValue
DocsumStoreVsmDocument::get_field_value(const vespalib::string& field_name) const
{
    if (_document != nullptr) {
        auto entry_idx = _result_class.GetIndexFromName(field_name.c_str());
        if (entry_idx >= 0) {
            assert((uint32_t) entry_idx < _result_class.GetNumEntries());
            return _docsum_filter.get_summary_field(entry_idx, _vsm_document);
        }
        try {
            const document::Field & field = _document->getField(field_name);
            auto value(field.getDataType().createFieldValue());
            if (value) {
                if (_document->getValue(field, *value)) {
                    return DocsumStoreFieldValue(std::move(value));
                }
            }
        } catch (document::FieldNotFoundException&) {
            // Field was not found in document type. Return empty value.
        }
    }
    return {};
}

JuniperInput
DocsumStoreVsmDocument::get_juniper_input(const vespalib::string& field_name) const
{
    // Markup for juniper has already been added due to FLATTENJUNIPER command in vsm summary config.
    return JuniperInput(get_field_value(field_name));
}

void
DocsumStoreVsmDocument::insert_summary_field(const vespalib::string& field_name, vespalib::slime::Inserter& inserter) const
{
    if (_document != nullptr) {
        auto entry_idx = _result_class.GetIndexFromName(field_name.c_str());
        if (entry_idx >= 0) {
            assert((uint32_t) entry_idx < _result_class.GetNumEntries());
            _docsum_filter.insert_summary_field(entry_idx, _vsm_document, inserter);
            return;
        }
        try {
            const document::Field & field = _document->getField(field_name);
            auto value(field.getDataType().createFieldValue());
            if (value) {
                if (_document->getValue(field, *value)) {
                    SummaryFieldConverter::insert_summary_field(*value, inserter);
                }
            }
        } catch (document::FieldNotFoundException&) {
            // Field was not found in document type. Don't insert anything.
        }
    }
}

void
DocsumStoreVsmDocument::insert_document_id(vespalib::slime::Inserter& inserter) const
{
    if (_document) {
        auto id = _document->getId().toString();
        vespalib::Memory id_view(id.data(), id.size());
        inserter.insertString(id_view);
    }
}

}

FieldPath
copyPathButFirst(const FieldPath & rhs) {
    // skip the element that correspond to the start field value
    FieldPath path;
    if ( ! rhs.empty()) {
        for (auto it = rhs.begin() + 1; it != rhs.end(); ++it) {
            path.push_back(std::make_unique<document::FieldPathEntry>(**it));
        }
    }
    return path;
}

void
DocsumFilter::prepareFieldSpec(DocsumFieldSpec & spec, const DocsumTools::FieldSpec & toolsSpec,
                               const FieldMap & fieldMap, const FieldPathMapT & fieldPathMap)
{
    { // setup output field
        const vespalib::string & name = toolsSpec.getOutputName();
        LOG(debug, "prepareFieldSpec: output field name '%s'", name.c_str());
        FieldIdT field = fieldMap.fieldNo(name);
        if (field != FieldMap::npos) {
            if (field < fieldPathMap.size()) {
                spec.setOutputField(DocsumFieldSpec::FieldIdentifier(field, copyPathButFirst(fieldPathMap[field])));
            } else {
                LOG(warning, "Could not find a field path for field '%s' with id '%d'", name.c_str(), field);
                spec.setOutputField(DocsumFieldSpec::FieldIdentifier(field, FieldPath()));
            }
        } else {
            LOG(warning, "Could not find output summary field '%s'", name.c_str());
        }
    }
    // setup input fields
    for (size_t i = 0; i < toolsSpec.getInputNames().size(); ++i) {
        const vespalib::string & name = toolsSpec.getInputNames()[i];
        LOG(debug, "prepareFieldSpec: input field name '%s'", name.c_str());
        FieldIdT field = fieldMap.fieldNo(name);
        if (field != FieldMap::npos) {
            if (field < fieldPathMap.size()) {
                LOG(debug, "field %u < map size %zu", field, fieldPathMap.size());
                spec.getInputFields().push_back(DocsumFieldSpec::FieldIdentifier(field, copyPathButFirst(fieldPathMap[field])));
            } else {
                LOG(warning, "Could not find a field path for field '%s' with id '%d'", name.c_str(), field);
                spec.getInputFields().push_back(DocsumFieldSpec::FieldIdentifier(field, FieldPath()));
            }
            if (_highestFieldNo <= field) {
                _highestFieldNo = field + 1;
            }
        } else {
            LOG(warning, "Could not find input summary field '%s'", name.c_str());
        }
    }
}

const document::FieldValue *
DocsumFilter::getFieldValue(const DocsumFieldSpec::FieldIdentifier & fieldId,
                            VsmsummaryConfig::Fieldmap::Command command,
                            const Document & docsum, bool & modified)
{
    FieldIdT fId = fieldId.getId();
    const document::FieldValue * fv = docsum.getField(fId);
    if (fv == nullptr) {
        return nullptr;
    }
    switch (command) {
    case VsmsummaryConfig::Fieldmap::Command::FLATTENJUNIPER:
        if (_snippetModifiers != nullptr) {
            FieldModifier * mod = _snippetModifiers->getModifier(fId);
            if (mod != nullptr) {
                _cachedValue = mod->modify(*fv, fieldId.getPath());
                modified = true;
                return _cachedValue.get();
            }
        }
        [[fallthrough]];
    default:
        return fv;
    }
}


DocsumFilter::DocsumFilter(const DocsumToolsPtr &tools, const IDocSumCache & docsumCache) :
    _docsumCache(&docsumCache),
    _tools(tools),
    _fields(),
    _highestFieldNo(0),
    _flattenWriter(),
    _snippetModifiers(nullptr),
    _cachedValue(),
    _emptyFieldPath()
{ }

DocsumFilter::~DocsumFilter() =default;

void DocsumFilter::init(const FieldMap & fieldMap, const FieldPathMapT & fieldPathMap)
{
    if (_tools.get()) {
        const ResultClass *resClass = _tools->getResultClass();
        const std::vector<DocsumTools::FieldSpec> & inputSpecs = _tools->getFieldSpecs();
        if (resClass != nullptr) {
            uint32_t entryCnt = resClass->GetNumEntries();
            assert(entryCnt == inputSpecs.size());
            for (uint32_t i = 0; i < entryCnt; ++i) {
                const ResConfigEntry &entry = *resClass->GetEntry(i);
                const DocsumTools::FieldSpec & toolsSpec = inputSpecs[i];
                _fields.push_back(DocsumFieldSpec(entry._type, toolsSpec.getCommand()));
                LOG(debug, "About to prepare field spec for summary field '%s'", entry._name.c_str());
                prepareFieldSpec(_fields.back(), toolsSpec, fieldMap, fieldPathMap);
            }
            assert(entryCnt == _fields.size());
        }
    }
}

uint32_t
DocsumFilter::getNumDocs() const
{
    return std::numeric_limits<uint32_t>::max();
}

bool
DocsumFilter::write_flatten_field(const DocsumFieldSpec& field_spec, const Document& doc)
{
    if (field_spec.getCommand() == VsmsummaryConfig::Fieldmap::Command::NONE) {
        LOG(debug, "write_flatten_field: Cannot handle command NONE");
        return false;
    }

    if (field_spec.getResultType() != RES_LONG_STRING && field_spec.getResultType() != RES_STRING) {
        LOG(debug, "write_flatten_field: Can only handle result types STRING and LONG_STRING");
        return false;
    }
    switch (field_spec.getCommand()) {
    case VsmsummaryConfig::Fieldmap::Command::FLATTENJUNIPER:
        _flattenWriter.setSeparator(juniper::separators::record_separator_string);
        break;
    default:
        break;
    }
    const DocsumFieldSpec::FieldIdentifierVector & inputFields = field_spec.getInputFields();
    for (size_t i = 0; i < inputFields.size(); ++i) {
        const DocsumFieldSpec::FieldIdentifier & fieldId = inputFields[i];
        bool modified = false;
        const document::FieldValue * fv = getFieldValue(fieldId, field_spec.getCommand(), doc, modified);
        if (fv != nullptr) {
            LOG(debug, "write_flatten_field: About to flatten field '%d' with field value (%s) '%s'",
                fieldId.getId(), modified ? "modified" : "original", fv->toString().c_str());
            if (modified) {
                fv->iterateNested(_emptyFieldPath, _flattenWriter);
            } else {
                fv->iterateNested(fieldId.getPath(), _flattenWriter);
            }
        } else {
            LOG(debug, "write_flatten_field: Field value not set for field '%d'", fieldId.getId());
        }
    }
    return true;
}

std::unique_ptr<const IDocsumStoreDocument>
DocsumFilter::getMappedDocsum(uint32_t id)
{
    const ResultClass *resClass = _tools->getResultClass();
    if (resClass == nullptr) {
        return {};
    }

    const Document & doc = _docsumCache->getDocSum(id);

    return std::make_unique<DocsumStoreVsmDocument>(*this, doc);
}

search::docsummary::DocsumStoreFieldValue
DocsumFilter::get_struct_or_multivalue_summary_field(const DocsumFieldSpec& field_spec, const Document& doc)
{
    // Filtering not yet implemented, return whole struct or multivalue field
    const DocsumFieldSpec::FieldIdentifier & fieldId = field_spec.getOutputField();
    const document::FieldValue* field_value = doc.getField(fieldId.getId());
    return DocsumStoreFieldValue(field_value);
}

search::docsummary::DocsumStoreFieldValue
DocsumFilter::get_flattened_summary_field(const DocsumFieldSpec& field_spec, const Document& doc)
{
    if (!write_flatten_field(field_spec, doc)) {
        return {};
    }
    const CharBuffer& buf = _flattenWriter.getResult();
    auto value = document::StringFieldValue::make(vespalib::stringref(buf.getBuffer(), buf.getPos()));
    _flattenWriter.clear();
    return DocsumStoreFieldValue(std::move(value));
}

search::docsummary::DocsumStoreFieldValue
DocsumFilter::get_summary_field(uint32_t entry_idx, const Document& doc)
{
    const auto& field_spec = _fields[entry_idx];
    ResType type = field_spec.getResultType();
    if (type == RES_JSONSTRING) {
        return get_struct_or_multivalue_summary_field(field_spec, doc);
    } else {
        if (field_spec.getInputFields().size() == 1 && field_spec.getCommand() == VsmsummaryConfig::Fieldmap::Command::NONE) {
            const DocsumFieldSpec::FieldIdentifier & fieldId = field_spec.getInputFields()[0];
            const document::FieldValue* field_value = doc.getField(fieldId.getId());
            return DocsumStoreFieldValue(field_value);
        } else if (field_spec.getInputFields().size() == 0 && field_spec.getCommand() == VsmsummaryConfig::Fieldmap::Command::NONE) {
            return {};
        } else {
            return get_flattened_summary_field(field_spec, doc);
        }
    }
}

void
DocsumFilter::insert_struct_or_multivalue_summary_field(const DocsumFieldSpec& field_spec, const Document& doc, vespalib::slime::Inserter& inserter)
{
    if (field_spec.getCommand() != VsmsummaryConfig::Fieldmap::Command::NONE) {
        return;
    }
    const DocsumFieldSpec::FieldIdentifier& fieldId = field_spec.getOutputField();
    const document::FieldValue* fv = doc.getField(fieldId.getId());
    if (fv == nullptr) {
        return;
    }
    CheckUndefinedValueVisitor check_undefined;
    fv->accept(check_undefined);
    if (!check_undefined.is_undefined()) {
        SlimeFieldWriter writer;
        if (! field_spec.hasIdentityMapping()) {
            writer.setInputFields(field_spec.getInputFields());
        }
        writer.insert(*fv, inserter);
    }
}

void
DocsumFilter::insert_flattened_summary_field(const DocsumFieldSpec& field_spec, const Document& doc, vespalib::slime::Inserter& inserter)
{
    if (!write_flatten_field(field_spec, doc)) {
        return;
    }
    const CharBuffer& buf = _flattenWriter.getResult();
    inserter.insertString(vespalib::Memory(buf.getBuffer(), buf.getPos()));
    _flattenWriter.clear();
}

void
DocsumFilter::insert_summary_field(uint32_t entry_idx, const Document& doc, vespalib::slime::Inserter& inserter)
{
    const auto& field_spec = _fields[entry_idx];
    ResType type = field_spec.getResultType();
    if (type == RES_JSONSTRING) {
        insert_struct_or_multivalue_summary_field(field_spec, doc, inserter);
    } else {
        if (field_spec.getInputFields().size() == 1 && field_spec.getCommand() == VsmsummaryConfig::Fieldmap::Command::NONE) {
            const DocsumFieldSpec::FieldIdentifier & fieldId = field_spec.getInputFields()[0];
            const document::FieldValue* field_value = doc.getField(fieldId.getId());
            if (field_value != nullptr) {
                SummaryFieldConverter::insert_summary_field(*field_value, inserter);
            }
        } else if (field_spec.getInputFields().size() == 0 && field_spec.getCommand() == VsmsummaryConfig::Fieldmap::Command::NONE) {
        } else {
            insert_flattened_summary_field(field_spec, doc, inserter);
        }
    }
}

}
