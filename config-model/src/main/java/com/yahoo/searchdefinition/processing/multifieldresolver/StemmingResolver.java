// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.searchdefinition.processing.multifieldresolver;

import com.yahoo.config.application.api.DeployLogger;
import com.yahoo.searchdefinition.Search;
import com.yahoo.searchdefinition.document.SDField;
import com.yahoo.searchdefinition.document.Stemming;

import java.util.List;
import java.util.logging.Level;

/**
 * Class resolving conflicts when fields with different stemming-settings are
 * combined into the same index
 */
public class StemmingResolver extends MultiFieldResolver {

    public StemmingResolver(String indexName, List<SDField> fields, Search search, DeployLogger logger) {
        super(indexName, fields, search, logger);
    }

    @Override
    public void resolve() {
        checkStemmingForIndexFields(indexName, fields);
    }

    private void checkStemmingForIndexFields(String indexName, List<SDField> fields) {
        Stemming stemming = null;
        SDField stemmingField = null;
        for (SDField field : fields) {
            if (stemming == null && stemmingField==null) {
                stemming = field.getStemming(search);
                stemmingField = field;
            } else if (stemming != field.getStemming(search)) {
                deployLogger.logApplicationPackage(Level.WARNING, "Field '" + field.getName() + "' has " + field.getStemming(search) +
                        ", whereas field '" + stemmingField.getName() + "' has " + stemming +
                        ". All fields indexing to the index '" + indexName  + "' must have the same stemming." +
                        " This should be corrected as it will make indexing fail in a few cases.");
            }
        }
    }

}
