// Copyright Verizon Media. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.zookeeper;

import com.google.inject.Inject;
import com.yahoo.component.AbstractComponent;

/**
 * A dummy {@link VespaZooKeeperServer} implementation that can be used when a container cluster is not configured with standalone ZK cluster.
 *
 * @author bjorncs
 */
public class DummyVespaZooKeeperServer extends AbstractComponent implements VespaZooKeeperServer {

    @Inject public DummyVespaZooKeeperServer() {}

}
