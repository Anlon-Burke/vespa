// Copyright Verizon Media. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.controller.api.integration.aws;

import com.yahoo.config.provision.ApplicationId;
import com.yahoo.config.provision.TenantName;

import java.util.Optional;

/**
 * @author mortent
 */
public interface RoleService {

    Optional<TenantRoles> createTenantRole(TenantName tenant);

    String createTenantPolicy(TenantName tenant, String policyName, String awsId, String role);

}
