// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.searchdefinition.derived;

import com.yahoo.document.DataTypeName;
import com.yahoo.searchdefinition.DocumentReference;
import com.yahoo.searchdefinition.DocumentReferences;
import com.yahoo.searchdefinition.Schema;
import com.yahoo.searchdefinition.document.SDDocumentType;

import java.util.*;

/**
 * <p>A class which can reorder a list of search definitions such that any supertype
 * always preceed any subtype. Subject to this condition the given order
 * is preserved (the minimal reordering is done).</p>
 *
 * <p>This class is <b>not</b> multithread safe. Only one ordering must be done
 * at the time in any instance.</p>
 *
 * @author bratseth
 * @author bjorncs
 */
public class SearchOrderer {

    /** A map from DataTypeName to the Search defining them */
    private final Map<DataTypeName, Schema> documentNameToSearch = new HashMap<>();

    /**
     * Reorders the given list of search definitions such that any supertype
     * always preceed any subtype. Subject to this condition the given order
     * is preserved (the minimal reordering is done).
     *
     * @return a new list containing the same search instances in the right order
     */
    public List<Schema> order(List<Schema> unordered) {
        // Description above state that the original order should be preserved, except for the dependency constraint.
        // Yet we botch that guarantee by sorting the list...
        unordered.sort(Comparator.comparing(Schema::getName));

        // No, this is not a fast algorithm...
        indexOnDocumentName(unordered);
        List<Schema> ordered = new ArrayList<>(unordered.size());
        List<Schema> moveOutwards = new ArrayList<>();
        for (Schema schema : unordered) {
            if (allDependenciesAlreadyEmitted(ordered, schema)) {
                addOrdered(ordered, schema, moveOutwards);
            }
            else {
                moveOutwards.add(schema);
            }
        }

        // Any leftovers means we have search definitions with undefined inheritants.
        // This is warned about elsewhere.
        ordered.addAll(moveOutwards);

        documentNameToSearch.clear();
        return ordered;
    }

    private void addOrdered(List<Schema> ordered, Schema schema, List<Schema> moveOutwards) {
        ordered.add(schema);
        Schema eligibleMove;
        do {
            eligibleMove = removeFirstEntryWithFullyEmittedDependencies(moveOutwards, ordered);
            if (eligibleMove != null) {
                ordered.add(eligibleMove);
            }
        } while (eligibleMove != null);
    }

    /** Removes and returns the first search from the move list which can now be added, or null if none */
    private Schema removeFirstEntryWithFullyEmittedDependencies(List<Schema> moveOutwards, List<Schema> ordered) {
        for (Schema move : moveOutwards) {
            if (allDependenciesAlreadyEmitted(ordered, move)) {
                moveOutwards.remove(move);
                return move;
            }
        }
        return null;
    }

    private boolean allDependenciesAlreadyEmitted(List<Schema> alreadyOrdered, Schema schema) {
        if (schema.getDocument() == null) {
            return true;
        }
        SDDocumentType document = schema.getDocument();
        return allInheritedDependenciesEmitted(alreadyOrdered, document) && allReferenceDependenciesEmitted(alreadyOrdered, document);
    }

    private boolean allInheritedDependenciesEmitted(List<Schema> alreadyOrdered, SDDocumentType document) {
        for (SDDocumentType sdoc : document.getInheritedTypes() ) {
            DataTypeName inheritedName = sdoc.getDocumentName();
            if ("document".equals(inheritedName.getName())) {
                continue;
            }
            Schema inheritedSchema = documentNameToSearch.get(inheritedName);
            if (!alreadyOrdered.contains(inheritedSchema)) {
                return false;
            }
        }
        return true;
    }

    private static boolean allReferenceDependenciesEmitted(List<Schema> alreadyOrdered, SDDocumentType document) {
        DocumentReferences documentReferences = document.getDocumentReferences()
                .orElseThrow(() -> new IllegalStateException("Missing document references. Should have been processed by now."));
        return documentReferences.stream()
                .map(Map.Entry::getValue)
                .map(DocumentReference::targetSearch)
                .allMatch(alreadyOrdered::contains);
    }

    private void indexOnDocumentName(List<Schema> schemas) {
        documentNameToSearch.clear();
        for (Schema schema : schemas) {
            if (schema.getDocument() != null) {
                documentNameToSearch.put(schema.getDocument().getDocumentName(), schema);
            }
        }
    }

}
