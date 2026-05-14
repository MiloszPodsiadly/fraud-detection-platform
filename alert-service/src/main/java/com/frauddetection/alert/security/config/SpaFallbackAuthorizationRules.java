package com.frauddetection.alert.security.config;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer;

import java.util.Set;

class SpaFallbackAuthorizationRules implements EndpointAuthorizationRuleGroup {

    private static final Set<String> SPA_ROUTES = Set.of(
            "/analyst-console",
            "/fraud-case",
            "/fraud-transaction",
            "/transaction-scoring",
            "/compliance",
            "/reports",
            "/auth/callback"
    );

    public void configure(AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizationManagerRequestMatcherRegistry authorize) {
        // SPA fallback is intentionally narrow; backend-looking routes stay fail-closed.
        authorize
                .requestMatchers(this::isSpaFallbackRoute).permitAll();
    }

    private boolean isSpaFallbackRoute(HttpServletRequest request) {
        return HttpMethod.GET.matches(request.getMethod())
                && SPA_ROUTES.contains(request.getRequestURI());
    }
}
