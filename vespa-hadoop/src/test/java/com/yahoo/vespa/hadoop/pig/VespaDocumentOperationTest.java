// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hadoop.pig;

import org.apache.pig.data.BagFactory;
import org.apache.pig.data.DataBag;
import org.apache.pig.data.DataByteArray;
import org.apache.pig.data.DataType;
import org.apache.pig.data.SortedDataBag;
import org.apache.pig.data.Tuple;
import org.apache.pig.data.TupleFactory;
import org.apache.pig.impl.logicalLayer.FrontendException;
import org.apache.pig.impl.logicalLayer.schema.Schema;
import org.codehaus.jackson.JsonNode;
import org.codehaus.jackson.map.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SuppressWarnings("serial")
public class VespaDocumentOperationTest {
    private final ByteArrayOutputStream outContent = new ByteArrayOutputStream();
    private final PrintStream originalOut = System.out;

    @BeforeEach
    public void setUpStreams() {
        System.setOut(new PrintStream(outContent));
    }

    @AfterEach
    public void restoreStreams() {
        System.setOut(originalOut);
    }
    @Test
    public void requireThatUDFReturnsCorrectJson() throws Exception {
        String json = getDocumentOperationJson("docid=id:<application>:metrics::<name>-<date>");
        ObjectMapper m = new ObjectMapper();
        JsonNode root = m.readTree(json);
        JsonNode fields = root.path("fields");

        // operation put is default
        assertEquals("id:testapp:metrics::clicks-20160112", root.get("put").getTextValue());
        assertEquals("testapp", fields.get("application").getTextValue());
        assertEquals("clicks", fields.get("name").getTextValue());
        assertEquals(3, fields.get("value").getIntValue());
    }


    @Test
    public void requireThatUDFSupportsUpdateAssign() throws IOException {
        String json = getDocumentOperationJson("docid=id:<application>:metrics::<name>-<date>", "operation=update");
        ObjectMapper m = new ObjectMapper();
        JsonNode root = m.readTree(json);
        JsonNode fields = root.path("fields");

        assertEquals("id:testapp:metrics::clicks-20160112", root.get("update").getTextValue());
        assertEquals("testapp", fields.get("application").get("assign").getTextValue());
        assertEquals("clicks", fields.get("name").get("assign").getTextValue());
        assertEquals(3, fields.get("value").get("assign").getIntValue());
    }

    @Test
    public void requireThatUDFSupportsConditionalUpdateAssign() throws IOException {
        String json = getDocumentOperationJson("docid=id:<application>:metrics::<name>-<date>", "operation=update", "condition=clicks < <value>");
        ObjectMapper m = new ObjectMapper();
        JsonNode root = m.readTree(json);
        JsonNode fields = root.path("fields");

        assertEquals("id:testapp:metrics::clicks-20160112", root.get("update").getTextValue());
        assertEquals("clicks < 3", root.get("condition").getTextValue());
        assertEquals("testapp", fields.get("application").get("assign").getTextValue());
        assertEquals("clicks", fields.get("name").get("assign").getTextValue());
        assertEquals(3, fields.get("value").get("assign").getIntValue());
    }

    @Test
    public void requireThatUDFSupportsCreateIfNonExistent() throws IOException {
        String json = getDocumentOperationJson("docid=id:<application>:metrics::<name>-<date>", "operation=update",
                "create-if-non-existent=true");
        ObjectMapper m = new ObjectMapper();
        JsonNode root = m.readTree(json);
        JsonNode fields = root.path("fields");

        assertEquals("id:testapp:metrics::clicks-20160112", root.get("update").getTextValue());
        assertTrue(root.get("create").getBooleanValue());
        assertEquals("testapp", fields.get("application").get("assign").getTextValue());
        assertEquals("clicks", fields.get("name").get("assign").getTextValue());
        assertEquals(3, fields.get("value").get("assign").getIntValue());
    }


    @Test
    public void requireThatUDFReturnsNullForMissingConfig() throws Exception {
        String json = getDocumentOperationJson();
        assertNull(json);
    }


