// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.http.client.core.communication;

import ai.vespa.util.http.hc4.VespaHttpClientBuilder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yahoo.security.SslContextBuilder;
import com.yahoo.vespa.http.client.config.ConnectionParams;
import com.yahoo.vespa.http.client.config.Endpoint;
import com.yahoo.vespa.http.client.config.FeedParams;
import com.yahoo.vespa.http.client.core.Document;
import com.yahoo.vespa.http.client.core.Encoder;
import com.yahoo.vespa.http.client.core.Headers;
import com.yahoo.vespa.http.client.core.ServerResponseException;
import com.yahoo.vespa.http.client.core.Vtag;
import org.apache.http.Header;
import org.apache.http.HttpHost;
import org.apache.http.HttpResponse;
import org.apache.http.StatusLine;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.InputStreamEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.message.BasicHeader;
import org.apache.http.util.EntityUtils;

import javax.net.ssl.SSLContext;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.GZIPOutputStream;

/**
 * @author Einar M R Rosenvinge
 */
class ApacheGatewayConnection implements GatewayConnection {

    private static final Logger log = Logger.getLogger(ApacheGatewayConnection.class.getName());
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final String PATH = "/reserved-for-internal-use/feedapi?";
    private static final byte[] START_OF_FEED_XML = "<vespafeed>\n".getBytes(StandardCharsets.UTF_8);
    private static final byte[] END_OF_FEED_XML = "\n</vespafeed>\n".getBytes(StandardCharsets.UTF_8);
    private static final byte[] START_OF_FEED_JSON = "[".getBytes(StandardCharsets.UTF_8);
    private static final byte[] END_OF_FEED_JSON = "]".getBytes(StandardCharsets.UTF_8);

    private final List<Integer> supportedVersions = new ArrayList<>();
    private final byte[] startOfFeed;
    private final byte[] endOfFeed;
    private final Endpoint endpoint;
    private final FeedParams feedParams;
    private final String clusterSpecificRoute;
    private final ConnectionParams connectionParams;
    private CloseableHttpClient httpClient;
    private Instant connectionTime = null;
    private Instant lastPollTime = null;
    private String sessionId;
    private final String clientId;
    private int negotiatedVersion = -1;
    private final HttpClientFactory httpClientFactory;
    private final String shardingKey = UUID.randomUUID().toString().substring(0, 5);
    private final Clock clock;

    ApacheGatewayConnection(Endpoint endpoint,
                            FeedParams feedParams,
                            String clusterSpecificRoute,
                            ConnectionParams connectionParams,
                            HttpClientFactory httpClientFactory,
                            String clientId,
                            Clock clock) {
        supportedVersions.add(3);
        this.endpoint = endpoint;
        this.feedParams = feedParams;
        this.clusterSpecificRoute = clusterSpecificRoute;
        this.httpClientFactory = httpClientFactory;
        this.connectionParams = connectionParams;
        this.httpClient = null;
        this.clientId = clientId;
        this.clock = clock;

        if (feedParams.getDataFormat() == FeedParams.DataFormat.JSON_UTF8) {
            startOfFeed = START_OF_FEED_JSON;
            endOfFeed = END_OF_FEED_JSON;
        } else {
            startOfFeed = START_OF_FEED_XML;
            endOfFeed = END_OF_FEED_XML;
        }
    }

    @Override
    public InputStream write(List<Document> docs) throws ServerResponseException, IOException {
        return write(docs, false, connectionParams.getUseCompression());
    }

    @Override
    public InputStream poll() throws ServerResponseException, IOException {
        lastPollTime = clock.instant();
        return write(Collections.<Document>emptyList(), false, false);
    }

    @Override
    public Instant lastPollTime() { return lastPollTime; }

    @Override
    public InputStream drain() throws ServerResponseException, IOException {
        return write(Collections.<Document>emptyList(), true, false);
    }

    @Override
    public boolean connect() {
        log.fine(() -> "Attempting to connect to " + endpoint);
        if (httpClient != null)
            log.log(Level.WARNING, "Previous httpClient still exists.");
        httpClient = httpClientFactory.createClient();
        connectionTime = clock.instant();
        return httpClient != null;
    }

    @Override
    public Instant connectionTime() { return connectionTime; }

