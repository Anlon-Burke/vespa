// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.jdisc.http.server.jetty;

import com.google.inject.Inject;
import com.yahoo.jdisc.Metric;
import com.yahoo.jdisc.http.ConnectorConfig;
import com.yahoo.jdisc.http.ssl.SslContextFactoryProvider;
import com.yahoo.security.tls.MixedMode;
import com.yahoo.security.tls.TransportSecurityUtils;
import org.eclipse.jetty.alpn.server.ALPNServerConnectionFactory;
import org.eclipse.jetty.http2.server.HTTP2ServerConnectionFactory;
import org.eclipse.jetty.server.ConnectionFactory;
import org.eclipse.jetty.server.DetectorConnectionFactory;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.ProxyConnectionFactory;
import org.eclipse.jetty.server.SecureRequestCustomizer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.eclipse.jetty.util.ssl.SslContextFactory;

import java.util.Collection;
import java.util.List;

/**
 * @author Einar M R Rosenvinge
 * @author bjorncs
 */
public class ConnectorFactory {

    private final ConnectorConfig connectorConfig;
    private final SslContextFactoryProvider sslContextFactoryProvider;

    @Inject
    public ConnectorFactory(ConnectorConfig connectorConfig,
                            SslContextFactoryProvider sslContextFactoryProvider) {
        runtimeConnectorConfigValidation(connectorConfig);
        this.connectorConfig = connectorConfig;
        this.sslContextFactoryProvider = sslContextFactoryProvider;
    }

    // Perform extra connector config validation that can only be performed at runtime,
    // e.g. due to TLS configuration through environment variables.
    private static void runtimeConnectorConfigValidation(ConnectorConfig config) {
        validateProxyProtocolConfiguration(config);
        validateSecureRedirectConfig(config);
    }

    private static void validateProxyProtocolConfiguration(ConnectorConfig config) {
        ConnectorConfig.ProxyProtocol proxyProtocolConfig = config.proxyProtocol();
        if (proxyProtocolConfig.enabled()) {
            boolean tlsMixedModeEnabled = TransportSecurityUtils.getInsecureMixedMode() != MixedMode.DISABLED;
            if (!isSslEffectivelyEnabled(config) || tlsMixedModeEnabled) {
                throw new IllegalArgumentException("Proxy protocol can only be enabled if connector is effectively HTTPS only");
            }
        }
    }

    private static void validateSecureRedirectConfig(ConnectorConfig config) {
        if (config.secureRedirect().enabled() && isSslEffectivelyEnabled(config)) {
            throw new IllegalArgumentException("Secure redirect can only be enabled on connectors without HTTPS");
        }
    }

    public ConnectorConfig getConnectorConfig() {
        return connectorConfig;
    }

    public ServerConnector createConnector(final Metric metric, final Server server, JettyConnectionLogger connectionLogger) {
        ServerConnector connector = new JDiscServerConnector(
                connectorConfig, metric, server, connectionLogger, createConnectionFactories(metric).toArray(ConnectionFactory[]::new));
        connector.setPort(connectorConfig.listenPort());
        connector.setName(connectorConfig.name());
        connector.setAcceptQueueSize(connectorConfig.acceptQueueSize());
        connector.setReuseAddress(connectorConfig.reuseAddress());
        connector.setIdleTimeout((long)(connectorConfig.idleTimeout() * 1000.0));
        return connector;
    }

    private List<ConnectionFactory> createConnectionFactories(Metric metric) {
        if (!isSslEffectivelyEnabled(connectorConfig)) {
            return List.of(newHttp1ConnectionFactory());
        } else if (connectorConfig.ssl().enabled()) {
            return connectionFactoriesForHttps(metric);
        } else if (TransportSecurityUtils.isTransportSecurityEnabled()) {
            switch (TransportSecurityUtils.getInsecureMixedMode()) {
                case TLS_CLIENT_MIXED_SERVER:
                case PLAINTEXT_CLIENT_MIXED_SERVER:
                    return connectionFactoriesForHttpsMixedMode(metric);
                case DISABLED:
                    return connectionFactoriesForHttps(metric);
                default:
                    throw new IllegalStateException();
            }
        } else {
            return List.of(newHttp1ConnectionFactory());
        }
    }

