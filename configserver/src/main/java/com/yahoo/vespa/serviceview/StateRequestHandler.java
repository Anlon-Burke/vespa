// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.serviceview;

import com.fasterxml.jackson.jaxrs.json.JacksonJsonProvider;
import com.google.inject.Inject;
import com.yahoo.cloud.config.ConfigserverConfig;
import com.yahoo.container.jdisc.ThreadedHttpRequestHandler;
import ai.vespa.http.DomainName;
import ai.vespa.http.HttpURL;
import ai.vespa.http.HttpURL.Path;
import ai.vespa.http.HttpURL.Query;
import ai.vespa.http.HttpURL.Scheme;
import com.yahoo.restapi.RestApi;
import com.yahoo.restapi.RestApiRequestHandler;
import com.yahoo.vespa.serviceview.bindings.ApplicationView;
import com.yahoo.vespa.serviceview.bindings.ConfigClient;
import com.yahoo.vespa.serviceview.bindings.HealthClient;
import com.yahoo.vespa.serviceview.bindings.ModelResponse;
import org.glassfish.jersey.client.ClientProperties;
import org.glassfish.jersey.client.proxy.WebResourceFactory;

import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientRequestFilter;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.HttpHeaders;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;

/**
 * A web service to discover and proxy Vespa service state info.
 *
 * @author Steinar Knutsen
 * @author bjorncs
 */
public class StateRequestHandler extends RestApiRequestHandler<StateRequestHandler> {

    private static final String USER_AGENT = "service-view-config-server-client";
    private static final String SINGLE_API_LINK = "url";

    @SuppressWarnings("removal")
    private final Client client = new ai.vespa.util.http.VespaClientBuilderFactory()
            .newBuilder()
            .property(ClientProperties.CONNECT_TIMEOUT, 10000)
            .property(ClientProperties.READ_TIMEOUT, 10000)
            .register(JacksonJsonProvider.class)
            .register((ClientRequestFilter) ctx -> ctx.getHeaders().put(HttpHeaders.USER_AGENT, List.of(USER_AGENT)))
            .build();

    private final int restApiPort;

    private static class GiveUpLinkRetargetingException extends Exception {
        public GiveUpLinkRetargetingException(Throwable reason) {
            super(reason);
        }

        public GiveUpLinkRetargetingException(String message) {
            super(message);
        }
    }

    @Inject
    public StateRequestHandler(ThreadedHttpRequestHandler.Context context,
                               ConfigserverConfig configserverConfig) {
        super(context, StateRequestHandler::createRestApiDefinition);
        this.restApiPort = configserverConfig.httpport();
    }

    @Override
    protected void destroy() {
        client.close();
        super.destroy();
    }

    private static RestApi createRestApiDefinition(StateRequestHandler self) {
        return RestApi.builder()
                .addRoute(RestApi.route("/serviceview/v1")
                        .get(self::getDefaultUserInfo))
                .addRoute(RestApi.route("/serviceview/v1/")
                        .get(self::getDefaultUserInfo))
                .addRoute(RestApi.route("/serviceview/v1/tenant/{tenantName}/application/{applicationName}/environment/{environmentName}/region/{regionName}/instance/{instanceName}")
                        .get(self::getUserInfo))
                .addRoute(RestApi.route("/serviceview/v1/tenant/{tenantName}/application/{applicationName}/environment/{environmentName}/region/{regionName}/instance/{instanceName}/service/{serviceIdentifier}/{*}")
                        .get(self::singleService))
                .registerJacksonResponseEntity(HashMap.class)
                .registerJacksonResponseEntity(ApplicationView.class)
                .build();
    }

    private ApplicationView getDefaultUserInfo(RestApi.RequestContext context) {
        return getUserInfo(context.baseRequestURL(), "default", "default", "default", "default", "default");
    }

    private ApplicationView getUserInfo(RestApi.RequestContext context) {
        String tenantName = context.pathParameters().getStringOrThrow("tenantName");
        String applicationName = context.pathParameters().getStringOrThrow("applicationName");
        String environmentName = context.pathParameters().getStringOrThrow("environmentName");
        String regionName = context.pathParameters().getStringOrThrow("regionName");
        String instanceName = context.pathParameters().getStringOrThrow("instanceName");
        return getUserInfo(context.baseRequestURL(), tenantName, applicationName, environmentName, regionName, instanceName);
    }

    public HashMap<?, ?> singleService(RestApi.RequestContext context) {
        String tenantName = context.pathParameters().getStringOrThrow("tenantName");
        String applicationName = context.pathParameters().getStringOrThrow("applicationName");
        String environmentName = context.pathParameters().getStringOrThrow("environmentName");
        String regionName = context.pathParameters().getStringOrThrow("regionName");
        String instanceName = context.pathParameters().getStringOrThrow("instanceName");
        String identifier = context.pathParameters().getStringOrThrow("serviceIdentifier");
        Path apiParams = context.pathParameters().getRest().orElse(Path.empty());
        Query apiQuery = context.queryParameters().getFullQuery();
        return singleService(context.baseRequestURL(), tenantName, applicationName, environmentName, regionName, instanceName, identifier, apiParams, apiQuery);
    }

    protected ApplicationView getUserInfo(HttpURL url, String tenantName, String applicationName, String environmentName, String regionName, String instanceName) {
        ServiceModel model = new ServiceModel(
                getModelConfig(tenantName, applicationName, environmentName, regionName, instanceName));
        return model.showAllClusters(
                baseUri(url).toString(),
                applicationIdentifier(tenantName, applicationName, environmentName, regionName, instanceName));
    }

