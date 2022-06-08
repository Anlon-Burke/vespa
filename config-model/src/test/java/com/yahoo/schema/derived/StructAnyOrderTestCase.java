// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.schema.derived;

import com.yahoo.schema.parser.ParseException;
import org.junit.Test;

import java.io.IOException;

/**
 * @author Einar M R Rosenvinge
 */
public class StructAnyOrderTestCase extends AbstractExportingTestCase {
    @Test
    public void testStructAnyOrder() throws IOException, ParseException {
        assertCorrectDeriving("structanyorder");
    }
}
