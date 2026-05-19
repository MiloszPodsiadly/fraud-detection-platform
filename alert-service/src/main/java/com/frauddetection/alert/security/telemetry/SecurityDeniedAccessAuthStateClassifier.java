package com.frauddetection.alert.security.telemetry;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

@Component
public class SecurityDeniedAccessAuthStateClassifier {

    public String classify(Authentication authentication) {
        if (authentication == null || authentication instanceof AnonymousAuthenticationToken) {
            return "anonymous";
        }
        return authentication.isAuthenticated() ? "authenticated" : "anonymous";
    }
}