    private List<ConnectionFactory> connectionFactoriesForHttps(Metric metric) {
        ConnectorConfig.ProxyProtocol proxyProtocolConfig = connectorConfig.proxyProtocol();
        HttpConnectionFactory http1Factory = newHttp1ConnectionFactory();
        if (connectorConfig.http2Enabled()) {
            HTTP2ServerConnectionFactory http2Factory = newHttp2ConnectionFactory();
            ALPNServerConnectionFactory alpnFactory = newAlpnConnectionFactory(List.of(http1Factory, http2Factory), http1Factory);
            SslConnectionFactory sslFactory = newSslConnectionFactory(metric, alpnFactory);
            if (proxyProtocolConfig.enabled()) {
                ProxyConnectionFactory proxyProtocolFactory = newProxyProtocolConnectionFactory(sslFactory);
                if (proxyProtocolConfig.mixedMode()) {
                    DetectorConnectionFactory detectorFactory = newDetectorConnectionFactory(sslFactory);
                    return List.of(detectorFactory, proxyProtocolFactory, sslFactory, alpnFactory, http1Factory, http2Factory);
                } else {
                    return List.of(proxyProtocolFactory, sslFactory, alpnFactory, http1Factory, http2Factory);
                }
            } else {
                return List.of(sslFactory, alpnFactory, http1Factory, http2Factory);
            }
        } else {
            SslConnectionFactory sslFactory = newSslConnectionFactory(metric, http1Factory);
            if (proxyProtocolConfig.enabled()) {
                ProxyConnectionFactory proxyProtocolFactory = newProxyProtocolConnectionFactory(sslFactory);
                if (proxyProtocolConfig.mixedMode()) {
                    DetectorConnectionFactory detectorFactory = newDetectorConnectionFactory(sslFactory);
                    return List.of(detectorFactory, proxyProtocolFactory, sslFactory, http1Factory);
                } else {
                    return List.of(proxyProtocolFactory, sslFactory, http1Factory);
                }
            } else {
                return List.of(sslFactory, http1Factory);
            }
        }
    }

    private List<ConnectionFactory> connectionFactoriesForHttpsMixedMode(Metric metric) {
        // No support for proxy-protocol/http2 when using HTTP with TLS mixed mode
        HttpConnectionFactory httpFactory = newHttp1ConnectionFactory();
        SslConnectionFactory sslFactory = newSslConnectionFactory(metric, httpFactory);
        DetectorConnectionFactory detectorFactory = newDetectorConnectionFactory(sslFactory);
        return List.of(detectorFactory, httpFactory, sslFactory);
    }

    private HttpConfiguration newHttpConfiguration() {
        HttpConfiguration httpConfig = new HttpConfiguration();
        httpConfig.setSendDateHeader(true);
        httpConfig.setSendServerVersion(false);
        httpConfig.setSendXPoweredBy(false);
        httpConfig.setHeaderCacheSize(connectorConfig.headerCacheSize());
        httpConfig.setOutputBufferSize(connectorConfig.outputBufferSize());
        httpConfig.setRequestHeaderSize(connectorConfig.requestHeaderSize());
        httpConfig.setResponseHeaderSize(connectorConfig.responseHeaderSize());
        if (isSslEffectivelyEnabled(connectorConfig)) {
            httpConfig.addCustomizer(new SecureRequestCustomizer());
        }
        return httpConfig;
    }

    private HttpConnectionFactory newHttp1ConnectionFactory() {
        return new HttpConnectionFactory(newHttpConfiguration());
    }

    private HTTP2ServerConnectionFactory newHttp2ConnectionFactory() {
        return new HTTP2ServerConnectionFactory(newHttpConfiguration());
    }

    private SslConnectionFactory newSslConnectionFactory(Metric metric, ConnectionFactory wrappedFactory) {
        SslContextFactory ctxFactory = sslContextFactoryProvider.getInstance(connectorConfig.name(), connectorConfig.listenPort());
        SslConnectionFactory connectionFactory = new SslConnectionFactory(ctxFactory, wrappedFactory.getProtocol());
        connectionFactory.addBean(new SslHandshakeFailedListener(metric, connectorConfig.name(), connectorConfig.listenPort()));
        return connectionFactory;
    }

    private ALPNServerConnectionFactory newAlpnConnectionFactory(Collection<ConnectionFactory> alternatives,
                                                                 ConnectionFactory defaultFactory) {
        String[] protocols = alternatives.stream().map(ConnectionFactory::getProtocol).toArray(String[]::new);
        ALPNServerConnectionFactory factory = new ALPNServerConnectionFactory(protocols);
        factory.setDefaultProtocol(defaultFactory.getProtocol());
        return factory;
    }

    private DetectorConnectionFactory newDetectorConnectionFactory(ConnectionFactory.Detecting... alternatives) {
        // Note: Detector connection factory with single alternative will fallback to next protocol in connection factory list
        return new DetectorConnectionFactory(alternatives);
    }

    private ProxyConnectionFactory newProxyProtocolConnectionFactory(ConnectionFactory wrappedFactory) {
        return new ProxyConnectionFactory(wrappedFactory.getProtocol());
    }

    private static boolean isSslEffectivelyEnabled(ConnectorConfig config) {
        return config.ssl().enabled()
                || (config.implicitTlsEnabled() && TransportSecurityUtils.isTransportSecurityEnabled());
    }

}