    @Test
    public void requireThatUDFCorrectlyGeneratesRemoveBagAsMapOperation() throws Exception {
        DataBag bag = BagFactory.getInstance().newDefaultBag();

        Schema innerObjectSchema = new Schema();
        Tuple innerObjectTuple = TupleFactory.getInstance().newTuple();
        addToTuple("year", DataType.CHARARRAY, "2020", innerObjectSchema, innerObjectTuple);
        addToTuple("month", DataType.INTEGER, 3, innerObjectSchema, innerObjectTuple);

        Schema objectSchema = new Schema();
        Tuple objectTuple = TupleFactory.getInstance().newTuple();
        addToTuple("key", DataType.CHARARRAY, "234566", objectSchema, objectTuple);
        addToTuple("value", DataType.TUPLE, innerObjectTuple,innerObjectSchema,objectSchema, objectTuple);

        Schema bagSchema = new Schema();
        addToBagWithSchema("firstLayerTuple",DataType.TUPLE,objectTuple,objectSchema,bagSchema,bag);

        innerObjectSchema = new Schema();
        innerObjectTuple = TupleFactory.getInstance().newTuple();
        addToTuple("year", DataType.CHARARRAY, "2020", innerObjectSchema, innerObjectTuple);
        addToTuple("month", DataType.INTEGER, 3, innerObjectSchema, innerObjectTuple);

        objectSchema = new Schema();
        objectTuple = TupleFactory.getInstance().newTuple();
        addToTuple("key", DataType.CHARARRAY, "123456", objectSchema, objectTuple);
        addToTuple("value", DataType.TUPLE, innerObjectTuple,innerObjectSchema,objectSchema, objectTuple);

        addToBagWithSchema("firstLayerTuple",DataType.TUPLE,objectTuple,objectSchema,bagSchema,bag);

        Schema schema = new Schema();
        Tuple tuple = TupleFactory.getInstance().newTuple();
        addToTuple("bag", DataType.BAG, bag, bagSchema, schema, tuple);
        addToTuple("id", DataType.CHARARRAY, "123", schema, tuple);

        VespaDocumentOperation docOp = new VespaDocumentOperation("docid=id", "remove-map-fields=bag","operation=update");
        docOp.setInputSchema(schema);
        String json = docOp.exec(tuple);

        ObjectMapper m = new ObjectMapper();
        JsonNode root = m.readTree(json);
        JsonNode fields = root.get("fields");
        assertEquals("{\"remove\":0}", fields.get("bag{123456}").toString());
        assertEquals("{\"remove\":0}", fields.get("bag{234566}").toString());

    }

    @Test
    public void requireThatUDFCorrectlyGeneratesAddBagAsMapOperation() throws Exception {

        DataBag bag = BagFactory.getInstance().newDefaultBag();

        Schema innerObjectSchema = new Schema();
        Tuple innerObjectTuple = TupleFactory.getInstance().newTuple();
        addToTuple("year", DataType.CHARARRAY, "2020", innerObjectSchema, innerObjectTuple);
        addToTuple("month", DataType.INTEGER, 3, innerObjectSchema, innerObjectTuple);

        Schema objectSchema = new Schema();
        Tuple objectTuple = TupleFactory.getInstance().newTuple();
        addToTuple("key", DataType.CHARARRAY, "123456", objectSchema, objectTuple);
        addToTuple("value", DataType.TUPLE, innerObjectTuple,innerObjectSchema,objectSchema, objectTuple);

        Schema bagSchema = new Schema();
        addToBagWithSchema("firstLayerTuple",DataType.TUPLE,objectTuple,objectSchema,bagSchema,bag);

        Schema schema = new Schema();
        Tuple tuple = TupleFactory.getInstance().newTuple();
        addToTuple("bag", DataType.BAG, bag, bagSchema, schema, tuple);
        addToTuple("id", DataType.CHARARRAY, "123", schema, tuple);
        VespaDocumentOperation docOp = new VespaDocumentOperation("docid=id", "update-map-fields=bag","operation=update");
        docOp.setInputSchema(schema);
        String json = docOp.exec(tuple);

        ObjectMapper m = new ObjectMapper();
        JsonNode root = m.readTree(json);

        JsonNode fields = root.get("fields");
        JsonNode value = fields.get("bag{123456}");
        JsonNode assign = value.get("assign");
        assertEquals("2020", assign.get("year").getTextValue());
        assertEquals(3, assign.get("month").getIntValue());
    }

