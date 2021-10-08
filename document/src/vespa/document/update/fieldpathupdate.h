// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
#pragma once

#include "updatevisitor.h"
#include <vespa/document/base/fieldpath.h>
#include <vespa/document/util/printable.h>

namespace document {

class DocumentTypeRepo;
class Field;
class FieldValue;
class BucketIdFactory;
class Document;
class DataType;

namespace select { class Node; }
namespace fieldvalue { class IteratorHandler; }

class FieldPathUpdate : public vespalib::Cloneable,
                        public Printable,
                        public vespalib::Identifiable
{
protected:
    using nbostream = vespalib::nbostream;
    using stringref = vespalib::stringref;
    /** To be used for deserialization */
    FieldPathUpdate();
    FieldPathUpdate(const FieldPathUpdate &);
    FieldPathUpdate & operator =(const FieldPathUpdate &);

   static stringref getString(nbostream & stream);
public:
    using SP = std::shared_ptr<FieldPathUpdate>;
    using CP = vespalib::CloneablePtr<FieldPathUpdate>;

    ~FieldPathUpdate() override;

    enum FieldPathUpdateType {
        Add    = IDENTIFIABLE_CLASSID(AddFieldPathUpdate),
        Assign = IDENTIFIABLE_CLASSID(AssignFieldPathUpdate),
        Remove = IDENTIFIABLE_CLASSID(RemoveFieldPathUpdate)
    };

    void applyTo(Document& doc) const;

    FieldPathUpdate* clone() const override = 0;

    virtual bool operator==(const FieldPathUpdate& other) const;
    bool operator!=(const FieldPathUpdate& other) const {
        return ! (*this == other);
    }

    const vespalib::string& getOriginalFieldPath() const { return _originalFieldPath; }
    const vespalib::string& getOriginalWhereClause() const { return _originalWhereClause; }

    /**
     * Check that a given field value is of the type inferred by
     * the field path.
     * @throws IllegalArgumentException upon datatype mismatch.
     */
    void checkCompatibility(const FieldValue& fv, const DataType & type) const;

    void print(std::ostream& out, bool verbose, const std::string& indent) const override;

    DECLARE_IDENTIFIABLE_ABSTRACT(FieldPathUpdate);

    virtual void accept(UpdateVisitor &visitor) const = 0;
    virtual uint8_t getSerializedType() const = 0;

    /** Deserializes and creates a new FieldPathUpdate instance.
     * Requires type id to be not yet consumed.
     */
    static std::unique_ptr<FieldPathUpdate> createInstance(const DocumentTypeRepo& repo, const DataType &type, nbostream & stream);

protected:
    FieldPathUpdate(stringref fieldPath, stringref whereClause = stringref());

    virtual void deserialize(const DocumentTypeRepo& repo, const DataType& type, nbostream & stream);

    /** @return the datatype of the last path element in the field path */
    const DataType& getResultingDataType(const FieldPath & path) const;
    enum SerializedMagic {AssignMagic=0, RemoveMagic=1, AddMagic=2};
private:
    // TODO: rename to createIteratorHandler?
    virtual std::unique_ptr<fieldvalue::IteratorHandler> getIteratorHandler(Document& doc, const DocumentTypeRepo & repo) const = 0;

    vespalib::string _originalFieldPath;
    vespalib::string _originalWhereClause;
};

}
