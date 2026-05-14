package com.frauddetection.alert.security.config;

import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer;

class PublicTechnicalAuthorizationRules {

    void configure(AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizationManagerRequestMatcherRegistry authorize) {
        authorize
                .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                .requestMatchers(HttpMethod.GET,
                        "/",
                        "/index.html",
                        "/favicon.ico",
                        "/manifest.webmanifest",
                        "/robots.txt",
                        "/assets/**",
                        "/static/**"
                ).permitAll();
    }
}
