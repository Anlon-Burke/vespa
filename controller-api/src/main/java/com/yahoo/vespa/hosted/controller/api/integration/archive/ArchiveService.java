// Copyright Verizon Media. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.controller.api.integration.archive;

import com.yahoo.config.provision.TenantName;
import com.yahoo.config.provision.zone.ZoneId;

import java.net.URI;
import java.util.Optional;

/**
 * Service that manages archive storage URIs for tenant nodes.
 *
 * @author freva
 */
public interface ArchiveService {

    Optional<URI> archiveUriFor(ZoneId zoneId, TenantName tenant);

    // TODO: Method to configure archive permissions/access for a tenant

    // TODO: Method to revoke permission/access for a tenant

}
