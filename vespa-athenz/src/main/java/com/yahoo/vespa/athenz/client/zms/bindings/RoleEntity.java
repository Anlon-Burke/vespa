// Copyright Verizon Media. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

package com.yahoo.vespa.athenz.client.zms.bindings;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * @author mortent
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class RoleEntity {
    private final String roleName;
    private final List<Member> roleMembers;

    @JsonCreator
    public RoleEntity(@JsonProperty("roleName") String roleName, @JsonProperty("roleMembers") List<Member> roleMembers) {
        this.roleName = roleName;
        this.roleMembers = roleMembers;
    }

    public String roleName() {
        return roleName;
    }

    public List<Member> roleMembers() {
        return roleMembers;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class Member {
        private final String memberName;
        private final boolean active;
        private final boolean approved;

        @JsonCreator
        public Member(@JsonProperty("memberName") String memberName, @JsonProperty("active") boolean active, @JsonProperty("approved") boolean approved) {
            this.memberName = memberName;
            this.active = active;
            this.approved = approved;
        }

        public String memberName() {
            return memberName;
        }

        public boolean pendingApproval() {
            return !approved;
        }
    }
}
