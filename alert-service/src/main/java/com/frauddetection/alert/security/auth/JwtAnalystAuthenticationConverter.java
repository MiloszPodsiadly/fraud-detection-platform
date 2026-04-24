package com.frauddetection.alert.security.auth;

import com.frauddetection.alert.security.authorization.AnalystRole;
import com.frauddetection.alert.security.principal.AnalystPrincipal;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class JwtAnalystAuthenticationConverter {

    private final JwtSecurityProperties properties;
    private final AnalystAuthenticationFactory authenticationFactory;

    public JwtAnalystAuthenticationConverter(
            JwtSecurityProperties properties,
            AnalystAuthenticationFactory authenticationFactory
    ) {
        this.properties = properties;
        this.authenticationFactory = authenticationFactory;
    }

    public UsernamePasswordAuthenticationToken convert(Jwt jwt) {
        String userId = userId(jwt);
        Set<String> externalAccessValues = accessValues(jwt);
        Set<AnalystRole> roles = roles(externalAccessValues);
        Set<String> authorities = roles.stream()
                .flatMap(role -> role.authorities().stream())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        AnalystPrincipal principal = new AnalystPrincipal(userId, roles, authorities);
        return (UsernamePasswordAuthenticationToken) authenticationFactory.authenticated(principal);
    }

    private String userId(Jwt jwt) {
        String userId = jwt.getClaimAsString(properties.userIdClaim());
        if (!StringUtils.hasText(userId)) {
            throw new BadCredentialsException("JWT user id claim is required.");
        }
        return userId.trim();
    }

    private Set<String> accessValues(Jwt jwt) {
        Object claimValue = jwt.getClaims().get(properties.accessClaim());
        if (claimValue == null) {
            return Set.of();
        }
        if (claimValue instanceof String rawValue) {
            return splitValues(rawValue);
        }
        if (claimValue instanceof Collection<?> values) {
            return values.stream()
                    .filter(String.class::isInstance)
                    .map(String.class::cast)
                    .map(String::trim)
                    .filter(StringUtils::hasText)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        }
        throw new BadCredentialsException("JWT access claim has unsupported type.");
    }

    private Set<AnalystRole> roles(Set<String> externalAccessValues) {
        Map<String, AnalystRole> roleMapping = properties.roleMapping();
        return externalAccessValues.stream()
                .map(roleMapping::get)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private Set<String> splitValues(String rawValue) {
        return Arrays.stream(rawValue.split("[,\\s]+"))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
