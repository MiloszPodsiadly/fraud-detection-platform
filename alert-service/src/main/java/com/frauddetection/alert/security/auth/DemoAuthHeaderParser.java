package com.frauddetection.alert.security.auth;

import com.frauddetection.alert.security.authorization.AnalystAuthority;
import com.frauddetection.alert.security.authorization.AnalystRole;
import com.frauddetection.alert.security.principal.AnalystPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class DemoAuthHeaderParser implements AnalystPrincipalResolver {

    @Override
    public Optional<AnalystPrincipal> resolve(HttpServletRequest request) {
        return parse(request);
    }

    public Optional<AnalystPrincipal> parse(HttpServletRequest request) {
        String userId = request.getHeader(DemoAuthHeaders.USER_ID);
        if (!StringUtils.hasText(userId)) {
            return Optional.empty();
        }

        Set<AnalystRole> roles = parseRoles(request.getHeader(DemoAuthHeaders.ROLES));
        Set<String> authorities = roles.stream()
                .flatMap(role -> role.authorities().stream())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        authorities.addAll(parseAuthorities(request.getHeader(DemoAuthHeaders.AUTHORITIES)));

        return Optional.of(new AnalystPrincipal(userId.trim(), roles, authorities));
    }

    private Set<AnalystRole> parseRoles(String headerValue) {
        if (!StringUtils.hasText(headerValue)) {
            return Set.of(AnalystRole.READ_ONLY_ANALYST);
        }
        return splitHeader(headerValue).stream()
                .map(this::parseRole)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private AnalystRole parseRole(String rawRole) {
        try {
            return AnalystRole.valueOf(rawRole);
        } catch (IllegalArgumentException exception) {
            throw new BadCredentialsException("Unknown demo role.", exception);
        }
    }

    private Set<String> parseAuthorities(String headerValue) {
        if (!StringUtils.hasText(headerValue)) {
            return Set.of();
        }
        Set<String> authorities = splitHeader(headerValue);
        if (!AnalystAuthority.ALL.containsAll(authorities)) {
            throw new BadCredentialsException("Unknown demo authority.");
        }
        return authorities;
    }

    private Set<String> splitHeader(String headerValue) {
        return Arrays.stream(headerValue.split(","))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
