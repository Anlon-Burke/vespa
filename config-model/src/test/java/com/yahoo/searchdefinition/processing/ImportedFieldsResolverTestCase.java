// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.searchdefinition.processing;

import com.yahoo.document.DataType;
import com.yahoo.document.TensorDataType;
import com.yahoo.searchdefinition.Search;
import com.yahoo.searchdefinition.document.ImmutableImportedSDField;
import com.yahoo.searchdefinition.document.ImmutableSDField;
import com.yahoo.searchdefinition.document.ImportedField;
import com.yahoo.searchdefinition.document.ImportedFields;
import com.yahoo.searchdefinition.document.SDField;
import com.yahoo.searchdefinition.document.TemporarySDField;
import com.yahoo.tensor.TensorType;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;

/**
 * @author geirst
 */
public class ImportedFieldsResolverTestCase {

    @Rule
    public final ExpectedException exceptionRule = ExpectedException.none();

    private void resolve_imported_field(String fieldName, String targetFieldName) {
        SearchModel model = new SearchModel();
        model.addImportedField(fieldName, "ref", targetFieldName).resolve();

        assertEquals(1, model.importedFields.fields().size());
        ImportedField myField = model.importedFields.fields().get(fieldName);
        assertNotNull(myField);
        assertEquals(fieldName, myField.fieldName());
        assertSame(model.childSearch.getConcreteField("ref"), myField.reference().referenceField());
        assertSame(model.parentSearch, myField.reference().targetSearch());
        ImmutableSDField targetField = model.parentSearch.getField(targetFieldName);
        if (targetField instanceof SDField) {
            assertSame(targetField, myField.targetField());
        } else {
            assertSame(getImportedField(targetField), getImportedField(myField.targetField()));
        }
    }

    private static ImportedField getImportedField(ImmutableSDField field) {
        return ((ImmutableImportedSDField) field).getImportedField();
    }

    @Test
    public void valid_imported_fields_are_resolved() {
        resolve_imported_field("my_attribute_field", "attribute_field");
        resolve_imported_field("my_tensor_field", "tensor_field");
        resolve_imported_field("my_ancient_field", "ancient_field");
    }

    @Test
    public void resolver_fails_if_document_reference_is_not_found() {
        exceptionRule.expect(IllegalArgumentException.class);
        exceptionRule.expectMessage("For search 'child', import field 'my_attribute_field': "
                + "Reference field 'not_ref' not found");
        new SearchModel().addImportedField("my_attribute_field", "not_ref", "budget").resolve();
    }

    @Test
    public void resolver_fails_if_referenced_field_is_not_found() {
        exceptionRule.expect(IllegalArgumentException.class);
        exceptionRule.expectMessage("For search 'child', import field 'my_attribute_field': "
                + "Field 'not_existing' via reference field 'ref': Not found");
        new SearchModel().addImportedField("my_attribute_field", "ref", "not_existing").resolve();
    }

    @Test
    public void resolver_fails_if_imported_field_is_not_an_attribute() {
        exceptionRule.expect(IllegalArgumentException.class);
        exceptionRule.expectMessage("For search 'child', import field 'my_not_attribute': "
                + "Field 'not_attribute' via reference field 'ref': Is not an attribute field. Only attribute fields supported");
        new SearchModel().addImportedField("my_not_attribute", "ref", "not_attribute").resolve();
    }

    @Test
    public void resolver_fails_if_imported_field_is_indexing() {
        exceptionRule.expect(IllegalArgumentException.class);
        exceptionRule.expectMessage(
                "For search 'child', import field 'my_attribute_and_index': " +
                        "Field 'attribute_and_index' via reference field 'ref': Is an index field. Not supported");
        new SearchModel()
                .addImportedField("my_attribute_and_index", "ref", "attribute_and_index")
                .resolve();
    }

    @Test
    public void resolver_fails_if_imported_field_is_of_type_predicate() {
        exceptionRule.expect(IllegalArgumentException.class);
        exceptionRule.expectMessage(
                "For search 'child', import field 'my_predicate_field': " +
                        "Field 'predicate_field' via reference field 'ref': Is of type 'predicate'. Not supported");
        new SearchModel().addImportedField("my_predicate_field", "ref", "predicate_field").resolve();
    }

    static class SearchModel extends ParentChildSearchModel {

        public final Search grandParentSearch;
        public ImportedFields importedFields;

        public SearchModel() {
            super();
            grandParentSearch = createSearch("grandparent");
            grandParentSearch.getDocument().addField(createField("ancient_field", DataType.INT, "{ attribute }"));

            parentSearch.getDocument().addField(createField("attribute_field", DataType.INT, "{ attribute }"));
            parentSearch.getDocument().addField(createField("attribute_and_index", DataType.INT, "{ attribute | index }"));
            parentSearch.getDocument().addField(new TemporarySDField("not_attribute", DataType.INT));
            parentSearch.getDocument().addField(createField("tensor_field", new TensorDataType(TensorType.fromSpec("tensor(x[5])")), "{ attribute }"));
            parentSearch.getDocument().addField(createField("predicate_field", DataType.PREDICATE, "{ attribute }"));
            addRefField(parentSearch, grandParentSearch, "ref");
            addImportedField(parentSearch, "ancient_field", "ref", "ancient_field");

            addRefField(childSearch, parentSearch, "ref");
        }


        protected SearchModel addImportedField(String fieldName, String referenceFieldName, String targetFieldName) {
            return addImportedField(childSearch, fieldName, referenceFieldName, targetFieldName);
        }

        protected SearchModel addImportedField(Search search, String fieldName, String referenceFieldName, String targetFieldName) {
            super.addImportedField(search, fieldName, referenceFieldName, targetFieldName);
            return this;
        }

        public void resolve() {
            resolve(grandParentSearch);
            resolve(parentSearch);
            importedFields = resolve(childSearch);
        }

        private static ImportedFields resolve(Search search) {
            assertNotNull(search.temporaryImportedFields().get());
            assertFalse(search.importedFields().isPresent());
            new ImportedFieldsResolver(search, null, null, null).process(true, false);
            assertFalse(search.temporaryImportedFields().isPresent());
            assertNotNull(search.importedFields().get());
            return search.importedFields().get();
        }
    }

}
