// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.node.admin.task.util.systemd;

import com.yahoo.vespa.hosted.node.admin.component.TaskContext;
import com.yahoo.vespa.hosted.node.admin.task.util.process.ChildProcessFailureException;
import com.yahoo.vespa.hosted.node.admin.task.util.process.TestTerminal;
import org.junit.Test;

import static org.hamcrest.CoreMatchers.containsString;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;

/**
 * @author hakonhall
 */
public class SystemCtlTest {

    private final TaskContext taskContext = mock(TaskContext.class);
    private final TestTerminal terminal = new TestTerminal();

    @Test
    public void enable() {
        terminal.expectCommand("systemctl --quiet is-enabled docker 2>&1", 1, "")
                .expectCommand("systemctl enable docker 2>&1")
                .expectCommand("systemctl --quiet is-enabled docker 2>&1");

        SystemCtl.SystemCtlEnable enableDockerService = new SystemCtl(terminal).enable("docker");
        assertTrue(enableDockerService.converge(taskContext));
        assertFalse("Already converged", enableDockerService.converge(taskContext));
    }

    @Test
    public void enableCommandFailure() {
        terminal.expectCommand("systemctl --quiet is-enabled docker 2>&1", 1, "")
                .expectCommand("systemctl enable docker 2>&1", 1, "error enabling service");
        SystemCtl.SystemCtlEnable enableDockerService = new SystemCtl(terminal).enable("docker");
        try {
            enableDockerService.converge(taskContext);
            fail();
        } catch (ChildProcessFailureException e) {
            // success
        }
    }


    @Test
    public void start() {
        terminal.expectCommand(
                        "systemctl show docker 2>&1",
                        0,
                        "a=b\n" +
                                "ActiveState=failed\n" +
                                "bar=zoo\n")
                .expectCommand("systemctl start docker 2>&1", 0, "");

        SystemCtl.SystemCtlStart startDockerService = new SystemCtl(terminal).start("docker");
        assertTrue(startDockerService.converge(taskContext));
    }

    @Test
    public void startIsNoop() {
        terminal.expectCommand(
                        "systemctl show docker 2>&1",
                        0,
                        "a=b\n" +
                                "ActiveState=active\n" +
                                "bar=zoo\n")
                .expectCommand("systemctl start docker 2>&1", 0, "");

        SystemCtl.SystemCtlStart startDockerService = new SystemCtl(terminal).start("docker");
        assertFalse(startDockerService.converge(taskContext));
    }


    @Test
    public void startCommandFailre() {
        terminal.expectCommand("systemctl show docker 2>&1", 1, "error");
        SystemCtl.SystemCtlStart startDockerService = new SystemCtl(terminal).start("docker");
        try {
            startDockerService.converge(taskContext);
            fail();
        } catch (ChildProcessFailureException e) {
            // success
        }
    }


    @Test
    public void disable() {
        terminal.expectCommand("systemctl --quiet is-enabled docker 2>&1")
                .expectCommand("systemctl disable docker 2>&1")
                .expectCommand("systemctl --quiet is-enabled docker 2>&1", 1, "");

        assertTrue(new SystemCtl(terminal).disable("docker").converge(taskContext));
        assertFalse("Already converged", new SystemCtl(terminal).disable("docker").converge(taskContext));
    }

    @Test
    public void stop() {
        terminal.expectCommand(
                        "systemctl show docker 2>&1",
                        0,
                        "a=b\n" +
                                "ActiveState=active\n" +
                                "bar=zoo\n")
                .expectCommand("systemctl stop docker 2>&1", 0, "");

        assertTrue(new SystemCtl(terminal).stop("docker").converge(taskContext));
    }

    @Test
    public void restart() {
        terminal.expectCommand("systemctl restart docker 2>&1", 0, "");
        assertTrue(new SystemCtl(terminal).restart("docker").converge(taskContext));
    }

    @Test
    public void testUnitExists() {
        SystemCtl systemCtl = new SystemCtl(terminal);

        terminal.expectCommand("systemctl list-unit-files foo.service 2>&1", 0,
                "UNIT FILE STATE\n" +
                        "\n" +
                        "0 unit files listed.\n");
        assertFalse(systemCtl.serviceExists(taskContext, "foo"));

        terminal.expectCommand("systemctl list-unit-files foo.service 2>&1", 0,
                "UNIT FILE           STATE  \n" +
                        "foo.service enabled\n" +
                        "\n" +
                        "1 unit files listed.\n");
        assertTrue(systemCtl.serviceExists(taskContext, "foo"));

        terminal.expectCommand("systemctl list-unit-files foo.service 2>&1", 0, "garbage");
        try {
            systemCtl.serviceExists(taskContext, "foo");
            fail();
        } catch (Exception e) {
            assertThat(e.getMessage(), containsString("garbage"));
        }
    }

    @Test
    public void withSudo() {
        SystemCtl systemCtl = new SystemCtl(terminal).withSudo();
        terminal.expectCommand("sudo systemctl restart docker 2>&1", 0, "");
        assertTrue(systemCtl.restart("docker").converge(taskContext));
    }

}