    @Test
    public void requireThatUDFCorrectlyGeneratesAddTensorOperation() throws Exception {

        Schema schema = new Schema();
        Tuple tuple = TupleFactory.getInstance().newTuple();

        // Please refer to the tensor format documentation

        Map<String, Double> tensor = new HashMap<String, Double>() {{
            put("x:label1,y:label2,z:label4", 2.0);
            put("x:label3", 3.0);
        }};

        addToTuple("id", DataType.CHARARRAY, "123", schema, tuple);
        addToTuple("tensor", DataType.MAP, tensor, schema, tuple);

        VespaDocumentOperation docOp = new VespaDocumentOperation("docid=empty", "update-tensor-fields=tensor","operation=update");
        docOp.setInputSchema(schema);
        String json = docOp.exec(tuple);

        ObjectMapper m = new ObjectMapper();
        JsonNode root = m.readTree(json);
        JsonNode fields = root.get("fields");
        JsonNode tensorValue = fields.get("tensor");
        JsonNode add = tensorValue.get("add");
        JsonNode cells = add.get("cells");
        Iterator<JsonNode> cellsIterator = cells.getElements();

        JsonNode element = cellsIterator.next();
        assertEquals("label1", element.get("address").get("x").getTextValue());
        assertEquals("label2", element.get("address").get("y").getTextValue());
        assertEquals("label4", element.get("address").get("z").getTextValue());
        assertEquals("2.0", element.get("value").toString());

        element = cellsIterator.next();
        assertEquals("label3", element.get("address").get("x").getTextValue());
        assertEquals("3.0", element.get("value").toString());
    }

    @Test
    public void requireThatUDFCorrectlyGeneratesRemoveTensorOperation() throws Exception {

        Schema schema = new Schema();
        Tuple tuple = TupleFactory.getInstance().newTuple();

        // Please refer to the tensor format documentation

        Map<String, Double> tensor = new HashMap<String, Double>() {{
            put("x:label1,y:label2,z:label4", 2.0);
            put("x:label3", 3.0);
        }};

        addToTuple("id", DataType.CHARARRAY, "123", schema, tuple);
        addToTuple("tensor", DataType.MAP, tensor, schema, tuple);

        VespaDocumentOperation docOp = new VespaDocumentOperation("docid=empty", "remove-tensor-fields=tensor","operation=update");
        docOp.setInputSchema(schema);
        String json = docOp.exec(tuple);

        ObjectMapper m = new ObjectMapper();
        JsonNode root = m.readTree(json);
        JsonNode fields = root.get("fields");
        JsonNode tensorValue = fields.get("tensor");
        JsonNode remove = tensorValue.get("remove");
        JsonNode address = remove.get("addresses");

        Iterator<JsonNode> addressIterator = address.getElements();

        JsonNode element = addressIterator.next();
        assertEquals("label1", element.get("x").getTextValue());

        element = addressIterator.next();
        assertEquals("label2", element.get("y").getTextValue());

        element = addressIterator.next();
        assertEquals("label4", element.get("z").getTextValue());

        element = addressIterator.next();
        assertEquals("label3", element.get("x").getTextValue());
    }

    @Test
    public void requireThatUDFReturnsNullWhenExceptionHappens() throws IOException {
        Schema schema = new Schema();
        Tuple tuple = TupleFactory.getInstance().newTuple();

        // broken DELTA format that would throw internally
        Map<String, Double> tensor = new HashMap<String, Double>() {{
            put("xlabel1", 2.0); // missing : between 'x' and 'label1'
        }};

        addToTuple("id", DataType.CHARARRAY, "123", schema, tuple);
        addToTuple("tensor", DataType.MAP, tensor, schema, tuple);
  
        VespaDocumentOperation docOp = new VespaDocumentOperation("docid=empty", "create-tensor-fields=tensor");
        docOp.setInputSchema(schema);
        String json = docOp.exec(tuple);

        assertNull(json);
    }

    @Test
    public void requireThatUDFCorrectlyGeneratesRemoveOperation() throws Exception {
        String json = getDocumentOperationJson("operation=remove", "docid=id:<application>:metrics::<name>-<date>");
        ObjectMapper m = new ObjectMapper();
        JsonNode root = m.readTree(json);
        JsonNode fields = root.get("fields");

        assertEquals("id:testapp:metrics::clicks-20160112", root.get("remove").getTextValue());
        assertNull(fields);
    }


