// Copyright Verizon Media. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.controller.restapi.horizon;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yahoo.config.provision.SystemName;
import com.yahoo.config.provision.TenantName;
import com.yahoo.vespa.hosted.controller.api.role.Role;
import com.yahoo.vespa.hosted.controller.api.role.RoleDefinition;
import com.yahoo.vespa.hosted.controller.api.role.TenantRole;

import java.io.IOException;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * @author valerijf
 */
public class TsdbQueryRewriter {

    private static final ObjectMapper mapper = new ObjectMapper();
    private static final EnumSet<RoleDefinition> operatorRoleDefinitions =
            EnumSet.of(RoleDefinition.hostedOperator, RoleDefinition.hostedSupporter);

    public static byte[] rewrite(byte[] data, Set<Role> roles, SystemName systemName) throws IOException {
        boolean operator = roles.stream().map(Role::definition).anyMatch(operatorRoleDefinitions::contains);

        // Anyone with any tenant relation can view metrics for apps within those tenants
        Set<TenantName> authorizedTenants = roles.stream()
                .filter(TenantRole.class::isInstance)
                .map(role -> ((TenantRole) role).tenant())
                .collect(Collectors.toUnmodifiableSet());
        if (!operator && authorizedTenants.isEmpty())
            throw new UnauthorizedException();

        JsonNode root = mapper.readTree(data);
        requireLegalType(root);
        getField(root, "executionGraph", ArrayNode.class)
                .ifPresent(graph -> rewriteQueryGraph(graph, authorizedTenants, operator, systemName));
        getField(root, "filters", ArrayNode.class)
                .ifPresent(filters -> rewriteFilters(filters, authorizedTenants, operator, systemName));
        getField(root, "queries", ArrayNode.class)
                .ifPresent(graph -> rewriteQueryGraph(graph, authorizedTenants, operator, systemName));

        return mapper.writeValueAsBytes(root);
    }

    private static void rewriteQueryGraph(ArrayNode executionGraph, Set<TenantName> tenantNames, boolean operator, SystemName systemName) {
        for (int i = 0; i < executionGraph.size(); i++) {
            JsonNode execution = executionGraph.get(i);

            // Will be handled by rewriteFilters()
            if (execution.has("filterId")) continue;

            rewriteFilter((ObjectNode) execution, tenantNames, operator, systemName);
        }
    }

    private static void rewriteFilters(ArrayNode filters, Set<TenantName> tenantNames, boolean operator, SystemName systemName) {
        for (int i = 0; i < filters.size(); i++)
            rewriteFilter((ObjectNode) filters.get(i), tenantNames, operator, systemName);
    }

    private static void rewriteFilter(ObjectNode parent, Set<TenantName> tenantNames, boolean operator, SystemName systemName) {
        ObjectNode prev = ((ObjectNode) parent.get("filter"));
        ArrayNode filters;
        // If we dont already have a filter object, or the object that we have is not an AND filter
        if (prev == null || !"Chain".equals(prev.get("type").asText()) || prev.get("op") != null && !"AND".equals(prev.get("op").asText())) {
            // Create new filter object
            filters = parent.putObject("filter")
                    .put("type", "Chain")
                    .put("op", "AND")
                    .putArray("filters");

            // Add the previous filter to the AND expression
            if (prev != null) filters.add(prev);
        } else filters = (ArrayNode) prev.get("filters");

        // Make sure we only show metrics in the relevant system
        ObjectNode systemFilter = filters.addObject();
        systemFilter.put("type", "TagValueLiteralOr");
        systemFilter.put("filter", systemName.name().toLowerCase());
        systemFilter.put("tagKey", "system");

        // Make sure non-operators cannot see metrics outside of their tenants
        if (!operator) {
            ObjectNode appFilter = filters.addObject();
            appFilter.put("type", "TagValueRegex");
            appFilter.put("filter",
                    tenantNames.stream().map(TenantName::value).sorted().collect(Collectors.joining("|", "^(", ")\\..*")));
            appFilter.put("tagKey", "applicationId");
        }
    }

    private static void requireLegalType(JsonNode root) {
        Optional.ofNullable(root.get("type"))
                .map(JsonNode::asText)
                .filter(type -> !"TAG_KEYS_AND_VALUES".equals(type))
                .ifPresent(type -> { throw new IllegalArgumentException("Illegal type " + type); });
    }

    private static <T extends JsonNode> Optional<T> getField(JsonNode object, String fieldName, Class<T> clazz) {
        return Optional.ofNullable(object.get(fieldName)).filter(clazz::isInstance).map(clazz::cast);
    }

    static class UnauthorizedException extends RuntimeException { }

}
