// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.searchdefinition.derived;

import com.yahoo.config.model.deploy.TestProperties;
import com.yahoo.searchdefinition.parser.ParseException;
import org.junit.Test;

import java.io.IOException;

/**
 * @author arnej
 */
public class MatchSettingsResolvingTestCase extends AbstractExportingTestCase {

    @Test
    public void testSimpleDefaults() throws IOException, ParseException {
        assertCorrectDeriving("matchsettings_simple_def",
                              new TestProperties().setExperimentalSdParsing(false));
        assertCorrectDeriving("matchsettings_simple_def",
                              new TestProperties().setExperimentalSdParsing(true));
    }

    @Test
    public void testSimpleWithStructSettings() throws IOException, ParseException {
        assertCorrectDeriving("matchsettings_simple_wss",
                              new TestProperties().setExperimentalSdParsing(false));
        assertCorrectDeriving("matchsettings_simple_wss",
                              new TestProperties().setExperimentalSdParsing(true));
    }

    @Test
    public void testSimpleWithFieldSettings() throws IOException, ParseException {
        assertCorrectDeriving("matchsettings_simple_wfs",
                              new TestProperties().setExperimentalSdParsing(false));
        assertCorrectDeriving("matchsettings_simple_wfs",
                              new TestProperties().setExperimentalSdParsing(true));
    }

    @Test
    public void testSimpleStructAndFieldSettings() throws IOException, ParseException {
        assertCorrectDeriving("matchsettings_simple_wss_wfs",
                              new TestProperties().setExperimentalSdParsing(false));
        assertCorrectDeriving("matchsettings_simple_wss_wfs",
                              new TestProperties().setExperimentalSdParsing(true));
    }

    @Test
    public void testMapDefaults() throws IOException, ParseException {
        assertCorrectDeriving("matchsettings_map_def",
                              new TestProperties().setExperimentalSdParsing(false));
        assertCorrectDeriving("matchsettings_map_def",
                              new TestProperties().setExperimentalSdParsing(true));
    }

    @Test
    public void testMapWithStructSettings() throws IOException, ParseException {
        assertCorrectDeriving("matchsettings_map_wss",
                              new TestProperties().setExperimentalSdParsing(false));
        assertCorrectDeriving("matchsettings_map_wss",
                              new TestProperties().setExperimentalSdParsing(true));
    }

    @Test
    public void testMapWithFieldSettings() throws IOException, ParseException {
        assertCorrectDeriving("matchsettings_map_wfs",
                              new TestProperties().setExperimentalSdParsing(false));
        assertCorrectDeriving("matchsettings_map_wfs",
                              new TestProperties().setExperimentalSdParsing(true));
    }

    @Test
    public void testMapAfter() throws IOException, ParseException {
        assertCorrectDeriving("matchsettings_map_after",
                              new TestProperties().setExperimentalSdParsing(false));
        assertCorrectDeriving("matchsettings_map_after",
                              new TestProperties().setExperimentalSdParsing(true));
    }


    @Test
    public void testMapInStruct() throws IOException, ParseException {
        assertCorrectDeriving("matchsettings_map_in_struct",
                              new TestProperties().setExperimentalSdParsing(false));
        assertCorrectDeriving("matchsettings_map_in_struct",
                              new TestProperties().setExperimentalSdParsing(true));
    }

    
}
