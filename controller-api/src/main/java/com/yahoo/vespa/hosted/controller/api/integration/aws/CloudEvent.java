// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.controller.api.integration.aws;

import java.util.Date;
import java.util.Optional;
import java.util.Set;

/**
 * A maintenance event in a cloud service.
 *
 * @author freva
 */
public class CloudEvent {

    public final String instanceEventId;
    public final String code;
    public final String description;
    public final Optional<Date> notBefore;
    public final Optional<Date> notBeforeDeadline;
    public final Optional<Date> notAfter;
    public final String awsRegionName;
    public final Set<String> affectedInstances;

    public CloudEvent(String instanceEventId, String code, String description, Date notAfter, Date notBefore,
                      Date notBeforeDeadline, String awsRegionName, Set<String> affectedInstances) {
        this.instanceEventId = instanceEventId;
        this.code = code;
        this.description = description;
        this.notBefore = Optional.ofNullable(notBefore);
        this.notBeforeDeadline = Optional.ofNullable(notBeforeDeadline);
        this.notAfter = Optional.ofNullable(notAfter);
        this.awsRegionName = awsRegionName;
        this.affectedInstances = Set.copyOf(affectedInstances);
    }

}
