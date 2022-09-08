// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#include "slimefieldwriter.h"
#include <vespa/vespalib/stllike/asciistream.h>
#include <vespa/vespalib/util/size_literals.h>
#include <vespa/searchsummary/docsummary/resultconfig.h>
#include <vespa/document/datatype/positiondatatype.h>
#include <vespa/vespalib/data/slime/slime.h>

#include <vespa/log/log.h>
LOG_SETUP(".vsm.slimefieldwriter");

namespace {

vespalib::string
toString(const std::vector<vespalib::string> & fieldPath)
{
    vespalib::asciistream oss;
    for (size_t i = 0; i < fieldPath.size(); ++i) {
        if (i > 0) {
            oss << ".";
        }
        oss << fieldPath[i];
    }
    return oss.str();
}

} // namespace <unnamed>

using namespace vespalib::slime::convenience;

namespace vsm {

void
SlimeFieldWriter::traverseRecursive(const document::FieldValue & fv, Inserter &inserter)
{
    LOG(debug, "traverseRecursive: class(%s), fieldValue(%s), currentPath(%s)",
        fv.className(), fv.toString().c_str(), toString(_currPath).c_str());

    if (fv.isCollection()) {
        const auto & cfv = static_cast<const document::CollectionFieldValue &>(fv);
        if (cfv.isA(document::FieldValue::Type::ARRAY)) {
            const auto & afv = static_cast<const document::ArrayFieldValue &>(cfv);
            Cursor &a = inserter.insertArray();
            for (const auto & nfv : afv) {
                ArrayInserter ai(a);
                traverseRecursive(nfv, ai);
            }
        } else {
            assert(cfv.isA(document::FieldValue::Type::WSET));
            const auto & wsfv = static_cast<const document::WeightedSetFieldValue &>(cfv);
            Cursor &a = inserter.insertArray();
            Symbol isym = a.resolve("item");
            Symbol wsym = a.resolve("weight");
            for (const auto &entry : wsfv) {
                Cursor &o = a.addObject();
                const document::FieldValue & nfv = *entry.first;
                ObjectSymbolInserter oi(o, isym);
                traverseRecursive(nfv, oi);
                int weight = static_cast<const document::IntFieldValue &>(*entry.second).getValue();
                o.setLong(wsym, weight);
            }
        }
    } else if (fv.isA(document::FieldValue::Type::MAP)) {
        const auto & mfv = static_cast<const document::MapFieldValue &>(fv);
        Cursor &a = inserter.insertArray();
        Symbol keysym = a.resolve("key");
        Symbol valsym = a.resolve("value");
        for (const auto &entry : mfv) {
            Cursor &o = a.addObject();
            ObjectSymbolInserter ki(o, keysym);
            traverseRecursive(*entry.first, ki);
            _currPath.emplace_back("value");
            ObjectSymbolInserter vi(o, valsym);
            traverseRecursive(*entry.second, vi);
            _currPath.pop_back();
        }
    } else if (fv.isStructured()) {
        const auto & sfv = static_cast<const document::StructuredFieldValue &>(fv);
        Cursor &o = inserter.insertObject();
        if (sfv.getDataType() == &document::PositionDataType::getInstance()
            && search::docsummary::ResultConfig::wantedV8geoPositions())
        {
            bool ok = true;
            try {
                int x = std::numeric_limits<int>::min();
                int y = std::numeric_limits<int>::min();
                for (const document::Field & entry : sfv) {
                    document::FieldValue::UP fval(sfv.getValue(entry));
                    if (entry.getName() == "x") {
                        x = fval->getAsInt();
                    } else if (entry.getName() == "y") {
                        y = fval->getAsInt();
                    } else {
                        ok = false;
                    }
                }
                if (x == std::numeric_limits<int>::min()) ok = false;
                if (y == std::numeric_limits<int>::min()) ok = false;
                if (ok) {
                    o.setDouble("lat", double(y) / 1.0e6);
                    o.setDouble("lng", double(x) / 1.0e6);
                    return;
                }
            } catch (std::exception &e) {
                (void)e;
                // fallback to code below
            }
        }
        for (const document::Field & entry : sfv) {
            if (explorePath(entry.getName())) {
                _currPath.push_back(entry.getName());
                Memory keymem(entry.getName());
                ObjectInserter oi(o, keymem);
                document::FieldValue::UP fval(sfv.getValue(entry));
                traverseRecursive(*fval, oi);
                _currPath.pop_back();
            }
        }
    } else {
        if (fv.isLiteral()) {
            const auto & lfv = static_cast<const document::LiteralFieldValueB &>(fv);
            inserter.insertString(lfv.getValueRef());
        } else if (fv.isNumeric()) {
            switch (fv.getDataType()->getId()) {
            case document::DataType::T_BYTE:
            case document::DataType::T_SHORT:
            case document::DataType::T_INT:
            case document::DataType::T_LONG:
                inserter.insertLong(fv.getAsLong());
                break;
            case document::DataType::T_DOUBLE:
                inserter.insertDouble(fv.getAsDouble());
                break;
            case document::DataType::T_FLOAT:
                inserter.insertDouble(fv.getAsFloat());
                break;
            default:
                inserter.insertString(fv.getAsString());
            }
        } else if (fv.isA(document::FieldValue::Type::BOOL)) {
            const auto & bfv = static_cast<const document::BoolFieldValue &>(fv);
            inserter.insertBool(bfv.getValue());
        } else {
            inserter.insertString(fv.toString());
        }
    }
}

bool
SlimeFieldWriter::explorePath(vespalib::stringref candidate)
{
    if (_inputFields == nullptr) {
        return true;
    }
    // find out if we should explore the current path
    for (const auto & field : *_inputFields) {
        const FieldPath & fp = field.getPath();
        if (_currPath.size() <= fp.size()) {
            bool equal = true;
            for (size_t j = 0; j < _currPath.size() && equal; ++j) {
                equal = (fp[j].getName() == _currPath[j]);
            }
            if (equal) {
                if (_currPath.size() == fp.size()) {
                    return true;
                } else if (fp[_currPath.size()].getName() == candidate) {
                    // the current path matches one of the input field paths
                    return true;
                }
            }
        }
    }
    return false;
}

SlimeFieldWriter::SlimeFieldWriter() :
    _inputFields(nullptr),
    _currPath()
{
}

SlimeFieldWriter::~SlimeFieldWriter() = default;

void
SlimeFieldWriter::insert(const document::FieldValue & fv, vespalib::slime::Inserter& inserter)
{
    traverseRecursive(fv, inserter);
}

}
