// Copyright Vespa.ai. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package ai.vespa.metricsproxy.metric.dimensions;

import ai.vespa.metricsproxy.metric.model.DimensionId;

import java.util.Map;

import static ai.vespa.metricsproxy.core.MetricsConsumers.toUnmodifiableLinkedMap;
import static ai.vespa.metricsproxy.metric.model.DimensionId.toDimensionId;

/**
 * Node-specific metric dimensions.
 *
 * @author gjoranv
 */
public class NodeDimensions {

    private final Map<DimensionId, String> dimensions;

    public NodeDimensions(NodeDimensionsConfig config) {
        dimensions = config.dimensions().entrySet().stream().collect(
                toUnmodifiableLinkedMap(e -> toDimensionId(e.getKey()), Map.Entry::getValue));
    }

    public Map<DimensionId, String> getDimensions() { return dimensions; }

}
