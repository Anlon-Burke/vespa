// Copyright Verizon Media. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.config.server;

import com.yahoo.cloud.config.ConfigserverConfig;
import com.yahoo.config.model.api.ConfigDefinitionRepo;
import com.yahoo.config.provision.Zone;
import com.yahoo.vespa.config.server.modelfactory.ModelFactoryRegistry;
import com.yahoo.vespa.config.server.tenant.TenantListener;

import java.time.Clock;

/**
 * Interface representing all global config server components used within the config server.
 *
 * @author Ulf Lilleengen
 */
public interface GlobalComponentRegistry {

    ConfigserverConfig getConfigserverConfig();
    TenantListener getTenantListener();
    ReloadListener getReloadListener();
    ConfigDefinitionRepo getStaticConfigDefinitionRepo();
    ModelFactoryRegistry getModelFactoryRegistry();
    Zone getZone();
    Clock getClock();
    ConfigServerDB getConfigServerDB();
}
