// Copyright Verizon Media. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.search.dispatch.searchcluster;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * @author bratseth
 */
public class SearchClusterCoverageTest {

    @Test
    public void two_groups_equal_docs() {
        var tester =  new SearchClusterTester(2, 3);

        tester.setDocsPerNode(100, 0);
        tester.setDocsPerNode(100, 1);
        tester.pingIterationCompleted();
        assertTrue(tester.group(0).hasSufficientCoverage());
        assertTrue(tester.group(1).hasSufficientCoverage());
    }

    @Test
    public void two_groups_one_missing_docs() {
        var tester =  new SearchClusterTester(2, 3);

        tester.setDocsPerNode(100, 0);
        tester.setDocsPerNode( 70, 1);
        tester.pingIterationCompleted();
        assertTrue(tester.group(0).hasSufficientCoverage());
        assertFalse(tester.group(1).hasSufficientCoverage());
    }

    @Test
    public void three_groups_one_missing_docs() {
        var tester =  new SearchClusterTester(3, 3);

        tester.setDocsPerNode(100, 0);
        tester.setDocsPerNode( 87, 1);  // min is set to 88 in MockSearchCluster
        tester.setDocsPerNode(100, 2);
        tester.pingIterationCompleted();
        assertTrue(tester.group(0).hasSufficientCoverage());
        assertFalse(tester.group(1).hasSufficientCoverage());
        assertTrue(tester.group(2).hasSufficientCoverage());
    }

    @Test
    public void three_groups_one_missing_docs_but_too_few() {
        var tester =  new SearchClusterTester(3, 3);

        tester.setDocsPerNode(100, 0);
        tester.setDocsPerNode( 89, 1);  // min is set to 88 in MockSearchCluster
        tester.setDocsPerNode(100, 2);
        tester.pingIterationCompleted();
        assertTrue(tester.group(0).hasSufficientCoverage());
        assertTrue(tester.group(1).hasSufficientCoverage());
        assertTrue(tester.group(2).hasSufficientCoverage());
    }

    @Test
    public void three_groups_one_has_too_many_docs() {
        var tester =  new SearchClusterTester(3, 3);

        tester.setDocsPerNode(100, 0);
        tester.setDocsPerNode(150, 1);
        tester.setDocsPerNode(100, 2);
        tester.pingIterationCompleted();
        assertTrue(tester.group(0).hasSufficientCoverage());
        assertTrue(tester.group(1).hasSufficientCoverage());
        assertTrue(tester.group(2).hasSufficientCoverage());
    }

    @Test
    public void three_groups_one_has_a_node_down() {
        var tester =  new SearchClusterTester(3, 3);

        tester.setDocsPerNode(100, 0);
        tester.setDocsPerNode(150, 1);
        tester.setDocsPerNode(100, 2);
        tester.setWorking(1, 1, false);
        tester.pingIterationCompleted();
        assertTrue(tester.group(0).hasSufficientCoverage());
        assertFalse(tester.group(1).hasSufficientCoverage());
        assertTrue(tester.group(2).hasSufficientCoverage());
    }

}
