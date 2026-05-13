package com.frauddetection.alert.security.auth;

import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.frauddetection.alert.security.authorization.AnalystRole;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.authority.mapping.GrantedAuthoritiesMapper;
import org.springframework.security.oauth2.core.oidc.user.OidcUserAuthority;
import org.springframework.security.oauth2.core.user.OAuth2UserAuthority;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Component
public class OidcAnalystAuthoritiesMapper implements GrantedAuthoritiesMapper {

    private final JwtSecurityProperties properties;
    private final AlertServiceMetrics metrics;

    @Autowired
    public OidcAnalystAuthoritiesMapper(JwtSecurityProperties properties, AlertServiceMetrics metrics) {
        this.properties = properties;
        this.metrics = metrics;
    }

    OidcAnalystAuthoritiesMapper(JwtSecurityProperties properties) {
        this(properties, null);
    }

    @Override
    public Collection<? extends GrantedAuthority> mapAuthorities(Collection<? extends GrantedAuthority> authorities) {
        Set<String> externalAccessValues = authorities.stream()
                .flatMap(this::accessValues)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<AnalystRole> roles = externalAccessValues.stream()
                .map(properties.roleMapping()::get)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        boolean hasUnmappedAccessValue = externalAccessValues.stream()
                .anyMatch(accessValue -> !properties.roleMapping().containsKey(accessValue));
        if (hasUnmappedAccessValue && metrics != null) {
            metrics.recordOidcAuthorityMappingMiss(properties.accessClaim());
        }
        return Stream.concat(
                        roles.stream().flatMap(role -> role.authorities().stream()),
                        roles.stream().map(role -> "ROLE_" + role.name())
                )
                .map(SimpleGrantedAuthority::new)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private Stream<String> accessValues(GrantedAuthority authority) {
        if (authority instanceof OidcUserAuthority oidcUserAuthority) {
            return accessValues(oidcUserAuthority.getIdToken().getClaims());
        }
        if (authority instanceof OAuth2UserAuthority oAuth2UserAuthority) {
            return accessValues(oAuth2UserAuthority.getAttributes());
        }
        return Stream.empty();
    }

    private Stream<String> accessValues(Map<String, Object> claims) {
        Object claimValue = claims.get(properties.accessClaim());
        if (claimValue instanceof String rawValue) {
            return splitValues(rawValue).stream();
        }
        if (claimValue instanceof Collection<?> values) {
            return values.stream()
                    .filter(String.class::isInstance)
                    .map(String.class::cast)
                    .map(String::trim)
                    .filter(StringUtils::hasText);
        }
        return Stream.empty();
    }

    private Set<String> splitValues(String rawValue) {
        return Arrays.stream(rawValue.split("[,\\s]+"))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
