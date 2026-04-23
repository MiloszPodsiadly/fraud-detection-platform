package com.frauddetection.alert.audit;

import java.util.Set;

public record AuditActor(
        String userId,
        Set<String> roles,
        Set<String> authorities
) {
    private static final String UNKNOWN_USER_ID = "unknown";

    public AuditActor {
        userId = userId == null || userId.isBlank() ? UNKNOWN_USER_ID : userId.trim();
        roles = roles == null ? Set.of() : Set.copyOf(roles);
        authorities = authorities == null ? Set.of() : Set.copyOf(authorities);
    }
}