    @Test
    public void requireThatUDFGeneratesComplexDataTypes() throws Exception {
        Schema schema = new Schema();
        Tuple tuple = TupleFactory.getInstance().newTuple();

        Tuple intTuple = TupleFactory.getInstance().newTuple();
        int[] intArray = {1, 2, 3};
        for (int i : intArray) { intTuple.append(i); }

        Tuple stringTuple = TupleFactory.getInstance().newTuple();
        String[] stringArray = {"a", "b", "c"};
        for (String s : stringArray) { stringTuple.append(s); }

        DataBag bag = new SortedDataBag(null);
        bag.add(intTuple);
        bag.add(stringTuple);

        Map<String, Object> innerMap = new HashMap<String, Object>() {{
            put("a", "string");
            put("tuple", intTuple);
        }};

        DataByteArray bytes = new DataByteArray("testdata".getBytes());

        Map<String, Object> outerMap = new HashMap<String, Object>() {{
            put("string", "value");
            put("int", 3);
            put("float", 3.145);
            put("bool", true);
            put("byte", bytes);
            put("map", innerMap);
            put("bag", bag);
        }};

        addToTuple("map", DataType.MAP, outerMap, schema, tuple);

        VespaDocumentOperation docOp = new VespaDocumentOperation("docid=empty");
        docOp.setInputSchema(schema);
        String json = docOp.exec(tuple);

        ObjectMapper m = new ObjectMapper();
        JsonNode root = m.readTree(json);
        JsonNode fields = root.get("fields");
        JsonNode map = fields.get("map");

        assertEquals("value", map.get("string").getTextValue());
        assertEquals(3, map.get("int").getIntValue());
        assertEquals(3.145, map.get("float").getDoubleValue(), 1e-6);
        assertTrue(map.get("bool").getBooleanValue());
        assertEquals("dGVzdGRhdGE=", map.get("byte").getTextValue());

        assertEquals("string", map.get("map").get("a").getTextValue());
        for (int i = 0; i < intArray.length; ++i) {
            assertEquals(intArray[i], map.get("map").get("tuple").get(i).asInt());
        }

        JsonNode bagField = map.get("bag");
        for (int i = 0; i < intArray.length; ++i) {
            assertEquals(intArray[i], bagField.get(0).get(i).asInt());
        }
        for (int i = 0; i < stringArray.length; ++i) {
            assertEquals(stringArray[i], bagField.get(1).get(i).asText());
        }
    }


    @Test
    public void requireThatSimpleArraysMustBeConfigured() throws Exception {
        String[] stringArray = {"a", "b", "c"};
        JsonNode array = setupSimpleArrayOperation("array", stringArray, "docid=empty"); // simple arrays not configured
        // json: [["a"], ["b"], ["c"]]
        assertEquals("a", array.get(0).get(0).asText());
        assertEquals("b", array.get(1).get(0).asText());
        assertEquals("c", array.get(2).get(0).asText());
    }


    @Test
    public void requireThatSimpleArraysAreSupported() throws Exception {
        String[] stringArray = {"a", "b", "c"};
        JsonNode array = setupSimpleArrayOperation("array", stringArray, "docid=empty", "simple-array-fields=array");
        // json: ["a", "b", "c"]
        assertEquals("a", array.get(0).asText());
        assertEquals("b", array.get(1).asText());
        assertEquals("c", array.get(2).asText());
    }


    @Test
    public void requireThatSimpleArraysCanBeConfiguredWithWildcard() throws Exception {
        String[] stringArray = {"a", "b", "c"};
        JsonNode array = setupSimpleArrayOperation("array", stringArray, "docid=empty", "simple-array-fields=*");
        // json: ["a", "b", "c"]
        assertEquals("a", array.get(0).asText());
        assertEquals("b", array.get(1).asText());
        assertEquals("c", array.get(2).asText());
    }


    @Test
    public void requireThatMultipleSimpleArraysAreSupported() throws Exception {
        String[] stringArray = {"a", "b", "c"};
        JsonNode array = setupSimpleArrayOperation("array", stringArray, "docid=empty", "simple-array-fields=empty,array");
        // json: ["a", "b", "c"]
        assertEquals("a", array.get(0).asText());
        assertEquals("b", array.get(1).asText());
        assertEquals("c", array.get(2).asText());
    }


    private JsonNode setupSimpleArrayOperation(String name, String[] array, String... params) throws IOException {
        Schema schema = new Schema();
        Tuple tuple = TupleFactory.getInstance().newTuple();

        DataBag bag = new SortedDataBag(null);
        for (String s : array) {
            Tuple stringTuple = TupleFactory.getInstance().newTuple();
            stringTuple.append(s);
            bag.add(stringTuple);
        }
        addToTuple(name, DataType.BAG, bag, schema, tuple);

        VespaDocumentOperation docOp = new VespaDocumentOperation(params);
        docOp.setInputSchema(schema);
        String json = docOp.exec(tuple);

        ObjectMapper m = new ObjectMapper();
        JsonNode root = m.readTree(json);
        JsonNode fields = root.get("fields");
        return fields.get(name);
    }


