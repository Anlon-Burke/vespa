// Copyright Verizon Media. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.orchestrator.resources;

import com.google.common.util.concurrent.UncheckedTimeoutException;
import com.yahoo.container.jdisc.HttpRequest;
import com.yahoo.container.jdisc.HttpResponse;
import com.yahoo.container.jdisc.LoggingRequestHandler;
import com.yahoo.jdisc.Metric;
import com.yahoo.jdisc.http.HttpRequest.Method;
import com.yahoo.jdisc.test.MockMetric;
import com.yahoo.jdisc.test.TestTimer;
import com.yahoo.test.json.JsonTestHelper;
import com.yahoo.vespa.applicationmodel.ApplicationInstance;
import com.yahoo.vespa.applicationmodel.ApplicationInstanceId;
import com.yahoo.vespa.applicationmodel.ApplicationInstanceReference;
import com.yahoo.vespa.applicationmodel.ClusterId;
import com.yahoo.vespa.applicationmodel.ConfigId;
import com.yahoo.vespa.applicationmodel.HostName;
import com.yahoo.vespa.applicationmodel.ServiceCluster;
import com.yahoo.vespa.applicationmodel.ServiceInstance;
import com.yahoo.vespa.applicationmodel.ServiceStatus;
import com.yahoo.vespa.applicationmodel.ServiceType;
import com.yahoo.vespa.applicationmodel.TenantId;
import com.yahoo.vespa.curator.mock.MockCurator;
import com.yahoo.vespa.flags.InMemoryFlagSource;
import com.yahoo.vespa.orchestrator.DummyAntiServiceMonitor;
import com.yahoo.vespa.orchestrator.Host;
import com.yahoo.vespa.orchestrator.HostNameNotFoundException;
import com.yahoo.vespa.orchestrator.OrchestrationException;
import com.yahoo.vespa.orchestrator.Orchestrator;
import com.yahoo.vespa.orchestrator.OrchestratorContext;
import com.yahoo.vespa.orchestrator.OrchestratorImpl;
import com.yahoo.vespa.orchestrator.controller.ClusterControllerClientFactoryMock;
import com.yahoo.vespa.orchestrator.model.ApplicationApi;
import com.yahoo.vespa.orchestrator.model.ApplicationApiFactory;
import com.yahoo.vespa.orchestrator.policy.HostStateChangeDeniedException;
import com.yahoo.vespa.orchestrator.policy.Policy;
import com.yahoo.vespa.orchestrator.policy.SuspensionReasons;
import com.yahoo.vespa.orchestrator.restapi.wire.GetHostResponse;
import com.yahoo.vespa.orchestrator.restapi.wire.PatchHostRequest;
import com.yahoo.vespa.orchestrator.restapi.wire.PatchHostResponse;
import com.yahoo.vespa.orchestrator.restapi.wire.UpdateHostResponse;
import com.yahoo.vespa.orchestrator.status.ApplicationLock;
import com.yahoo.vespa.orchestrator.status.HostInfo;
import com.yahoo.vespa.orchestrator.status.HostStatus;
import com.yahoo.vespa.orchestrator.status.StatusService;
import com.yahoo.vespa.orchestrator.status.ZkStatusService;
import com.yahoo.vespa.service.monitor.ServiceModel;
import com.yahoo.vespa.service.monitor.ServiceMonitor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @author hakonhall
 * @author bjorncs
 */
class HostRequestHandlerTest {

    private static final Clock clock = mock(Clock.class);
    private static final int SERVICE_MONITOR_CONVERGENCE_LATENCY_SECONDS = 0;
    private static final TenantId TENANT_ID = new TenantId("tenantId");
    private static final ApplicationInstanceId APPLICATION_INSTANCE_ID = new ApplicationInstanceId("applicationId");
    private static final ServiceMonitor serviceMonitor = mock(ServiceMonitor.class);
    private static final StatusService EVERY_HOST_IS_UP_HOST_STATUS_SERVICE = new ZkStatusService(
            new MockCurator(), mock(Metric.class), new TestTimer(), new DummyAntiServiceMonitor());
    private static final ApplicationApiFactory applicationApiFactory = new ApplicationApiFactory(3, clock);

    static {
        when(serviceMonitor.getApplication(any(HostName.class)))
                .thenReturn(Optional.of(
                        new ApplicationInstance(
                                TENANT_ID,
                                APPLICATION_INSTANCE_ID,
                                Set.of())));
    }

    private static final ServiceMonitor alwaysEmptyServiceMonitor = new ServiceMonitor() {
        private final ServiceModel emptyServiceModel = new ServiceModel(Map.of());

        @Override
        public ServiceModel getServiceModelSnapshot() {
            return emptyServiceModel;
        }
    };

    private static class AlwaysAllowPolicy implements Policy {
        @Override
        public SuspensionReasons grantSuspensionRequest(OrchestratorContext context, ApplicationApi applicationApi) {
            return SuspensionReasons.nothingNoteworthy();
        }

        @Override
        public void releaseSuspensionGrant(OrchestratorContext context, ApplicationApi application) {
        }

