// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.controller.restapi;

import ai.vespa.hosted.api.MultiPartStreamer;
import com.yahoo.application.container.handler.Request;
import com.yahoo.config.provision.SystemName;
import com.yahoo.vespa.hosted.controller.api.integration.user.User;
import com.yahoo.vespa.hosted.controller.api.role.Role;
import com.yahoo.vespa.hosted.controller.api.role.SecurityContext;
import com.yahoo.vespa.hosted.controller.api.role.SimplePrincipal;
import com.yahoo.yolean.Exceptions;

import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Controller container test with services.xml which accommodates cloud user management.
 *
 * @author jonmv
 */
public class ControllerContainerCloudTest extends ControllerContainerTest {

    @Override
    protected SystemName system() {
        return SystemName.Public;
    }

    @Override
    protected String variablePartXml() {
        return "  <component id='com.yahoo.vespa.hosted.controller.security.CloudAccessControlRequests'/>\n" +
               "  <component id='com.yahoo.vespa.hosted.controller.security.CloudAccessControl'/>\n" +

               "  <handler id='com.yahoo.vespa.hosted.controller.restapi.application.ApplicationApiHandler'>\n" +
               "    <binding>http://*/application/v4/*</binding>\n" +
               "  </handler>\n" +

               "  <handler id='com.yahoo.vespa.hosted.controller.restapi.zone.v1.ZoneApiHandler'>\n" +
               "    <binding>http://*/zone/v1</binding>\n" +
               "    <binding>http://*/zone/v1/*</binding>\n" +
               "  </handler>\n" +

               "  <http>\n" +
               "    <server id='default' port='8080' />\n" +
               "    <filtering>\n" +
               "      <request-chain id='default'>\n" +
               "        <filter id='com.yahoo.vespa.hosted.controller.restapi.filter.ControllerAuthorizationFilter'/>\n" +
               "        <binding>http://*/*</binding>\n" +
               "      </request-chain>\n" +
               "    </filtering>\n" +
               "  </http>\n";
    }

    protected static final String accessDenied = "{\n" +
                                                 "  \"code\" : 403,\n" +
                                                 "  \"message\" : \"Access denied\"\n" +
                                                 "}";

    protected RequestBuilder request(String path) { return new RequestBuilder(path, Request.Method.GET); }
    protected RequestBuilder request(String path, Request.Method method) { return new RequestBuilder(path, method); }

    protected static class RequestBuilder implements Supplier<Request> {
        private final String path;
        private final Request.Method method;
        private byte[] data = new byte[0];
        private Principal principal = () -> "user@test";
        private User user;
        private Set<Role> roles = Set.of(Role.everyone());
        private String contentType;

        private RequestBuilder(String path, Request.Method method) {
            this.path = path;
            this.method = method;
        }

        public RequestBuilder contentType(String contentType) { this.contentType = contentType; return this; }
        public RequestBuilder data(MultiPartStreamer streamer) {
            return Exceptions.uncheck(() -> data(streamer.data().readAllBytes()).contentType(streamer.contentType()));
        }
        public RequestBuilder data(byte[] data) { this.data = data; return this; }
        public RequestBuilder data(String data) { this.data = data.getBytes(StandardCharsets.UTF_8); return this; }
        public RequestBuilder principal(String principal) { this.principal = new SimplePrincipal(principal); return this; }
        public RequestBuilder user(User user) { this.user = user; return this; }
        public RequestBuilder roles(Set<Role> roles) { this.roles = roles; return this; }
        public RequestBuilder roles(Role... roles) { return roles(Set.of(roles)); }

        @Override
        public Request get() {
            Request request = new Request("http://localhost:8080" + path, data, method, principal);
            request.getAttributes().put(SecurityContext.ATTRIBUTE_NAME, new SecurityContext(principal, roles));
            if (user != null) {
                Map<String, String> userAttributes = new HashMap<>();
                userAttributes.put("email", user.email());
                if (user.name() != null)
                    userAttributes.put("name", user.name());
                if (user.nickname() != null)
                    userAttributes.put("nickname", user.nickname());
                if (user.picture() != null)
                    userAttributes.put("picture", user.picture());
                request.getAttributes().put(User.ATTRIBUTE_NAME, Map.copyOf(userAttributes));
            }
            request.getHeaders().put("Content-Type", contentType);
            return request;
        }
    }

}