    @Test
    public void requireThatUDFSupportsTensors() throws IOException {
        Schema schema = new Schema();
        Tuple tuple = TupleFactory.getInstance().newTuple();

        // Please refer to the tensor format documentation

        Map<String, Double> tensor = new HashMap<String, Double>() {{
            put("x:label1,y:label2,z:label4", 2.0);
            put("x:label3", 3.0);
        }};

        addToTuple("id", DataType.CHARARRAY, "123", schema, tuple);
        addToTuple("tensor", DataType.MAP, tensor, schema, tuple);

        VespaDocumentOperation docOp = new VespaDocumentOperation("docid=empty", "create-tensor-fields=tensor");
        docOp.setInputSchema(schema);
        String json = docOp.exec(tuple);

        ObjectMapper m = new ObjectMapper();
        JsonNode root = m.readTree(json);
        JsonNode fields = root.get("fields");
        JsonNode tensorNode = fields.get("tensor");
        JsonNode cells = tensorNode.get("cells");

        assertEquals("label1", cells.get(0).get("address").get("x").asText());
        assertEquals("label2", cells.get(0).get("address").get("y").asText());
        assertEquals("label4", cells.get(0).get("address").get("z").asText());
        assertEquals("label3", cells.get(1).get("address").get("x").asText());

        assertEquals(2.0, cells.get(0).get("value").asDouble(), 1e-6);
        assertEquals(3.0, cells.get(1).get("value").asDouble(), 1e-6);
    }


    @Test
    public void requireThatUDFCanExcludeFields() throws IOException {
        String json = getDocumentOperationJson("docid=id:<application>:metrics::<name>-<date>", "exclude-fields=application,date");
        ObjectMapper m = new ObjectMapper();
        JsonNode root = m.readTree(json);
        JsonNode fields = root.path("fields");

        // 'application' and 'date' fields should not appear in JSON
        assertNull(fields.get("application"));
        assertNull(fields.get("date"));
        assertNotNull(fields.get("name"));
        assertNotNull(fields.get("value"));
    }


    private String getDocumentOperationJson(String... params) throws IOException {
        Schema schema = new Schema();
        Tuple tuple = TupleFactory.getInstance().newTuple();

        addToTuple("application", DataType.CHARARRAY, "testapp", schema, tuple);
        addToTuple("name", DataType.CHARARRAY, "clicks", schema, tuple);
        addToTuple("date", DataType.CHARARRAY, "20160112", schema, tuple);
        addToTuple("value", DataType.CHARARRAY, 3, schema, tuple);

        VespaDocumentOperation docOp = new VespaDocumentOperation(params);
        docOp.setInputSchema(schema);
        return docOp.exec(tuple);
    }


    @Test
    public void requireThatUDFSupportsSimpleObjectFields() throws IOException {
        Schema objectSchema = new Schema();
        Tuple objectTuple = TupleFactory.getInstance().newTuple();
        addToTuple("id", DataType.LONG, 123456789L, objectSchema, objectTuple);
        addToTuple("url", DataType.CHARARRAY, "example.com", objectSchema, objectTuple);
        addToTuple("value", DataType.INTEGER, 123, objectSchema, objectTuple);

        Schema schema = new Schema();
        Tuple tuple = TupleFactory.getInstance().newTuple();
        addToTuple("object", DataType.TUPLE, objectTuple, objectSchema, schema, tuple);

        VespaDocumentOperation docOp = new VespaDocumentOperation("docid=empty", "simple-object-fields=object");
        docOp.setInputSchema(schema);
        String json = docOp.exec(tuple);

        ObjectMapper m = new ObjectMapper();
        JsonNode root = m.readTree(json);
        JsonNode fields = root.get("fields");
        JsonNode objectNode = fields.get("object");

        assertEquals(123456789L, objectNode.get("id").asLong());
        assertEquals("example.com", objectNode.get("url").asText());
        assertEquals(123, objectNode.get("value").asInt());
    }