        @Override
        public void acquirePermissionToRemove(OrchestratorContext context, ApplicationApi applicationApi) {
        }

        @Override
        public void releaseSuspensionGrant(
                OrchestratorContext context, ApplicationInstance applicationInstance,
                HostName hostName,
                ApplicationLock hostStatusRegistry) {
        }
    }

    private final OrchestratorImpl alwaysAllowOrchestrator = createAlwaysAllowOrchestrator(clock);
    private final OrchestratorImpl hostNotFoundOrchestrator = createHostNotFoundOrchestrator(clock);
    private final OrchestratorImpl alwaysRejectOrchestrator = createAlwaysRejectResolver(clock);

    @BeforeEach
    void setUp() {
        when(clock.instant()).thenReturn(Instant.now());
    }

    static OrchestratorImpl createAlwaysAllowOrchestrator(Clock clock) {
        return new OrchestratorImpl(
                new AlwaysAllowPolicy(),
                new ClusterControllerClientFactoryMock(),
                EVERY_HOST_IS_UP_HOST_STATUS_SERVICE,
                serviceMonitor,
                SERVICE_MONITOR_CONVERGENCE_LATENCY_SECONDS,
                clock,
                applicationApiFactory,
                new InMemoryFlagSource());
    }

    static OrchestratorImpl createHostNotFoundOrchestrator(Clock clock) {
        return new OrchestratorImpl(
                new AlwaysAllowPolicy(),
                new ClusterControllerClientFactoryMock(),
                EVERY_HOST_IS_UP_HOST_STATUS_SERVICE,
                alwaysEmptyServiceMonitor,
                SERVICE_MONITOR_CONVERGENCE_LATENCY_SECONDS,
                clock,
                applicationApiFactory,
                new InMemoryFlagSource());
    }

    static OrchestratorImpl createAlwaysRejectResolver(Clock clock) {
        return new OrchestratorImpl(
                new AlwaysFailPolicy(),
                new ClusterControllerClientFactoryMock(),
                EVERY_HOST_IS_UP_HOST_STATUS_SERVICE,
                serviceMonitor,
                SERVICE_MONITOR_CONVERGENCE_LATENCY_SECONDS,
                clock,
                applicationApiFactory,
                new InMemoryFlagSource());
    }

    @Test
    void returns_200_on_success() throws IOException {
        HostRequestHandler handler = createHandler(alwaysAllowOrchestrator);

        HttpResponse response = executeRequest(handler, Method.PUT, "/orchestrator/v1/hosts/hostname/suspended", null);
        UpdateHostResponse updateHostResponse = parseResponseContent(handler, response, UpdateHostResponse.class);
        assertEquals("hostname", updateHostResponse.hostname());
    }

    @Test
    void throws_404_when_host_unknown() throws IOException {
        HostRequestHandler handler = createHandler(hostNotFoundOrchestrator);

        HttpResponse response = executeRequest(handler, Method.PUT, "/orchestrator/v1/hosts/hostname/suspended", null);
        assertEquals(404, response.getStatus());
    }

    private static class AlwaysFailPolicy implements Policy {
        @Override
        public SuspensionReasons grantSuspensionRequest(OrchestratorContext context, ApplicationApi applicationApi) throws HostStateChangeDeniedException {
            throw newHostStateChangeDeniedException();
        }

        @Override
        public void releaseSuspensionGrant(OrchestratorContext context, ApplicationApi application) throws HostStateChangeDeniedException {
            throw newHostStateChangeDeniedException();
        }

        @Override
        public void acquirePermissionToRemove(OrchestratorContext context, ApplicationApi applicationApi) throws HostStateChangeDeniedException {
            throw newHostStateChangeDeniedException();
        }

        @Override
        public void releaseSuspensionGrant(
                OrchestratorContext context, ApplicationInstance applicationInstance,
                HostName hostName,
                ApplicationLock hostStatusRegistry) throws HostStateChangeDeniedException {
            throw newHostStateChangeDeniedException();
        }

        private static HostStateChangeDeniedException newHostStateChangeDeniedException() {
            return new HostStateChangeDeniedException(
                    new HostName("some-host"),
                    "impossible-policy",
                    "This policy rejects all requests");
        }
    }

    @Test
    void throws_409_when_request_rejected_by_policies() throws IOException {
        HostRequestHandler handler = createHandler(alwaysRejectOrchestrator);

        HttpResponse response = executeRequest(handler, Method.PUT, "/orchestrator/v1/hosts/hostname/suspended", null);
        assertEquals(409, response.getStatus());
    }

    @Test
    void patch_state_may_throw_bad_request() throws IOException {
        Orchestrator orchestrator = mock(Orchestrator.class);
        HostRequestHandler handler = createHandler(orchestrator);

        PatchHostRequest request = new PatchHostRequest();
        request.state = "bad state";

        HttpResponse response = executeRequest(handler, Method.PATCH, "/orchestrator/v1/hosts/hostname", request);
        assertEquals(400, response.getStatus());
    }