    // Protected for easier testing only.
    protected static InputStreamEntity zipAndCreateEntity(final InputStream inputStream) throws IOException {
        byte[] buffer = new byte[4096];
        GZIPOutputStream gzos = null;
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            gzos = new GZIPOutputStream(baos);
            while (inputStream.available() > 0) {
                int length = inputStream.read(buffer);
                gzos.write(buffer, 0,length);
            }
        } finally {
            if (gzos != null)  {
                gzos.close();
            }
        }
        byte[] fooGzippedBytes = baos.toByteArray();
        return new InputStreamEntity(new ByteArrayInputStream(fooGzippedBytes), -1);
    }

    private InputStream write(List<Document> docs, boolean drain, boolean useCompression)
            throws ServerResponseException, IOException {
        HttpPost httpPost = createPost(drain, useCompression, false);

        ByteBuffer[] buffers = getDataWithStartAndEndOfFeed(docs, negotiatedVersion);
        InputStream inputStream = new ByteBufferInputStream(buffers);
        InputStreamEntity reqEntity = useCompression ? zipAndCreateEntity(inputStream)
                                                     : new InputStreamEntity(inputStream, -1);
        reqEntity.setChunked(true);
        httpPost.setEntity(reqEntity);
        return executePost(httpPost);
    }

    private ByteBuffer[] getDataWithStartAndEndOfFeed(List<Document> docs, int version) {
        List<ByteBuffer> data = new ArrayList<>();
        if (version == 3) {
            for (Document doc : docs) {
                int operationSize = doc.size() + startOfFeed.length + endOfFeed.length;
                StringBuilder envelope = new StringBuilder();
                Encoder.encode(doc.getOperationId(), envelope);
                envelope.append(' ');
                envelope.append(Integer.toHexString(operationSize));
                envelope.append('\n');
                data.add(StandardCharsets.US_ASCII.encode(envelope.toString()));
                data.add(ByteBuffer.wrap(startOfFeed));
                data.add(doc.getData());
                data.add(ByteBuffer.wrap(endOfFeed));
            }
        } else {
            throw new IllegalArgumentException("Protocol version " + version + " unsupported by client.");
        }
        return data.toArray(new ByteBuffer[data.size()]);
    }

    private HttpPost createPost(boolean drain, boolean useCompression, boolean isHandshake) {
        HttpPost httpPost = new HttpPost(createUri());

        for (int v : supportedVersions) {
            httpPost.addHeader(Headers.VERSION, "" + v);
        }
        if (sessionId != null) {
            httpPost.setHeader(Headers.SESSION_ID, sessionId);
        }
        if (clientId != null) {
            httpPost.setHeader(Headers.CLIENT_ID, clientId);
        }
        httpPost.setHeader(Headers.SHARDING_KEY, shardingKey);
        httpPost.setHeader(Headers.DRAIN, drain ? "true" : "false");
        if (clusterSpecificRoute != null) {
            httpPost.setHeader(Headers.ROUTE, feedParams.getRoute());
        } else {
            if (feedParams.getRoute() != null) {
                httpPost.setHeader(Headers.ROUTE, feedParams.getRoute());
            }
        }
        if (!isHandshake) {
            if (feedParams.getDataFormat() == FeedParams.DataFormat.JSON_UTF8) {
                httpPost.setHeader(Headers.DATA_FORMAT, FeedParams.DataFormat.JSON_UTF8.name());
            } else {
                httpPost.setHeader(Headers.DATA_FORMAT, FeedParams.DataFormat.XML_UTF8.name());
            }
            if (feedParams.getPriority() != null) {
                httpPost.setHeader(Headers.PRIORITY, feedParams.getPriority());
            }
            if (connectionParams.getTraceLevel() != 0) {
                httpPost.setHeader(Headers.TRACE_LEVEL, String.valueOf(connectionParams.getTraceLevel()));
            }
            if (negotiatedVersion == 3 && feedParams.getDenyIfBusyV3()) {
                httpPost.setHeader(Headers.DENY_IF_BUSY, "true");
            }
        }
        if (feedParams.getSilentUpgrade()) {
            httpPost.setHeader(Headers.SILENTUPGRADE, "true");
        }
        httpPost.setHeader(Headers.TIMEOUT, "" + feedParams.getServerTimeout(TimeUnit.SECONDS));

        for (Map.Entry<String, String> extraHeader : connectionParams.getHeaders()) {
            httpPost.addHeader(extraHeader.getKey(), extraHeader.getValue());
        }
        connectionParams.getDynamicHeaders().forEach((headerName, provider) -> {
            String headerValue = Objects.requireNonNull(
                    provider.getHeaderValue(),
                    provider.getClass().getName() + ".getHeader() returned null as header value!");
            httpPost.addHeader(headerName, headerValue);
        });

        if (useCompression) { // This causes the apache client to gzip the request content. Weird, huh?
            httpPost.setHeader("Content-Encoding", "gzip");
        }
        return httpPost;
    }

    private InputStream executePost(HttpPost httpPost) throws ServerResponseException, IOException {
        if (httpClient == null)
            throw new IOException("Trying to executePost while not having a connection/http client");
        HttpResponse response = httpClient.execute(httpPost);
        try {
            verifyServerResponseCode(response);
            verifyServerVersion(response.getFirstHeader(Headers.VERSION));
            verifySessionHeader(response.getFirstHeader(Headers.SESSION_ID));
        } catch (ServerResponseException e) {
            // Ensure response is consumed to allow connection reuse later on
            EntityUtils.consumeQuietly(response.getEntity());
            throw e;
        }
        // Consume response now to allow connection to be reused immediately
        byte[] responseData = EntityUtils.toByteArray(response.getEntity());
        return responseData == null ? null : new ByteArrayInputStream(responseData);
    }

    private void verifyServerResponseCode(HttpResponse response) throws ServerResponseException {
        StatusLine statusLine = response.getStatusLine();
        int statusCode = statusLine.getStatusCode();

        // We use code 261-299 to report errors related to internal transitive errors that the tenants should not care
        // about to avoid masking more serious errors.
        if (statusCode > 199 && statusCode < 260) return;
        if (statusCode == 299) throw new ServerResponseException(429, "Too  many requests.");
        throw new ServerResponseException(statusCode,
                                          tryGetDetailedErrorMessage(response).orElseGet(statusLine::getReasonPhrase));
    }

    private static Optional<String> tryGetDetailedErrorMessage(HttpResponse response) {
        Header contentType = response.getEntity().getContentType();
        if (contentType == null || !contentType.getValue().equalsIgnoreCase("application/json")) return Optional.empty();
        try (InputStream in = response.getEntity().getContent()) {
            JsonNode jsonNode = mapper.readTree(in);
            JsonNode message = jsonNode.get("message");
            if (message == null || message.textValue() == null) return Optional.empty();
            return Optional.of(response.getStatusLine().getReasonPhrase() + " - " + message.textValue());
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private void verifySessionHeader(Header serverHeader) throws ServerResponseException {
        if (serverHeader == null) {
            throw new ServerResponseException("Got no session ID from server.");
        }
        final String serverHeaderVal = serverHeader.getValue().trim();
        if (negotiatedVersion == 3) {
            if (clientId == null || !clientId.equals(serverHeaderVal)) {
                String message = "Running using v3. However, server responds with different session " +
                                 "than client has set; " + serverHeaderVal + " vs client code " + clientId;
                log.severe(message);
                throw new ServerResponseException(message);
            }
            return;
        }
        if (sessionId == null) { //this must be the first request
            log.finer("Got session ID from server: " + serverHeaderVal);
            this.sessionId = serverHeaderVal;
        } else {
            if (!sessionId.equals(serverHeaderVal)) {
                log.info("Request has been routed to a server which does not recognize the client session." +
                         " Most likely cause is upgrading of cluster, transitive error.");
                throw new ServerResponseException("Session ID received from server ('" + serverHeaderVal +
                                                  "') does not match cached session ID ('" + sessionId + "')");
            }
        }
    }

    private void verifyServerVersion(Header serverHeader) throws ServerResponseException {
        if (serverHeader == null) {
            throw new ServerResponseException("Got bad protocol version from server.");
        }
        int serverVersion;
        try {
            serverVersion = Integer.parseInt(serverHeader.getValue());
        } catch (NumberFormatException nfe) {
            throw new ServerResponseException("Got bad protocol version from server: " + nfe.getMessage());
        }
        if (!supportedVersions.contains(serverVersion)) {
            throw new ServerResponseException("Unsupported version: " + serverVersion
                                              + ". Supported versions: " + supportedVersions);
        }
        if (negotiatedVersion == -1) {
            if (log.isLoggable(Level.FINE)) {
                log.log(Level.FINE, "Server decided upon protocol version " + serverVersion + ".");
            }
        }
        this.negotiatedVersion = serverVersion;
    }

    private String createUri() {
        StringBuilder u = new StringBuilder();
        u.append(endpoint.isUseSsl() ? "https://" : "http://");
        u.append(endpoint.getHostname());
        u.append(":").append(endpoint.getPort());
        u.append(PATH);
        u.append(feedParams.toUriParameters());
        return u.toString();
    }

    @Override
    public Endpoint getEndpoint() {
        return endpoint;
    }

    @Override
    public void handshake() throws ServerResponseException, IOException {
        boolean useCompression = false;
        boolean drain = false;
        boolean handshake = true;
        HttpPost httpPost = createPost(drain, useCompression, handshake);

        String oldSessionID = sessionId;
        sessionId = null;
        try (InputStream stream = executePost(httpPost)) {
            if (oldSessionID != null && !oldSessionID.equals(sessionId)) {
                throw new ServerResponseException(
                        "Session ID changed after new handshake, some documents might not be acked to correct thread. "
                                + getEndpoint() + " old " + oldSessionID + " new " + sessionId);
            }
            if (stream == null) {
                log.fine("Stream is null.");
            }
            log.fine("Got session ID " + sessionId);
        }
    }

    @Override
    public void close() {
        try {
            if (httpClient != null)
                httpClient.close();
        }
        catch (IOException e) {
            log.log(Level.WARNING, "Failed closing HTTP client", e);
        }
        httpClient = null;
    }

    /**
     * On re-connect we want to recreate the connection, hence we need a factory.
     */
    public static class HttpClientFactory {

        private final FeedParams feedParams;
        final ConnectionParams connectionParams;
        final boolean useSsl;

        public HttpClientFactory(FeedParams feedParams, ConnectionParams connectionParams, boolean useSsl) {
            this.feedParams = feedParams;
            this.connectionParams = connectionParams;
            this.useSsl = useSsl;
        }

        public CloseableHttpClient createClient() {
            HttpClientBuilder clientBuilder;
            if (connectionParams.useTlsConfigFromEnvironment()) {
                clientBuilder = VespaHttpClientBuilder.create();
            } else {
                clientBuilder = HttpClientBuilder.create();
                if (connectionParams.getSslContext() != null) {
                    setSslContext(clientBuilder, connectionParams.getSslContext());
                } else {
                    SslContextBuilder builder = new SslContextBuilder();
                    if (connectionParams.getPrivateKey() != null && connectionParams.getCertificate() != null) {
                        builder.withKeyStore(connectionParams.getPrivateKey(), connectionParams.getCertificate());
                    }
                    if (connectionParams.getCaCertificates() != null) {
                        builder.withTrustStore(connectionParams.getCaCertificates());
                    }
                    setSslContext(clientBuilder, builder.build());
                }
                if (connectionParams.getHostnameVerifier() != null) {
                    clientBuilder.setSSLHostnameVerifier(connectionParams.getHostnameVerifier());
                }
                clientBuilder.setUserTokenHandler(context -> null); // https://stackoverflow.com/a/42112034/1615280
            }
            clientBuilder.setMaxConnPerRoute(1);
            clientBuilder.setMaxConnTotal(1);
            clientBuilder.setUserAgent(String.format("vespa-http-client (%s)", Vtag.V_TAG_COMPONENT));
            clientBuilder.setDefaultHeaders(Collections.singletonList(new BasicHeader(Headers.CLIENT_VERSION, Vtag.V_TAG_COMPONENT)));
            int millisTotalTimeout = (int) (feedParams.getClientTimeout(TimeUnit.MILLISECONDS) + feedParams.getServerTimeout(TimeUnit.MILLISECONDS));
            RequestConfig.Builder requestConfigBuilder = RequestConfig.custom()
                    .setSocketTimeout(millisTotalTimeout)
                    .setConnectTimeout(millisTotalTimeout);
            if (connectionParams.getProxyHost() != null) {
                requestConfigBuilder.setProxy(new HttpHost(connectionParams.getProxyHost(), connectionParams.getProxyPort()));
            }
            clientBuilder.setDefaultRequestConfig(requestConfigBuilder.build());

            log.fine(() -> "Creating HttpClient:" +
                           " ConnectionTimeout " + connectionParams.getConnectionTimeToLive().getSeconds() + " seconds" +
                           " proxyhost (can be null) " + connectionParams.getProxyHost() + ":" + connectionParams.getProxyPort()
                            + (useSsl ? " using ssl " : " not using ssl")
            );
            return clientBuilder.build();
        }
    }

    // Note: Using deprecated setSslContext() to allow httpclient 4.4 on classpath (e.g unexpected Maven dependency resolution for test classpath)
    @SuppressWarnings("deprecation")
    private static void setSslContext(HttpClientBuilder builder, SSLContext sslContext) {
        builder.setSslcontext(sslContext);
    }

}
