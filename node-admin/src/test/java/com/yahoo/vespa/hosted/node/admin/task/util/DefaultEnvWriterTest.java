// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.node.admin.task.util;

import com.yahoo.vespa.hosted.node.admin.component.TaskContext;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.logging.Logger;

import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * @author bjorncs
 */
public class DefaultEnvWriterTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private static final Path EXAMPLE_FILE = Paths.get("src/test/resources/default-env-example.txt");
    private static final Path EXPECTED_RESULT_FILE = Paths.get("src/test/resources/default-env-rewritten.txt");

    private final TaskContext context = mock(TaskContext.class);

    @Test
    public void default_env_is_correctly_rewritten() throws IOException {
        Path tempFile = temporaryFolder.newFile().toPath();
        Files.copy(EXAMPLE_FILE, tempFile, REPLACE_EXISTING);

        DefaultEnvWriter writer = new DefaultEnvWriter();
        writer.addOverride("VESPA_HOSTNAME", "my-new-hostname");
        writer.addFallback("VESPA_CONFIGSERVER", "new-fallback-configserver");
        writer.addOverride("VESPA_TLS_CONFIG_FILE", "/override/path/to/config.file");

        boolean modified = writer.updateFile(context, tempFile);

        assertTrue(modified);
        assertEquals(Files.readString(EXPECTED_RESULT_FILE), Files.readString(tempFile));
        verify(context, times(1)).log(any(Logger.class), any(String.class));

        modified = writer.updateFile(context, tempFile);
        assertFalse(modified);
        assertEquals(Files.readString(EXPECTED_RESULT_FILE), Files.readString(tempFile));
        verify(context, times(1)).log(any(Logger.class), any(String.class));
    }

    @Test
    public void generates_default_env_content() throws IOException {
        DefaultEnvWriter writer = new DefaultEnvWriter();
        writer.addOverride("VESPA_HOSTNAME", "my-new-hostname");
        writer.addFallback("VESPA_CONFIGSERVER", "new-fallback-configserver");
        writer.addOverride("VESPA_TLS_CONFIG_FILE", "/override/path/to/config.file");
        writer.addUnset("VESPA_LEGACY_OPTION");
        String generatedContent = writer.generateContent();
        assertEquals(Files.readString(EXPECTED_RESULT_FILE), generatedContent);
    }
}