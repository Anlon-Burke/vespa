// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.search.query.profile.test;

import com.yahoo.search.query.profile.DumpTool;
import org.junit.Test;

import static org.junit.Assert.assertTrue;

/**
 * @author bratseth
 */
public class DumpToolTestCase {

    private final String profileDir = "src/test/java/com/yahoo/search/query/profile/config/test/multiprofile";

    @Test
    public void testNoParameters() {
        assertTrue(new DumpTool().resolveAndDump().startsWith("Dumps all resolved"));
    }

    @Test
    public void testHelpParameter() {
        assertTrue(new DumpTool().resolveAndDump("-help").startsWith("Dumps all resolved"));
    }

    @Test
    public void testNoDimensionValues() {
        System.out.println(new DumpTool().resolveAndDump("multiprofile1", profileDir));
        assertTrue(new DumpTool().resolveAndDump("multiprofile1", profileDir).contains("a=general-a\n"));
    }

    @Test
    public void testAllParametersSet() {
        assertTrue(new DumpTool().resolveAndDump("multiprofile1", profileDir, "").contains("a=general-a\n"));
    }

    @Test
    public void testVariant() {
        String result = new DumpTool().resolveAndDump("multiprofile1", profileDir, "region=us");
        assertTrue(result.contains("a=us-a"));
        assertTrue(result.contains("b=us-b"));
        assertTrue(result.contains("region=us"));
    }

}
