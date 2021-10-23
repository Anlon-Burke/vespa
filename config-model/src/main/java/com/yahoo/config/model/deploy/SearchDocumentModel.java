// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.config.model.deploy;

import com.yahoo.searchdefinition.Schema;
import com.yahoo.searchdefinition.SearchBuilder;
import com.yahoo.vespa.documentmodel.DocumentModel;
import com.yahoo.vespa.model.search.NamedSchema;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Internal helper class to retrieve document model and search definitions.
 *
 * @author Ulf Lilleengen
 */
public class SearchDocumentModel {

    private final DocumentModel documentModel;
    private final List<NamedSchema> schemas;

    public SearchDocumentModel(DocumentModel documentModel, List<NamedSchema> schemas) {
        this.documentModel = documentModel;
        this.schemas = schemas;

    }

    public DocumentModel getDocumentModel() {
        return documentModel;
    }

    public List<NamedSchema> getSchemas() {
        return schemas;
    }

    public static SearchDocumentModel fromBuilderAndNames(SearchBuilder builder, Map<String, String> names) {
        List<NamedSchema> ret = new ArrayList<>();
        for (Schema schema : builder.getSearchList()) {
            ret.add(new NamedSchema(names.get(schema.getName()), schema));
        }
        return new SearchDocumentModel(builder.getModel(), ret);
    }

    public static SearchDocumentModel fromBuilder(SearchBuilder builder) {
        List<NamedSchema> ret = new ArrayList<>();
        for (Schema schema : builder.getSearchList()) {
            ret.add(new NamedSchema(schema.getName(), schema));
        }
        return new SearchDocumentModel(builder.getModel(), ret);
    }

}
