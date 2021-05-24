// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.searchdefinition.processing;

import com.yahoo.config.application.api.DeployLogger;
import com.yahoo.document.CollectionDataType;
import com.yahoo.document.DataType;
import com.yahoo.searchdefinition.RankProfileRegistry;
import com.yahoo.searchdefinition.Search;
import com.yahoo.searchdefinition.document.Matching;
import com.yahoo.searchdefinition.document.SDField;
import com.yahoo.searchdefinition.document.Stemming;
import com.yahoo.vespa.indexinglanguage.ExpressionSearcher;
import com.yahoo.vespa.indexinglanguage.expressions.ExactExpression;
import com.yahoo.vespa.indexinglanguage.expressions.Expression;
import com.yahoo.vespa.indexinglanguage.expressions.ForEachExpression;
import com.yahoo.vespa.indexinglanguage.expressions.IndexExpression;
import com.yahoo.vespa.indexinglanguage.expressions.OutputExpression;
import com.yahoo.vespa.indexinglanguage.expressions.ScriptExpression;
import com.yahoo.vespa.model.container.search.QueryProfiles;

/**
 * The implementation of exact matching
 *
 * @author bratseth
 */
public class ExactMatch extends Processor {

    public static final String DEFAULT_EXACT_TERMINATOR = "@@";

    ExactMatch(Search search, DeployLogger deployLogger, RankProfileRegistry rankProfileRegistry, QueryProfiles queryProfiles) {
        super(search, deployLogger, rankProfileRegistry, queryProfiles);
    }

    @Override
    public void process(boolean validate, boolean documentsOnly) {
        for (SDField field : search.allConcreteFields()) {
            processField(field, search);
        }
    }

    private void processField(SDField field, Search search) {
        Matching.Type matching = field.getMatching().getType();
        if (matching.equals(Matching.Type.EXACT) || matching.equals(Matching.Type.WORD)) {
            implementExactMatch(field, search);
        } else if (field.getMatching().getExactMatchTerminator() != null) {
            warn(search, field, "exact-terminator requires 'exact' matching to have any effect.");
        }
        for (var structField : field.getStructFields()) {
            processField(structField, search);
        }
    }

    private void implementExactMatch(SDField field, Search search) {
        field.setStemming(Stemming.NONE);
        field.getNormalizing().inferLowercase();

        if (field.getMatching().getType().equals(Matching.Type.WORD)) {
            field.addQueryCommand("word");
        } else { // exact
            String exactTerminator = DEFAULT_EXACT_TERMINATOR;
            if (field.getMatching().getExactMatchTerminator() != null
                && ! field.getMatching().getExactMatchTerminator().equals("")) {
                exactTerminator = field.getMatching().getExactMatchTerminator();
            } else {
                info(search, field,
                     "With 'exact' matching, an exact-terminator is needed," +
                     " using default value '" + exactTerminator +"' as terminator");
            }
            field.addQueryCommand("exact " + exactTerminator);

            // The following part illustrates how nice it would have been with canonical representation of indices
            if (field.doesIndexing()) {
                exactMatchSettingsForField(field);
            }
        }
        ScriptExpression script = field.getIndexingScript();
        if (new ExpressionSearcher<>(IndexExpression.class).containedIn(script)) {
            field.setIndexingScript((ScriptExpression)new MyProvider(search).convert(field.getIndexingScript()));
        }
    }

    private void exactMatchSettingsForField(SDField field) {
        field.getRanking().setFilter(true);
    }

    private static class MyProvider extends TypedTransformProvider {

        MyProvider(Search search) {
            super(ExactExpression.class, search);
        }

        @Override
        protected boolean requiresTransform(Expression exp, DataType fieldType) {
            return exp instanceof OutputExpression;
        }

        @Override
        protected Expression newTransform(DataType fieldType) {
            Expression exp = new ExactExpression();
            if (fieldType instanceof CollectionDataType) {
                exp = new ForEachExpression(exp);
            }
            return exp;
        }

    }

}

