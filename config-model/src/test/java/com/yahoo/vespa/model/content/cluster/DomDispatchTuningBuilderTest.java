// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.model.content.cluster;

import com.yahoo.vespa.model.builder.xml.dom.ModelElement;
import com.yahoo.vespa.model.content.DispatchTuning;
import com.yahoo.vespa.model.test.utils.DeployLoggerStub;
import org.junit.Test;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * @author Simon Thoresen Hult
 */
public class DomDispatchTuningBuilderTest {

    @Test
    public void requireThatDefaultsAreNull() throws Exception {
        DispatchTuning dispatch = newTuningDispatch(
                "<content/>");
        assertNull(dispatch.getMaxHitsPerPartition());
    }

    @Test
    public void requireThatEmptyTuningIsSafe() throws Exception {
        DispatchTuning dispatch = newTuningDispatch(
                "<content>" +
                "  <tuning/>" +
                "</content>");
        assertNull(dispatch.getMaxHitsPerPartition());
    }

    @Test
    public void requireThatEmptydispatchIsSafe() throws Exception {
        DispatchTuning dispatch = newTuningDispatch(
                "<content>" +
                "  <tuning>" +
                "    <dispatch/>" +
                "  </tuning>" +
                "</content>");
        assertNull(dispatch.getMaxHitsPerPartition());
        assertNull(dispatch.getMinActiveDocsCoverage());
        assertNull(dispatch.getDispatchPolicy());
        assertNull(dispatch.getTopkProbability());
    }

    @Test
    public void requireThatTuningDispatchCanBeBuilt() throws Exception {
        DispatchTuning dispatch = newTuningDispatch(
                "<content>" +
                "  <tuning>" +
                "    <dispatch>" +
                "      <max-hits-per-partition>69</max-hits-per-partition>" +
                "      <min-active-docs-coverage>12.5</min-active-docs-coverage>" +
                "      <top-k-probability>0.999</top-k-probability>" +
                "    </dispatch>" +
                "  </tuning>" +
                "</content>");
        assertEquals(69, dispatch.getMaxHitsPerPartition().intValue());
        assertEquals(12.5, dispatch.getMinActiveDocsCoverage().doubleValue(), 0.0);
        assertEquals(0.999, dispatch.getTopkProbability().doubleValue(), 0.0);
    }
    @Test
    public void requireThatTuningDispatchPolicyRoundRobin() throws Exception {
        DispatchTuning dispatch = newTuningDispatch(
                "<content>" +
                        "  <tuning>" +
                        "    <dispatch>" +
                        "      <dispatch-policy>round-robin</dispatch-policy>" +
                        "    </dispatch>" +
                        "  </tuning>" +
                        "</content>");
        assertTrue(DispatchTuning.DispatchPolicy.ROUNDROBIN == dispatch.getDispatchPolicy());
    }
    @Test
    public void requireThatTuningDispatchPolicyRandom() throws Exception {
        DispatchTuning dispatch = newTuningDispatch(
                "<content>" +
                        "  <tuning>" +
                        "    <dispatch>" +
                        "      <dispatch-policy>random</dispatch-policy>" +
                        "    </dispatch>" +
                        "  </tuning>" +
                        "</content>");
        assertTrue(DispatchTuning.DispatchPolicy.ADAPTIVE == dispatch.getDispatchPolicy());
    }

    private static DispatchTuning newTuningDispatch(String xml) throws Exception {
        return DomTuningDispatchBuilder.build(
                new ModelElement(DocumentBuilderFactory.newInstance()
                                                       .newDocumentBuilder()
                                                       .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)))
                                                       .getDocumentElement()),
                new DeployLoggerStub());
    }
}
