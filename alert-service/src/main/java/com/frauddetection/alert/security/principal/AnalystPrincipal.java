package com.frauddetection.alert.security.principal;

import com.frauddetection.alert.security.authorization.AnalystRole;

import java.util.Set;

public record AnalystPrincipal(
        String userId,
        Set<AnalystRole> roles,
        Set<String> authorities
) {
    public AnalystPrincipal {
        roles = Set.copyOf(roles);
        authorities = Set.copyOf(authorities);
    }

    public boolean hasAuthority(String authority) {
        return authorities.contains(authority);
    }
}
