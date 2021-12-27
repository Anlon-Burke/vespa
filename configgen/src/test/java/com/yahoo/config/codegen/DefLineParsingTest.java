// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.config.codegen;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Tests parsing of a single line of a .def file
 *
 * @author bratseth
 * @author gjoranv
 */
public class DefLineParsingTest {

    @Test(expected = IllegalArgumentException.class)
    public void require_that_null_default_is_not_allowed() {
        new DefLine("s string default=null");
    }

    @Test
    public void testParseIntArray() {
        DefLine l = new DefLine("foo[] int");

        assertEquals("foo[]", l.getName());
        assertNull(l.getDefault());
        assertEquals("int", l.getType().getName());
    }

    @Test
    public void testParseIntMap() {
        DefLine l = new DefLine("foo{} int");

        assertEquals("foo{}", l.getName());
        assertNull(l.getDefault());
        assertEquals("int", l.getType().getName());
    }

    @Test
    public void testParseInnerMap() {
        DefLine l = new DefLine("foo{}.i int");

        assertEquals("foo{}.i", l.getName());
        assertNull(l.getDefault());
        assertEquals("int", l.getType().getName());
    }

    @Test
    public void testParseEnum() {
        DefLine l = new DefLine("idtype enum { us_east, US_WEST, EMEA } default=EMEA");

        assertEquals("idtype", l.getName());
        assertEquals("EMEA", l.getDefault().getValue());
        assertEquals("EMEA", l.getDefault().getStringRepresentation());
        assertEquals("enum", l.getType().getName());
        assertEquals("us_east", l.getType().getEnumArray()[0]);
        assertEquals("US_WEST", l.getType().getEnumArray()[1]);
        assertEquals("EMEA", l.getType().getEnumArray()[2]);
    }

    @Test
    public void testParseDefaultReference() {
        DefLine l = new DefLine("foo.bar reference default=\"value\"");

        assertEquals("foo.bar", l.getName());
        assertEquals("value", l.getDefault().getValue());
        assertEquals("\"value\"", l.getDefault().getStringRepresentation());
        assertEquals("reference", l.getType().getName());
    }

    @Test
    public void testParseNoDefaultReference() {
        DefLine l = new DefLine("foo.bar reference");

        assertEquals("foo.bar", l.getName());
        assertNull(l.getDefault());
        assertEquals("reference", l.getType().getName());
    }

    /**
     * 'file' parameters with default value is currently (2010-01-05) not allowed, but that might change in
     * the future, so the test is included to verify that value and name can be retrieved.
     */
    @Test
    public void testParseDefaultFile() {
        DefLine l = new DefLine("fileWithDef file default=\"value\"");

        assertEquals("fileWithDef", l.getName());
        assertEquals("value", l.getDefault().getValue());
        assertEquals("\"value\"", l.getDefault().getStringRepresentation());
        assertEquals("file", l.getType().getName());
    }

    @Test
    public void testParseNoDefaultFile() {
        DefLine l = new DefLine("fileVal file");

        assertEquals("fileVal", l.getName());
        assertNull(l.getDefault());
        assertEquals("file", l.getType().getName());
    }

    @Test
    public void testParseUrls() {
        DefLine l = new DefLine("urlVal url");

        assertEquals("urlVal", l.getName());
        assertNull(l.getDefault());
        assertEquals("url", l.getType().getName());
    }

    @Test
    public void testParseDefaultUrls() {
        DefLine l = new DefLine("urlVal url default=\"http://docs.vespa.ai\"");

        assertEquals("urlVal", l.getName());
        assertEquals("http://docs.vespa.ai", l.getDefault().getValue());
        assertEquals("\"http://docs.vespa.ai\"", l.getDefault().getStringRepresentation());
        assertEquals("url", l.getType().getName());
    }


