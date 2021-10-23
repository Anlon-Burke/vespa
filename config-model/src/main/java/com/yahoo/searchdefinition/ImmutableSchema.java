// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.searchdefinition;

import com.yahoo.config.application.api.ApplicationPackage;
import com.yahoo.config.application.api.DeployLogger;
import com.yahoo.config.model.api.ModelContext;
import com.yahoo.searchdefinition.document.ImmutableSDField;
import com.yahoo.searchdefinition.document.SDDocumentType;
import com.yahoo.searchdefinition.document.SDField;
import com.yahoo.vespa.documentmodel.SummaryField;

import java.io.Reader;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * An interface containing the non-mutating methods of {@link Schema}.
 * For description of the methods see {@link Schema}.
 *
 * @author bjorncs
 */
public interface ImmutableSchema {

    String getName();
    Optional<? extends ImmutableSchema> inherited();
    Index getIndex(String name);
    ImmutableSDField getConcreteField(String name);
    //TODO split in mutating/immutable by returning List<ImmutableSDField>
    List<SDField> allConcreteFields();
    List<Index> getExplicitIndices();
    Reader getRankingExpression(String fileName);
    ApplicationPackage applicationPackage();
    DeployLogger getDeployLogger();
    ModelContext.Properties getDeployProperties();
    RankingConstants rankingConstants();
    LargeRankExpressions rankExpressionFiles();
    OnnxModels onnxModels();
    Stream<ImmutableSDField> allImportedFields();
    SDDocumentType getDocument();
    ImmutableSDField getField(String name);

    default Stream<ImmutableSDField> allFields() {
        return allFieldsList().stream();
    }
    List<ImmutableSDField> allFieldsList();

    Map<String, SummaryField> getSummaryFields(ImmutableSDField field);

}
