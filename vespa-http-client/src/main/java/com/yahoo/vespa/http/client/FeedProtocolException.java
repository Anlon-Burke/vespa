// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.http.client;

import com.yahoo.vespa.http.client.config.Endpoint;

/**
 * An exception thrown when a feed endpoint returns an error during feeding.
 *
 * @author bjorncs
 * @deprecated Vespa-http-client will be removed in Vespa 8. It's replaced by <a href="https://docs.vespa.ai/en/vespa-feed-client.html">vespa-feed-client</a>
 */
@Deprecated
public class FeedProtocolException extends FeedEndpointException {

    private final int httpStatusCode;
    private final String httpResponseMessage;

    public FeedProtocolException(int httpStatusCode,
                                 String httpResponseMessage,
                                 Throwable cause,
                                 Endpoint endpoint) {
        super(createMessage(httpStatusCode, httpResponseMessage, endpoint), cause, endpoint);
        this.httpStatusCode = httpStatusCode;
        this.httpResponseMessage = httpResponseMessage;
    }

    private static String createMessage(int httpStatusCode,
                                        String httpResponseMessage,
                                        Endpoint endpoint) {
        return String.format("Endpoint '%s:%d' returned an error on handshake: %d - %s",
                             endpoint.getHostname(),
                             endpoint.getPort(),
                             httpStatusCode,
                             httpResponseMessage);
    }

    public int getHttpStatusCode() {
        return httpStatusCode;
    }

    public String getHttpResponseMessage() {
        return httpResponseMessage;
    }
}
