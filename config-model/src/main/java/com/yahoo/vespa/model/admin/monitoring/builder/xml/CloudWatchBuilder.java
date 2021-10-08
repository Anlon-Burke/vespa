// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.model.admin.monitoring.builder.xml;

import com.yahoo.vespa.model.admin.monitoring.CloudWatch;
import com.yahoo.vespa.model.admin.monitoring.MetricsConsumer;
import org.w3c.dom.Element;

import static com.yahoo.config.model.builder.xml.XmlHelper.getOptionalAttribute;
import static com.yahoo.config.model.builder.xml.XmlHelper.getOptionalChild;

/**
 * @author gjoranv
 */
public class CloudWatchBuilder {

    private static final String REGION_ATTRIBUTE = "region";
    private static final String NAMESPACE_ATTRIBUTE = "namespace";
    private static final String CREDENTIALS_ELEMENT = "credentials";
    private static final String ACCESS_KEY_ATTRIBUTE = "access-key-name";
    private static final String SECRET_KEY_ATTRIBUTE = "secret-key-name";
    private static final String SHARED_CREDENTIALS_ELEMENT = "shared-credentials";
    private static final String PROFILE_ATTRIBUTE = "profile";
    private static final String FILE_ATTRIBUTE = "file";

    public static CloudWatch buildCloudWatch(Element cloudwatchElement, MetricsConsumer consumer) {
        CloudWatch cloudWatch = new CloudWatch(cloudwatchElement.getAttribute(REGION_ATTRIBUTE),
                                               cloudwatchElement.getAttribute(NAMESPACE_ATTRIBUTE),
                                               consumer);

        getOptionalChild(cloudwatchElement, CREDENTIALS_ELEMENT)
                .ifPresent(elem -> cloudWatch.setHostedAuth(elem.getAttribute(ACCESS_KEY_ATTRIBUTE),
                                                            elem.getAttribute(SECRET_KEY_ATTRIBUTE)));

        getOptionalChild(cloudwatchElement, SHARED_CREDENTIALS_ELEMENT)
                .ifPresent(elem -> cloudWatch.setSharedCredentials(elem.getAttribute(FILE_ATTRIBUTE),
                                                                   getOptionalAttribute(elem, PROFILE_ATTRIBUTE)));

        return cloudWatch;
    }

}
