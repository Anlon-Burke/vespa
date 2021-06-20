// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.model.content.cluster;

import com.yahoo.config.application.api.DeployLogger;
import com.yahoo.vespa.model.content.DispatchTuning;
import com.yahoo.vespa.model.builder.xml.dom.ModelElement;

import java.util.logging.Level;

/**
 * @author Simon Thoresen Hult
 */
public class DomTuningDispatchBuilder {

    public static DispatchTuning build(ModelElement contentXml, DeployLogger logger) {
        DispatchTuning.Builder builder = new DispatchTuning.Builder();
        ModelElement tuningElement = contentXml.child("tuning");
        if (tuningElement == null) {
            return builder.build();
        }
        ModelElement dispatchElement = tuningElement.child("dispatch");
        if (dispatchElement == null) {
            return builder.build();
        }
        builder.setMaxHitsPerPartition(dispatchElement.childAsInteger("max-hits-per-partition"));
        builder.setTopKProbability(dispatchElement.childAsDouble("top-k-probability"));
        builder.setDispatchPolicy(dispatchElement.childAsString("dispatch-policy"));
        builder.setMinActiveDocsCoverage(dispatchElement.childAsDouble("min-active-docs-coverage"));

        if (dispatchElement.child("min-group-coverage") != null)
            logger.logApplicationPackage(Level.WARNING, "Attribute 'min-group-coverage' is deprecated and ignored: " +
                                                        "Use min-active-docs-coverage instead.");
        if (dispatchElement.child("use-local-node") != null)
            logger.logApplicationPackage(Level.WARNING, "Attribute 'use-local-node' is deprecated and ignored: " +
                                                        "The local node will automatically be preferred when appropriate.");
        return builder.build();
    }

}
