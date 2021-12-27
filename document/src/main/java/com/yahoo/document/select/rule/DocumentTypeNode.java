// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.document.select.rule;

import com.yahoo.document.BucketIdFactory;
import com.yahoo.document.DocumentGet;
import com.yahoo.document.DocumentOperation;
import com.yahoo.document.DocumentPut;
import com.yahoo.document.DocumentRemove;
import com.yahoo.document.DocumentType;
import com.yahoo.document.DocumentUpdate;
import com.yahoo.document.select.BucketSet;
import com.yahoo.document.select.Context;
import com.yahoo.document.select.Visitor;

/**
 * A document type node which returns the document type if exactly the type sopecified or false otherwise:
 * For using the exact document type as a condition.
 *
 * @author bratseth
 */
public class DocumentTypeNode implements ExpressionNode {

    private String type;

    public DocumentTypeNode(String type) {
        this.type = type;
    }

    public String getType() {
        return type;
    }

    public DocumentTypeNode setType(String type) {
        this.type = type;
        return this;
    }

    @Override
    public BucketSet getBucketSet(BucketIdFactory factory) {
        return null;
    }

    @Override
    public Object evaluate(Context context) {
        return evaluate(context.getDocumentOperation());
    }

    private Object evaluate(DocumentOperation op) {
        // TODO Vespa 8: Uncomment the following line and remove the legacy one
        // return op.getId().getDocType().equals(type) ? op : false;
        return legacyEvaluate(op);
    }

    private Object legacyEvaluate(DocumentOperation op) {
        DocumentType doct;
        if (op instanceof DocumentPut) {
            doct = ((DocumentPut) op).getDocument().getDataType();
        } else if (op instanceof DocumentUpdate) {
            doct = ((DocumentUpdate) op).getDocumentType();
        } else if (op instanceof DocumentRemove) {
            DocumentRemove removeOp = (DocumentRemove) op;
            return (removeOp.getId().getDocType().equals(type) ? op : Boolean.FALSE);
        } else if (op instanceof DocumentGet) {
            DocumentGet getOp = (DocumentGet) op;
            return (getOp.getId().getDocType().equals(type) ? op : Boolean.FALSE);
        } else {
            throw new IllegalStateException("Document class '" + op.getClass().getName() + "' is not supported.");
        }
        return doct.isA(this.type) ? op : Boolean.FALSE;
    }

    public void accept(Visitor visitor) {
        visitor.visit(this);
    }

    @Override
    public String toString() {
        return type;
    }

}
