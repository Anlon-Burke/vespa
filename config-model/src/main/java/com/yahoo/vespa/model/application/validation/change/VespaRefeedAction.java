// Copyright Verizon Media. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.model.application.validation.change;

import com.yahoo.config.model.api.ConfigChangeRefeedAction;
import com.yahoo.config.model.api.ServiceInfo;
import com.yahoo.config.application.api.ValidationOverrides;
import com.yahoo.config.provision.ClusterSpec;

import java.time.Instant;
import java.util.List;

/**
 * Represents an action to re-feed a document type in order to handle a config change.
 *
 * @author geirst
 * @author bratseth
 */
public class VespaRefeedAction extends VespaConfigChangeAction implements ConfigChangeRefeedAction {

    /**
     * The name of this action, which must be a valid ValidationId. This is a string here because
     * the validation ids belong to the Vespa model while these names are exposed to the config server,
     * which is model version independent.
     */
    private final String name;

    private final String documentType;
    private final boolean allowed;

    private VespaRefeedAction(ClusterSpec.Id id, String name, String message, List<ServiceInfo> services, String documentType, boolean allowed) {
        super(id, message, services);
        this.name = name;
        this.documentType = documentType;
        this.allowed = allowed;
    }

    /** Creates a refeed action with some missing information */
    // TODO: We should require document type or model its absence properly
    public static VespaRefeedAction of(ClusterSpec.Id id, String name, ValidationOverrides overrides, String message, Instant now) {
        return new VespaRefeedAction(id, name, message, List.of(), "", overrides.allows(name, now));
    }

    /** Creates a refeed action */
    public static VespaRefeedAction of(ClusterSpec.Id id, String name, ValidationOverrides overrides, String message,
                                       List<ServiceInfo> services, String documentType, Instant now) {
        return new VespaRefeedAction(id, name, message, services, documentType, overrides.allows(name, now));
    }

    @Override
    public VespaConfigChangeAction modifyAction(String newMessage, List<ServiceInfo> newServices, String documentType) {
        return new VespaRefeedAction(clusterId(), name, newMessage, newServices, documentType, allowed);
    }

    @Override
    public String name() { return name; }

    @Override
    public String getDocumentType() { return documentType; }

    @Override
    public boolean allowed() { return allowed; }

    @Override
    public boolean ignoreForInternalRedeploy() {
        return false;
    }

    @Override
    public String toString() {
        return super.toString() + ", documentType='" + documentType + "'";
    }

    @Override
    public boolean equals(Object o) {
        if ( ! super.equals(o)) return false;
        if ( ! (o instanceof VespaRefeedAction)) return false;
        VespaRefeedAction other = (VespaRefeedAction)o;
        if ( ! this.documentType.equals(other.documentType)) return false;
        if ( ! this.name.equals(other.name)) return false;
        if ( ! this.allowed == other.allowed) return false;
        return true;
    }

    @Override
    public int hashCode() {
        return 31 * super.hashCode() + 11 * name.hashCode() + documentType.hashCode();
    }

}
