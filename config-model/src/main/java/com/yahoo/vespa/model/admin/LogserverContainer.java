// Copyright 2019 Oath Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.model.admin;

import com.yahoo.config.model.api.ModelContext.FeatureFlags;
import com.yahoo.config.model.api.container.ContainerServiceType;
import com.yahoo.config.model.producer.AbstractConfigProducer;
import com.yahoo.vespa.model.container.Container;
import com.yahoo.vespa.model.container.component.AccessLogComponent;

/**
 * Container that should be running on same host as the logserver. Sets up a handler for getting logs from logserver.
 * Only in use in hosted Vespa.
 */
public class LogserverContainer extends Container {

    public LogserverContainer(AbstractConfigProducer parent, FeatureFlags featureFlags, boolean isHostedVespa) {
        super(parent, featureFlags, "" + 0, 0, isHostedVespa);
        addComponent(new AccessLogComponent(AccessLogComponent.AccessLogType.jsonAccessLog, ((LogserverContainerCluster) parent).getName(), true));
    }

    @Override
    public ContainerServiceType myServiceType() {
        return ContainerServiceType.LOGSERVER_CONTAINER;
    }

}
