package com.frauddetection.alert.security.principal;

import com.frauddetection.alert.security.authorization.AnalystRole;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class CurrentAnalystUser {

    public Optional<AnalystPrincipal> get() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return Optional.empty();
        }
        Object principal = authentication.getPrincipal();
        if (principal instanceof AnalystPrincipal analystPrincipal) {
            return Optional.of(analystPrincipal);
        }
        if (principal instanceof OidcUser oidcUser) {
            return Optional.of(fromAuthentication(authentication, oidcUser.getSubject()));
        }
        if (principal instanceof OAuth2User oAuth2User) {
            return Optional.of(fromAuthentication(authentication, oAuth2User.getName()));
        }
        return Optional.empty();
    }

    private AnalystPrincipal fromAuthentication(Authentication authentication, String userId) {
        Set<AnalystRole> roles = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(authority -> authority.startsWith("ROLE_"))
                .map(authority -> authority.substring("ROLE_".length()))
                .flatMap(role -> {
                    try {
                        return java.util.stream.Stream.of(AnalystRole.valueOf(role));
                    } catch (IllegalArgumentException ignored) {
                        return java.util.stream.Stream.empty();
                    }
                })
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> authorities = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(authority -> !authority.startsWith("ROLE_"))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        return new AnalystPrincipal(userId == null ? "" : userId, roles, authorities);
    }
}