    @Test
    public void requireThatUDFSupportsBagAsMapFields() throws IOException {
        DataBag bag = BagFactory.getInstance().newDefaultBag();

        Schema objectSchema = new Schema();
        Tuple objectTuple = TupleFactory.getInstance().newTuple();
        addToTuple("key", DataType.CHARARRAY, "123456", objectSchema, objectTuple);
        addToTuple("value", DataType.INTEGER, 123456, objectSchema, objectTuple);
        bag.add(objectTuple);

        objectSchema = new Schema();
        objectTuple = TupleFactory.getInstance().newTuple();
        addToTuple("key", DataType.CHARARRAY, "234567", objectSchema, objectTuple);
        addToTuple("value", DataType.INTEGER, 234567, objectSchema, objectTuple);
        bag.add(objectTuple);

        Schema schema = new Schema();
        Tuple tuple = TupleFactory.getInstance().newTuple();
        addToTuple("bag", DataType.BAG, bag, objectSchema, schema, tuple);

        VespaDocumentOperation docOp = new VespaDocumentOperation("docid=empty", "bag-as-map-fields=bag");
        docOp.setInputSchema(schema);
        String json = docOp.exec(tuple);

        ObjectMapper m = new ObjectMapper();
        JsonNode root = m.readTree(json);
        JsonNode fields = root.get("fields");
        JsonNode bagNode = fields.get("bag");

        assertEquals(123456, bagNode.get("123456").asInt());
        assertEquals(234567, bagNode.get("234567").asInt());
    }

    @Test
    public void requireThatUDFPrintIdWhenVerbose() throws IOException {
        DataBag bag = BagFactory.getInstance().newDefaultBag();

        Schema objectSchema = new Schema();
        Tuple objectTuple = TupleFactory.getInstance().newTuple();
        addToTuple("key", DataType.CHARARRAY, "123456", objectSchema, objectTuple);
        addToTuple("value", DataType.INTEGER, 123456, objectSchema, objectTuple);
        bag.add(objectTuple);

        objectSchema = new Schema();
        objectTuple = TupleFactory.getInstance().newTuple();
        addToTuple("key", DataType.CHARARRAY, "234567", objectSchema, objectTuple);
        addToTuple("value", DataType.INTEGER, 234567, objectSchema, objectTuple);
        bag.add(objectTuple);

        Schema schema = new Schema();
        Tuple tuple = TupleFactory.getInstance().newTuple();
        addToTuple("bag", DataType.BAG, bag, objectSchema, schema, tuple);

        VespaDocumentOperation docOp = new VespaDocumentOperation("docid=7654321", "bag-as-map-fields=bag","verbose=true");
        docOp.setInputSchema(schema);
        String json = docOp.exec(tuple);

        assertTrue(outContent.toString().contains("Processing docId: 7654321"));
    }

    @Test
    public void requireThatUDFVerboseSetToFalseByDefault() throws IOException {
        DataBag bag = BagFactory.getInstance().newDefaultBag();

        Schema objectSchema = new Schema();
        Tuple objectTuple = TupleFactory.getInstance().newTuple();
        addToTuple("key", DataType.CHARARRAY, "123456", objectSchema, objectTuple);
        addToTuple("value", DataType.INTEGER, 123456, objectSchema, objectTuple);
        bag.add(objectTuple);

        objectSchema = new Schema();
        objectTuple = TupleFactory.getInstance().newTuple();
        addToTuple("key", DataType.CHARARRAY, "234567", objectSchema, objectTuple);
        addToTuple("value", DataType.INTEGER, 234567, objectSchema, objectTuple);
        bag.add(objectTuple);

        Schema schema = new Schema();
        Tuple tuple = TupleFactory.getInstance().newTuple();
        addToTuple("bag", DataType.BAG, bag, objectSchema, schema, tuple);

        VespaDocumentOperation docOp = new VespaDocumentOperation("docid=7654321", "bag-as-map-fields=bag");
        docOp.setInputSchema(schema);
        String json = docOp.exec(tuple);

        assertEquals("", outContent.toString());
    }

    private void addToTuple(String alias, byte type, Object value, Schema schema, Tuple tuple) {
        schema.add(new Schema.FieldSchema(alias, type));
        tuple.append(value);
    }


    private void addToTuple(String alias, byte type, Object value, Schema schemaInField, Schema schema, Tuple tuple)
            throws FrontendException {
        schema.add(new Schema.FieldSchema(alias, schemaInField, type));
        tuple.append(value);
    }

    private void addToBagWithSchema(String alias, byte type, Tuple value, Schema schemaInField, Schema schema,DataBag bag)
            throws FrontendException {
        schema.add(new Schema.FieldSchema(alias, schemaInField, type));
        bag.add(value);
    }
}