    @Test
    void patch_works() throws OrchestrationException, IOException {
        Orchestrator orchestrator = mock(Orchestrator.class);
        HostRequestHandler handler = createHandler(orchestrator);

        String hostNameString = "hostname";
        PatchHostRequest request = new PatchHostRequest();
        request.state = "NO_REMARKS";

        HttpResponse httpResponse = executeRequest(handler, Method.PATCH, "/orchestrator/v1/hosts/hostname", request);
        PatchHostResponse response = parseResponseContent(handler, httpResponse, PatchHostResponse.class);
        assertEquals(response.description, "ok");
        verify(orchestrator, times(1)).setNodeStatus(new HostName(hostNameString), HostStatus.NO_REMARKS);
    }

    @Test
    void patch_handles_exception_in_orchestrator() throws OrchestrationException, IOException {
        Orchestrator orchestrator = mock(Orchestrator.class);
        HostRequestHandler handler = createHandler(orchestrator);

        String hostNameString = "hostname";
        PatchHostRequest request = new PatchHostRequest();
        request.state = "NO_REMARKS";

        doThrow(new OrchestrationException("error")).when(orchestrator).setNodeStatus(new HostName(hostNameString), HostStatus.NO_REMARKS);
        HttpResponse httpResponse = executeRequest(handler, Method.PATCH, "/orchestrator/v1/hosts/hostname", request);
        assertEquals(500, httpResponse.getStatus());
    }

    @Test
    void getHost_works() throws Exception {
        Orchestrator orchestrator = mock(Orchestrator.class);
        HostRequestHandler handler = createHandler(orchestrator);

        HostName hostName = new HostName("hostname");

        ServiceInstance serviceInstance = new ServiceInstance(
                new ConfigId("configId"),
                hostName,
                ServiceStatus.UP);
        ServiceCluster serviceCluster = new ServiceCluster(
                new ClusterId("clusterId"),
                new ServiceType("serviceType"),
                Collections.singleton(serviceInstance));
        serviceInstance.setServiceCluster(serviceCluster);

        Host host = new Host(
                hostName,
                HostInfo.createSuspended(HostStatus.ALLOWED_TO_BE_DOWN, Instant.EPOCH),
                new ApplicationInstanceReference(
                        new TenantId("tenantId"),
                        new ApplicationInstanceId("applicationId")),
                Collections.singletonList(serviceInstance));
        when(orchestrator.getHost(hostName)).thenReturn(host);

        HttpResponse httpResponse = executeRequest(handler, Method.GET, "/orchestrator/v1/hosts/hostname", null);
        GetHostResponse response = parseResponseContent(handler, httpResponse, GetHostResponse.class);

        assertEquals("http://localhost/orchestrator/v1/instances/tenantId:applicationId", response.applicationUrl());
        assertEquals("hostname", response.hostname());
        assertEquals("ALLOWED_TO_BE_DOWN", response.state());
        assertEquals("1970-01-01T00:00:00Z", response.suspendedSince());
        assertEquals(1, response.services().size());
        assertEquals("clusterId", response.services().get(0).clusterId);
        assertEquals("configId", response.services().get(0).configId);
        assertEquals("UP", response.services().get(0).serviceStatus);
        assertEquals("serviceType", response.services().get(0).serviceType);
    }

    @Test
    void throws_409_on_timeout() throws HostNameNotFoundException, HostStateChangeDeniedException, IOException {
        Orchestrator orchestrator = mock(Orchestrator.class);
        doThrow(new UncheckedTimeoutException("Timeout Message")).when(orchestrator).resume(any(HostName.class));

        HostRequestHandler handler = createHandler(orchestrator);
        HttpResponse httpResponse = executeRequest(handler, Method.DELETE, "/orchestrator/v1/hosts/hostname/suspended", null);
        assertEquals(409, httpResponse.getStatus());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        httpResponse.render(out);
        JsonTestHelper.assertJsonEquals("{\n" +
                "  \"hostname\" : \"hostname\",\n" +
                "  \"reason\" : {\n" +
                "    \"constraint\" : \"deadline\",\n" +
                "    \"message\" : \"resume failed: Timeout Message\"\n" +
                "  }\n" +
                "}",
                out.toString());
    }

    private HostRequestHandler createHandler(Orchestrator orchestrator) {
        var handlerContext = new LoggingRequestHandler.Context(Executors.newSingleThreadExecutor(), new MockMetric());
        return new HostRequestHandler(handlerContext, orchestrator);
    }

    private HttpResponse executeRequest(HostRequestHandler handler, Method method, String path, Object requestEntity) throws IOException {
        String uri = "http://localhost" + path;
        HttpRequest request;
        if (requestEntity != null) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            handler.restApi().jacksonJsonMapper().writeValue(out, requestEntity);
            request = HttpRequest.createTestRequest(uri, method, new ByteArrayInputStream(out.toByteArray()));
        } else {
            request = HttpRequest.createTestRequest(uri, method);
        }
        return handler.handle(request);
    }

    private <T> T parseResponseContent(HostRequestHandler handler, HttpResponse response, Class<T> responseEntityType) throws IOException {
        assertEquals(200, response.getStatus());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        response.render(out);
        return handler.restApi().jacksonJsonMapper().readValue(out.toByteArray(), responseEntityType);
    }

}
