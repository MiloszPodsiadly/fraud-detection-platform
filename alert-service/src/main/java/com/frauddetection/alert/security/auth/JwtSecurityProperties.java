package com.frauddetection.alert.security.auth;

import com.frauddetection.alert.security.authorization.AnalystRole;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

@ConfigurationProperties(prefix = "app.security.jwt")
public record JwtSecurityProperties(
        boolean enabled,
        boolean required,
        String issuerUri,
        String jwkSetUri,
        String userIdClaim,
        String accessClaim,
        Map<String, AnalystRole> roleMapping
) {
    public JwtSecurityProperties {
        userIdClaim = userIdClaim == null || userIdClaim.isBlank() ? "sub" : userIdClaim.trim();
        accessClaim = accessClaim == null || accessClaim.isBlank() ? "groups" : accessClaim.trim();
        roleMapping = roleMapping == null ? Map.of() : Map.copyOf(roleMapping);
    }
}
