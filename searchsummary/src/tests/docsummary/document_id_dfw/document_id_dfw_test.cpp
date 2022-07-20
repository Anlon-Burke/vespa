// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#include <vespa/document/base/documentid.h>
#include <vespa/document/datatype/documenttype.h>
#include <vespa/document/fieldvalue/document.h>
#include <vespa/document/repo/configbuilder.h>
#include <vespa/document/repo/documenttyperepo.h>
#include <vespa/searchsummary/docsummary/docsum_blob_entry_filter.h>
#include <vespa/searchsummary/docsummary/docsum_store_document.h>
#include <vespa/searchsummary/docsummary/document_id_dfw.h>
#include <vespa/searchsummary/docsummary/general_result.h>
#include <vespa/searchsummary/docsummary/resultconfig.h>
#include <vespa/searchsummary/docsummary/resultpacker.h>
#include <vespa/vespalib/data/slime/slime.h>
#include <vespa/vespalib/gtest/gtest.h>
#include <iostream>
#include <memory>

using document::Document;
using document::DocumentId;
using document::DocumentType;
using document::DocumentTypeRepo;
using document::config_builder::DocumenttypesConfigBuilderHelper;
using document::config_builder::Struct;
using search::docsummary::DocsumBlobEntryFilter;
using search::docsummary::DocsumStoreDocument;
using search::docsummary::DocsumStoreValue;
using search::docsummary::DocumentIdDFW;
using search::docsummary::IDocsumStoreDocument;
using search::docsummary::ResultClass;
using search::docsummary::ResultConfig;
using search::docsummary::ResultPacker;
using search::docsummary::GeneralResult;
using vespalib::Slime;
using vespalib::slime::Cursor;
using vespalib::slime::ObjectInserter;
using vespalib::slime::SlimeInserter;

namespace {

const int32_t          doc_type_id   = 787121340;
const vespalib::string doc_type_name = "test";
const vespalib::string header_name   = doc_type_name + ".header";
const vespalib::string body_name     = doc_type_name + ".body";


std::unique_ptr<const DocumentTypeRepo>
make_doc_type_repo()
{
    DocumenttypesConfigBuilderHelper builder;
    builder.document(doc_type_id, doc_type_name,
                     Struct(header_name), Struct(body_name));
    return std::unique_ptr<const DocumentTypeRepo>(new DocumentTypeRepo(builder.config()));
}

class DocumentIdDFWTest : public ::testing::Test
{
    vespalib::string                        _field_name;
    vespalib::Memory                        _field_name_view;
    DocsumBlobEntryFilter                   _docsum_blob_entry_filter;
    std::unique_ptr<ResultConfig>           _result_config;
    std::unique_ptr<ResultPacker>           _packer; // owns docsum blob
    std::unique_ptr<const DocumentTypeRepo> _repo;
    const DocumentType*                     _document_type;

protected:
    DocumentIdDFWTest();
    ~DocumentIdDFWTest() override;

    std::unique_ptr<DocsumStoreDocument> make_docsum_store_document(const vespalib::string &id);
    std::unique_ptr<DocsumStoreValue> make_docsum_store_value(std::unique_ptr<DocsumStoreDocument> doc);
    vespalib::Slime write(const DocsumStoreValue& value);
    vespalib::Memory get_field_name_view() const noexcept { return _field_name_view; }
};

DocumentIdDFWTest::DocumentIdDFWTest()
    : testing::Test(),
      _field_name("documentid"),
      _field_name_view(_field_name.data(), _field_name.size()),
      _docsum_blob_entry_filter(DocsumBlobEntryFilter().add_skip(search::docsummary::RES_LONG_STRING)),
      _result_config(std::make_unique<ResultConfig>(_docsum_blob_entry_filter)),
      _packer(std::make_unique<ResultPacker>(_result_config.get())),
      _repo(make_doc_type_repo()),
      _document_type(_repo->getDocumentType(doc_type_name))
{
    auto* cfg = _result_config->AddResultClass("default", 0);
    cfg->AddConfigEntry(_field_name.c_str(), search::docsummary::RES_LONG_STRING);
    _result_config->CreateEnumMaps();
}


DocumentIdDFWTest::~DocumentIdDFWTest() = default;


std::unique_ptr<DocsumStoreDocument>
DocumentIdDFWTest::make_docsum_store_document(const vespalib::string& id)
{
    auto doc = std::make_unique<Document>(*_document_type, DocumentId(id));
    doc->setRepo(*_repo);
    return std::make_unique<DocsumStoreDocument>(std::move(doc));
}

std::unique_ptr<DocsumStoreValue>
DocumentIdDFWTest::make_docsum_store_value(std::unique_ptr<DocsumStoreDocument> doc)
{
    EXPECT_TRUE(_packer->Init(0));
    const char *ptr = nullptr;
    uint32_t len = 0;
    EXPECT_TRUE(_packer->GetDocsumBlob(&ptr, &len));
    return std::make_unique<DocsumStoreValue>(ptr, len, std::move(doc));
}

vespalib::Slime
DocumentIdDFWTest::write(const DocsumStoreValue& value)
{
    auto result = std::make_unique<GeneralResult>(_result_config->LookupResultClass(0));
    EXPECT_TRUE(result->inplaceUnpack(value));
    Slime slime;
    SlimeInserter top_inserter(slime);
    Cursor & docsum = top_inserter.insertObject();
    ObjectInserter field_inserter(docsum, _field_name_view);
    DocumentIdDFW writer;
    writer.insertField(0, result.get(), nullptr, search::docsummary::RES_LONG_STRING, field_inserter);
    return slime;
}

TEST_F(DocumentIdDFWTest, insert_document_id)
{
    vespalib::string id("id::test::0");
    auto doc = make_docsum_store_document(id);
    auto dsvalue = make_docsum_store_value(std::move(doc));
    auto slime = write(*dsvalue);
    EXPECT_TRUE(slime.get()[get_field_name_view()].valid());
    EXPECT_EQ(id, slime.get()[get_field_name_view()].asString().make_string());
}

TEST_F(DocumentIdDFWTest, insert_document_id_no_document_doc)
{
    auto dsvalue = make_docsum_store_value(std::make_unique<DocsumStoreDocument>(std::unique_ptr<Document>()));
    auto slime = write(*dsvalue);
    EXPECT_FALSE(slime.get()[get_field_name_view()].valid());
}

TEST_F(DocumentIdDFWTest, insert_document_id_no_docsum_store_doc)
{
    auto dsvalue = make_docsum_store_value({});
    auto slime = write(*dsvalue);
    EXPECT_FALSE(slime.get()[get_field_name_view()].valid());
}

}

GTEST_MAIN_RUN_ALL_TESTS()
