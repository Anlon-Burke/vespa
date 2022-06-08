// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.schema.derived;

import com.yahoo.schema.parser.ParseException;
import org.junit.Test;

import java.io.IOException;

/**
 * Tests attribute settings
 *
 * @author bratseth
 */
public class AttributesTestCase extends AbstractExportingTestCase {

    @Test
    public void testDocumentDeriving() throws IOException, ParseException {
        assertCorrectDeriving("attributes");
    }

    @Test
    public void testArrayOfStructAttribute() throws IOException, ParseException {
        assertCorrectDeriving("array_of_struct_attribute");
    }

    @Test
    public void testMapOfStructAttribute() throws IOException, ParseException {
        assertCorrectDeriving("map_of_struct_attribute");
    }

    @Test
    public void testMapOfPrimitiveAttribute() throws IOException, ParseException {
        assertCorrectDeriving("map_attribute");
    }

}
