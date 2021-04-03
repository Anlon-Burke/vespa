// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.orchestrator.resources.instance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yahoo.config.provision.ApplicationId;
import com.yahoo.jrt.slobrok.api.Mirror;
import com.yahoo.vespa.applicationmodel.ClusterId;
import com.yahoo.vespa.applicationmodel.ConfigId;
import com.yahoo.vespa.applicationmodel.ServiceStatus;
import com.yahoo.vespa.applicationmodel.ServiceStatusInfo;
import com.yahoo.vespa.applicationmodel.ServiceType;
import com.yahoo.vespa.orchestrator.resources.instance.InstanceResource;
import com.yahoo.vespa.orchestrator.restapi.wire.SlobrokEntryResponse;
import com.yahoo.vespa.service.manager.UnionMonitorManager;
import com.yahoo.vespa.service.monitor.SlobrokApi;
import org.junit.Test;

import javax.ws.rs.WebApplicationException;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class InstanceResourceTest {
    private static final String APPLICATION_INSTANCE_REFERENCE = "tenant:app:prod:us-west-1:instance";
    private static final ApplicationId APPLICATION_ID = ApplicationId.from(
            "tenant", "app", "instance");
    private static final List<Mirror.Entry> ENTRIES = Arrays.asList(
            new Mirror.Entry("name1", "tcp/spec:1"),
            new Mirror.Entry("name2", "tcp/spec:2"));
    private static final ClusterId CLUSTER_ID = new ClusterId("cluster-id");

    private final SlobrokApi slobrokApi = mock(SlobrokApi.class);
    private final UnionMonitorManager rootManager = mock(UnionMonitorManager.class);
    private final InstanceResource resource = new InstanceResource(
            null,
            null,
            slobrokApi,
            rootManager);

    @Test
    public void testGetSlobrokEntries() throws Exception {
        testGetSlobrokEntriesWith("foo", "foo");
    }

    @Test
    public void testGetSlobrokEntriesWithoutPattern() throws Exception {
        testGetSlobrokEntriesWith(null, InstanceResource.DEFAULT_SLOBROK_PATTERN);
    }

    @Test
    public void testGetServiceStatusInfo() {
        ServiceType serviceType = new ServiceType("serviceType");
        ConfigId configId = new ConfigId("configId");
        ServiceStatus serviceStatus = ServiceStatus.UP;
        when(rootManager.getStatus(APPLICATION_ID, CLUSTER_ID, serviceType, configId))
                .thenReturn(new ServiceStatusInfo(serviceStatus));
        ServiceStatus actualServiceStatus = resource.getServiceStatus(
                APPLICATION_INSTANCE_REFERENCE,
                CLUSTER_ID.s(),
                serviceType.s(),
                configId.s()).serviceStatus();
        verify(rootManager).getStatus(APPLICATION_ID, CLUSTER_ID, serviceType, configId);
        assertEquals(serviceStatus, actualServiceStatus);
    }

    @Test(expected = WebApplicationException.class)
    public void testBadRequest() {
        resource.getServiceStatus(APPLICATION_INSTANCE_REFERENCE, CLUSTER_ID.s(), null, null);
    }

    private void testGetSlobrokEntriesWith(String pattern, String expectedLookupPattern)
            throws Exception{
        when(slobrokApi.lookup(APPLICATION_ID, expectedLookupPattern))
                .thenReturn(ENTRIES);

        List<SlobrokEntryResponse> response = resource.getSlobrokEntries(
                APPLICATION_INSTANCE_REFERENCE,
                pattern);

        verify(slobrokApi).lookup(APPLICATION_ID, expectedLookupPattern);

        ObjectMapper mapper = new ObjectMapper();
        String actualJson = mapper.writeValueAsString(response);
        assertEquals(
                "[{\"name\":\"name1\",\"spec\":\"tcp/spec:1\"},{\"name\":\"name2\",\"spec\":\"tcp/spec:2\"}]",
                actualJson);
    }
}