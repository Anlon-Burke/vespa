// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#pragma once

#include "docsum_field_writer.h"

namespace search::docsummary {

/*
 * Class for writing document id field.
 */
class DocumentIdDFW : public DocsumFieldWriter
{
private:
public:
    DocumentIdDFW();
    ~DocumentIdDFW() override;
    bool IsGenerated() const override;
    void insertField(uint32_t docid, GeneralResult *gres, GetDocsumsState *state, ResType type, vespalib::slime::Inserter &target) override;
};

}
