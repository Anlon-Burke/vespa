// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.node.admin.nodeagent;

import com.yahoo.vespa.hosted.node.admin.task.util.fs.ContainerPath;

import java.nio.file.Path;

/**
 * Utility for manipulating the initial file system the Docker container will start with.
 *
 * @author hakon
 */
public interface ContainerData {

    /** Add or overwrite file in container at path. */
    void addFile(ContainerPath path, String data);

    /** Add directory in container at path. */
    void addDirectory(ContainerPath path);

    /**
     * Symlink to a file in container at path.
     * @param symlink The path to the symlink inside the container
     * @param target The path to the target file for the symbolic link inside the container
     */
    void createSymlink(ContainerPath symlink, Path target);
}

