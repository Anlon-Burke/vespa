// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package ai.vespa.hosted.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MultiPartStreamerTest {

    @Test
    void test(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve("file");
        Files.write(file, new byte[]{0x48, 0x69});
        MultiPartStreamer streamer = new MultiPartStreamer("My boundary");

        assertEquals("--My boundary--",
                     new String(streamer.data().readAllBytes()));

        streamer.addData("data", "uss/enterprise", "lore")
                .addJson("json", "{\"xml\":false}")
                .addText("text", "Hello!")
                .addFile("file", file);

        String expected = "--My boundary\r\n" +
                          "Content-Disposition: form-data; name=\"data\"\r\n" +
                          "Content-Type: uss/enterprise\r\n" +
                          "\r\n" +
                          "lore\r\n" +
                          "--My boundary\r\n" +
                          "Content-Disposition: form-data; name=\"json\"\r\n" +
                          "Content-Type: application/json\r\n" +
                          "\r\n" +
                          "{\"xml\":false}\r\n" +
                          "--My boundary\r\n" +
                          "Content-Disposition: form-data; name=\"text\"\r\n" +
                          "Content-Type: text/plain\r\n" +
                          "\r\n" +
                          "Hello!\r\n" +
                          "--My boundary\r\n" +
                          "Content-Disposition: form-data; name=\"file\"; filename=\"" + file.getFileName() + "\"\r\n" +
                          "Content-Type: application/octet-stream\r\n" +
                          "\r\n" +
                          "Hi\r\n" +
                          "--My boundary--";

        assertEquals(expected,
                     new String(streamer.data().readAllBytes()));

        // Verify that all data is read again for a new builder.
        assertEquals(expected,
                     new String(streamer.data().readAllBytes()));

        assertEquals(List.of("multipart/form-data; boundary=My boundary; charset: utf-8"),
                     streamer.streamTo(HttpRequest.newBuilder(), Method.POST)
                             .uri(URI.create("https://uri/path"))
                             .build().headers().allValues("Content-Type"));
    }

}