    protected ModelResponse getModelConfig(String tenant, String application, String environment, String region, String instance) {
        WebTarget target = client.target("http://localhost:" + restApiPort + "/");
        ConfigClient resource = WebResourceFactory.newResource(ConfigClient.class, target);
        return resource.getServiceModel(tenant, application, environment, region, instance);
    }

    protected HashMap<?, ?> singleService(
            HttpURL url, String tenantName, String applicationName, String environmentName, String regionName, String instanceName, String identifier, Path path, Query query) {
        ServiceModel model = new ServiceModel(getModelConfig(tenantName, applicationName, environmentName, regionName, instanceName));
        Service s = model.getService(identifier);
        int requestedPort = s.matchIdentifierWithPort(identifier);
        HealthClient resource = getHealthClient(path, s, requestedPort, query, client);
        HashMap<?, ?> apiResult = resource.getHealthInfo();
        rewriteResourceLinks(url, apiResult, model, s, applicationIdentifier(tenantName, applicationName, environmentName, regionName, instanceName), identifier);
        return apiResult;
    }

    protected HealthClient getHealthClient(Path apiParams, Service s, int requestedPort, Query query, Client client) {
        URI uri = HttpURL.create(Scheme.http, DomainName.of(s.host), requestedPort, apiParams, query).asURI();
        WebTarget target = client.target(uri);
        return WebResourceFactory.newResource(HealthClient.class, target);
    }

    private String applicationIdentifier(String tenant, String application, String environment, String region, String instance) {
        return "tenant/" + tenant
                + "/application/" + application
                + "/environment/" + environment
                + "/region/" + region
                + "/instance/" + instance;
    }

    private void rewriteResourceLinks(HttpURL url,
                                      Object apiResult,
                                      ServiceModel model,
                                      Service self,
                                      String applicationIdentifier,
                                      String incomingIdentifier) {
        if (apiResult instanceof List) {
            for (@SuppressWarnings("unchecked") ListIterator<Object> i = ((List<Object>) apiResult).listIterator(); i.hasNext();) {
                Object resource = i.next();
                if (resource instanceof String) {
                    try {
                        StringBuilder buffer = linkBuffer(url, applicationIdentifier);
                        // if it points to a port and host not part of the application, rewriting will not occur, so this is kind of safe
                        retarget(model, self, buffer, (String) resource);
                        i.set(buffer.toString());
                    } catch (GiveUpLinkRetargetingException e) {
                        break; // assume relatively homogenous lists when doing rewrites to avoid freezing up on scanning long lists
                    }
                } else {
                    rewriteResourceLinks(url, resource, model, self, applicationIdentifier, incomingIdentifier);
                }
            }
        } else if (apiResult instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<Object, Object> api = (Map<Object, Object>) apiResult;
            for (Map.Entry<Object, Object> entry : api.entrySet()) {
                if (SINGLE_API_LINK.equals(entry.getKey()) && entry.getValue() instanceof String) {
                    try {
                        rewriteSingleLink(entry, model, self, linkBuffer(url, applicationIdentifier));
                    } catch (GiveUpLinkRetargetingException e) {
                        // NOP
                    }
                } else if ("link".equals(entry.getKey()) && entry.getValue() instanceof String) {
                    buildSingleLink(entry, linkBuffer(url, applicationIdentifier), incomingIdentifier);
                } else {
                    rewriteResourceLinks(url, entry.getValue(), model, self, applicationIdentifier, incomingIdentifier);
                }
            }
        }
    }

    private void buildSingleLink(Map.Entry<Object, Object> entry,
                                 StringBuilder newUri,
                                 String incomingIdentifier) {
        newUri.append("/service/")
                .append(incomingIdentifier);
        newUri.append(entry.getValue());
        entry.setValue(newUri.toString());
    }

    private void addQuery(String query, StringBuilder newUri) {
        if (query != null && query.length() > 0) {
            newUri.append('?').append(query);
        }
    }

    private StringBuilder linkBuffer(HttpURL url, String applicationIdentifier) {
        return new StringBuilder(baseUri(url).appendPath(Path.parse(applicationIdentifier)).toString());
    }

    private void rewriteSingleLink(Map.Entry<Object, Object> entry,
                                   ServiceModel model,
                                   Service self,
                                   StringBuilder newUri) throws GiveUpLinkRetargetingException {
        String url = (String) entry.getValue();
        retarget(model, self, newUri, url);
        entry.setValue(newUri.toString());
    }

    private void retarget(ServiceModel model, Service self, StringBuilder newUri, String url) throws GiveUpLinkRetargetingException {
        URI link;
        try {
            link = new URI(url);
        } catch (URISyntaxException e) {
            throw new GiveUpLinkRetargetingException(e);
        }
        if (!link.isAbsolute()) {
            throw new GiveUpLinkRetargetingException("This rewriting only supports absolute URIs.");
        }
        int linkPort = link.getPort();
        if (linkPort == -1) {
            linkPort = 80;
        }
        Service s;
        try {
            s = model.resolve(link.getHost(), linkPort, self);
        } catch (IllegalArgumentException e) {
            throw new GiveUpLinkRetargetingException(e);
        }
        newUri.append("/service/").append(s.getIdentifier(linkPort));
        newUri.append(link.getRawPath());
    }

    private static HttpURL baseUri(HttpURL url) {
        return url.withPath(Path.parse("/serviceview/v1/"));
    }
}
