package com.frauddetection.alert.security.config;

import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer;

class DenyByDefaultAuthorizationRules {

    void configureBackendRouteFamilies(
            AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizationManagerRequestMatcherRegistry authorize
    ) {
        authorize
                // Guardrail for future backend endpoints added without explicit route group ownership.
                .requestMatchers("/api/v1/**").denyAll()
                .requestMatchers("/api/**").denyAll()
                .requestMatchers("/governance/**").denyAll()
                .requestMatchers("/system/**").denyAll()
                .requestMatchers("/bff/**").denyAll()
                .requestMatchers("/actuator/**").denyAll();
    }

    void configureFinalDeny(
            AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizationManagerRequestMatcherRegistry authorize
    ) {
        authorize
                .anyRequest().denyAll();
    }
}
