// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.serviceview;

import ai.vespa.http.HttpURL;
import ai.vespa.http.HttpURL.Query;
import com.yahoo.cloud.config.ConfigserverConfig;
import com.yahoo.jdisc.test.MockMetric;
import ai.vespa.http.HttpURL.Path;
import com.yahoo.vespa.serviceview.bindings.ApplicationView;
import com.yahoo.vespa.serviceview.bindings.HealthClient;
import com.yahoo.vespa.serviceview.bindings.ModelResponse;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import javax.ws.rs.client.Client;
import java.net.URI;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

import static org.junit.Assert.assertEquals;

/**
 * Functional test for {@link StateRequestHandler}.
 *
 * @author Steinar Knutsen
 * @author bjorncs
 */
public class StateRequestHandlerTest {

    private static final String EXTERNAL_BASE_URI = "http://someserver:8080/serviceview/v1/";

    private static class TestHandler extends StateRequestHandler {
        private static final String BASE_URI = "http://vespa.yahoo.com:8080/state/v1";

        TestHandler(ConfigserverConfig config) {
            super(new Context(Executors.newSingleThreadExecutor(), new MockMetric()), config);
        }

        @Override
        protected ModelResponse getModelConfig(String tenant, String application, String environment, String region, String instance) {
            return ServiceModelTest.syntheticModelResponse();
        }

        @Override
        protected HealthClient getHealthClient(Path apiParams, Service s, int requestedPort, Query query, Client client) {
            HealthClient healthClient = Mockito.mock(HealthClient.class);
            HashMap<Object, Object> dummyHealthData = new HashMap<>();
            HashMap<String, String> dummyLink = new HashMap<>();
            dummyLink.put("url", BASE_URI);
            dummyHealthData.put("resources", Collections.singletonList(dummyLink));
            Mockito.when(healthClient.getHealthInfo()).thenReturn(dummyHealthData);
            return healthClient;
        }
    }

    private StateRequestHandler testHandler;
    private ServiceModel correspondingModel;

    @Before
    public void setUp() throws Exception {
        testHandler = new TestHandler(new ConfigserverConfig(new ConfigserverConfig.Builder()));
        correspondingModel = new ServiceModel(ServiceModelTest.syntheticModelResponse());
    }

    @After
    public void tearDown() {
        testHandler = null;
        correspondingModel = null;
    }

    @Test
    public final void test() {
        Service s = correspondingModel.resolve("vespa.yahoo.com", 8080, null);
        String api = "/state/v1";
        HashMap<?, ?> boom = testHandler.singleService(HttpURL.from(URI.create("http://someserver:8080")), "default", "default", "default", "default", "default", s.getIdentifier(8080), Path.parse(api), Query.empty().add("foo", "bar"));
        assertEquals(EXTERNAL_BASE_URI + "tenant/default/application/default/environment/default/region/default/instance/default/service/" + s.getIdentifier(8080) + api,
                     ((Map<?, ?>) ((List<?>) boom.get("resources")).get(0)).get("url"));
    }

    @Test
    public final void testLinkEquality() {
        ApplicationView explicitParameters = testHandler.getUserInfo(HttpURL.from(URI.create("http://someserver:8080")), "default", "default", "default", "default", "default");
        assertEquals(EXTERNAL_BASE_URI + "tenant/default/application/default/environment/default/region/default/instance" +
                        "/default/service/container-clustercontroller-2ul67p8psr451t3w8kdd0qwgg/state/v1/",
                explicitParameters.clusters.get(0).services.get(0).url);
    }

}