    @Test
    public void testParseDefaultInt() {
        DefLine l = new DefLine("foo int default=1000");

        assertEquals("foo", l.getName());
        assertEquals("1000", l.getDefault().getValue());
        assertEquals("1000", l.getDefault().getStringRepresentation());
        assertEquals("int", l.getType().getName());
    }

    @Test
    public void testParseDefaultLong() {
        DefLine l = new DefLine("foo long default=9223372036854775807");

        assertEquals("foo", l.getName());
        assertEquals("9223372036854775807", l.getDefault().getValue());
        assertEquals("9223372036854775807", l.getDefault().getStringRepresentation());
        assertEquals("long", l.getType().getName());
    }

    @Test
    public void testParseDefaultDouble() {
        DefLine l = new DefLine("foo double default=5.37");

        assertEquals("foo", l.getName());
        assertEquals("5.37", l.getDefault().getValue());
        assertEquals("5.37", l.getDefault().getStringRepresentation());
        assertEquals("double", l.getType().getName());
    }

    @Test
    public void testParseDefaultFalseBoolean() {
        DefLine l = new DefLine("foo bool default=false");

        assertEquals("foo", l.getName());
        assertEquals("false", l.getDefault().getValue());
        assertEquals("false", l.getDefault().getStringRepresentation());
        assertEquals("bool", l.getType().getName());
    }

    @Test
    public void testParseDefaultTrueBoolean() {
        DefLine l = new DefLine("foo bool default=true");

        assertEquals("foo", l.getName());
        assertEquals("true", l.getDefault().getValue());
        assertEquals("true", l.getDefault().getStringRepresentation());
        assertEquals("bool", l.getType().getName());
    }

    @Test
    public void testParseNoDefaultString() {
        DefLine l = new DefLine("foo.bar string");

        assertEquals("foo.bar", l.getName());
        assertNull(l.getDefault());
        assertEquals("string", l.getType().getName());
    }

    @Test
    public void testParseDefaultString() {
        DefLine l = new DefLine("foo.bar string default=\"value\"");

        assertEquals("foo.bar", l.getName());
        assertEquals("value", l.getDefault().getValue());
        assertEquals("\"value\"", l.getDefault().getStringRepresentation());
        assertEquals("string", l.getType().getName());
    }

    @Test
    public void testParseDefaultEmptyString() {
        DefLine l = new DefLine("foo.bar string default=\"\"");

        assertEquals("foo.bar", l.getName());
        assertEquals("", l.getDefault().getValue());
        assertEquals("\"\"", l.getDefault().getStringRepresentation());
        assertEquals("string", l.getType().getName());
    }

    @Test
    public void testParseDefaultStringUnquoted() {
        DefLine l = new DefLine("foo.bar string default=value");

        assertEquals("foo.bar", l.getName());
        assertEquals("value", l.getDefault().getValue());
        assertEquals("\"value\"", l.getDefault().getStringRepresentation());
        assertEquals("string", l.getType().getName());
    }

    @Test
    public void testParseStringnullDefaultString() {
        DefLine l = new DefLine("foo.bar string default=\"null\"");

        assertEquals("foo.bar", l.getName());
        assertEquals("null", l.getDefault().getValue());
        assertEquals("\"null\"", l.getDefault().getStringRepresentation());
        assertEquals("string", l.getType().getName());
    }

    @Test
    public void testRanges() {
        DefLine i = new DefLine("i int range=[0, 100]");
        DefLine l = new DefLine("l long range=[-1e10, 0]");
        DefLine d = new DefLine("d double range=[0, 1.0]");
        assertEquals("[0, 100]", i.getRange());
        assertEquals("[-1e10, 0]", l.getRange());
        assertEquals("[0, 1.0]", d.getRange());
    }

    @Test
    public void testRestartSpecification() {
        DefLine r0 = new DefLine("i int");
        DefLine r1 = new DefLine("i int restart");
        assertFalse(r0.getRestart());
        assertTrue(r1.getRestart());
    }

}
