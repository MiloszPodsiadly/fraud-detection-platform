package com.frauddetection.alert.security.principal;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.Optional;

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
        return Optional.empty();
    }
}
