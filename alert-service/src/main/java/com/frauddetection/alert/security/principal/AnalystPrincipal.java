package com.frauddetection.alert.security.principal;

import com.frauddetection.alert.security.authorization.AnalystRole;

import java.util.Set;

public record AnalystPrincipal(
        String userId,
        Set<AnalystRole> roles,
        Set<String> authorities,
        String institutionalActorId,
        String readAuditActorId
) {
    public AnalystPrincipal(String userId, Set<AnalystRole> roles, Set<String> authorities) {
        this(userId, roles, authorities, null, null);
    }

    public AnalystPrincipal(String userId, Set<AnalystRole> roles, Set<String> authorities, String institutionalActorId) {
        this(userId, roles, authorities, institutionalActorId, null);
    }

    public AnalystPrincipal {
        roles = Set.copyOf(roles);
        authorities = Set.copyOf(authorities);
    }

    public boolean hasAuthority(String authority) {
        return authorities.contains(authority);
    }
}
