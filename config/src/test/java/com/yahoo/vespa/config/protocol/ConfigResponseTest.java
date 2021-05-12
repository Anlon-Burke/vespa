// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.config.protocol;

import com.yahoo.foo.SimpletypesConfig;
import com.yahoo.text.AbstractUtf8Array;
import com.yahoo.text.Utf8Array;
import com.yahoo.vespa.config.ConfigPayload;
import com.yahoo.vespa.config.LZ4PayloadCompressor;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

/**
 * @author Ulf Lilleengen
 */
public class ConfigResponseTest {

    @Test
    public void require_that_slime_response_is_initialized() throws IOException {
        ConfigPayload configPayload = ConfigPayload.fromInstance(new SimpletypesConfig(new SimpletypesConfig.Builder()));
        ConfigResponse response = SlimeConfigResponse.fromConfigPayload(configPayload, 3, false, "mymd5");
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        response.serialize(baos, CompressionType.UNCOMPRESSED);
        String payload = baos.toString(StandardCharsets.UTF_8);
        assertNotNull(payload);
        assertEquals("{\"boolval\":false,\"doubleval\":0.0,\"enumval\":\"VAL1\",\"intval\":0,\"longval\":0,\"stringval\":\"s\"}", payload.toString());
        assertEquals(response.getGeneration(), 3L);
        assertEquals(response.getConfigMd5(), "mymd5");

        baos = new ByteArrayOutputStream();
        response.serialize(baos, CompressionType.UNCOMPRESSED);
        assertEquals(baos.toString(),"{\"boolval\":false,\"doubleval\":0.0,\"enumval\":\"VAL1\",\"intval\":0,\"longval\":0,\"stringval\":\"s\"}");
    }

    @Test
    public void require_that_slime_response_decompresses_on_serialize() throws IOException {
        ConfigPayload configPayload = ConfigPayload.fromInstance(new SimpletypesConfig(new SimpletypesConfig.Builder()));
        AbstractUtf8Array data = configPayload.toUtf8Array(true);
        Utf8Array bytes = new Utf8Array(new LZ4PayloadCompressor().compress(data.wrap()));
        ConfigResponse response = new SlimeConfigResponse(bytes, 3, false, "mymd5", CompressionInfo.create(CompressionType.LZ4, data.getByteLength()));
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        response.serialize(baos, CompressionType.UNCOMPRESSED);
        String payload = baos.toString(StandardCharsets.UTF_8);
        assertNotNull(payload);

        baos = new ByteArrayOutputStream();
        response.serialize(baos, CompressionType.UNCOMPRESSED);
        assertEquals(baos.toString(), "{\"boolval\":false,\"doubleval\":0.0,\"enumval\":\"VAL1\",\"intval\":0,\"longval\":0,\"stringval\":\"s\"}");
    }

}
