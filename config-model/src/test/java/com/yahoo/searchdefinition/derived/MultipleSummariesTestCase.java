// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.searchdefinition.derived;

import com.yahoo.config.model.deploy.TestProperties;
import com.yahoo.searchdefinition.parser.ParseException;
import org.junit.Test;

import java.io.IOException;

/**
 * Tests deriving a configuration with multiple summaries
 *
 * @author  bratseth
 */
public class MultipleSummariesTestCase extends AbstractExportingTestCase {
    @Test
    public void testMultipleSummaries() throws IOException, ParseException {
        assertCorrectDeriving("multiplesummaries",
                              new TestProperties().setExperimentalSdParsing(false));
    }

    @Test
    public void testMultipleSummariesNew() throws IOException, ParseException {
        assertCorrectDeriving("multiplesummaries",
                              new TestProperties().setExperimentalSdParsing(true));
    }
}
