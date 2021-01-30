// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.container.logging;


import com.google.inject.Inject;
import com.yahoo.component.provider.ComponentRegistry;

/**
 * Logs to all the configured access logs.
 *
 * @author Tony Vaagenes
 * @author bjorncs
 */
public class AccessLog implements RequestLog {

    private final ComponentRegistry<RequestLogHandler> implementers;

    @Inject
    public AccessLog(ComponentRegistry<RequestLogHandler> implementers) {
        this.implementers = implementers;
    }

    public static AccessLog voidAccessLog() {
        return new AccessLog(new ComponentRegistry<>());
    }

    @Override
    public void log(RequestLogEntry entry) {
        for (RequestLogHandler handler: implementers.allComponents()) {
            handler.log(entry);
        }
    }

}
