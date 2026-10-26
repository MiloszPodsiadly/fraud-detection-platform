package com.frauddetection.alert.security.auth;

import com.frauddetection.alert.security.authorization.AnalystRole;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@ConfigurationProperties(prefix = "app.security.jwt")
public record JwtSecurityProperties(
        boolean enabled,
        boolean required,
        String issuerUri,
        String jwkSetUri,
        String userIdClaim,
        String accessClaim,
        Map<String, AnalystRole> roleMapping,
        Set<String> productionPromotionAccessValues,
        String expectedAudience,
        boolean allowUnboundJwksForLocal
) {
    public JwtSecurityProperties(
            boolean enabled,
            boolean required,
            String issuerUri,
            String jwkSetUri,
            String userIdClaim,
            String accessClaim,
            Map<String, AnalystRole> roleMapping,
            Set<String> productionPromotionAccessValues
    ) {
        this(enabled, required, issuerUri, jwkSetUri, userIdClaim, accessClaim, roleMapping,
                productionPromotionAccessValues, null, false);
    }

    @ConstructorBinding
    public JwtSecurityProperties {
        userIdClaim = userIdClaim == null || userIdClaim.isBlank() ? "sub" : userIdClaim.trim();
        accessClaim = accessClaim == null || accessClaim.isBlank() ? "groups" : accessClaim.trim();
        issuerUri = issuerUri == null || issuerUri.isBlank() ? null : issuerUri.trim();
        jwkSetUri = jwkSetUri == null || jwkSetUri.isBlank() ? null : jwkSetUri.trim();
        expectedAudience = expectedAudience == null || expectedAudience.isBlank() ? null : expectedAudience.trim();
        roleMapping = roleMapping == null ? Map.of() : Map.copyOf(roleMapping);
        productionPromotionAccessValues = productionPromotionAccessValues == null ? Set.of()
                : productionPromotionAccessValues.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .collect(Collectors.toUnmodifiableSet());
    }

    public boolean grantsProductionPromotionAccess(String accessValue) {
        return productionPromotionAccessValues.contains(accessValue);
    }
}
