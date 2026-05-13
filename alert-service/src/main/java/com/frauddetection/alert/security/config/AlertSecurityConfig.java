package com.frauddetection.alert.security.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.frauddetection.alert.security.auth.BffLogoutSuccessHandler;
import com.frauddetection.alert.security.auth.BffSecurityProperties;
import com.frauddetection.alert.security.auth.DemoAuthFilter;
import com.frauddetection.alert.security.auth.JwtAnalystAuthenticationConverter;
import com.frauddetection.alert.security.auth.OidcAnalystAuthoritiesMapper;
import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.frauddetection.alert.security.error.ApiAccessDeniedHandler;
import com.frauddetection.alert.security.error.ApiAuthenticationEntryPoint;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(BffSecurityProperties.class)
public class AlertSecurityConfig {

    /**
     * Security Foundation v1 is intentionally scoped to analyst business APIs.
     *
     * Public:
     * - health/info actuator endpoints needed for local orchestration
     *
     * Protected:
     * - analyst workflow endpoints under /api/v1/**
     * - governance advisory audit endpoints, which record authenticated operator review only
     *
     * Fallback:
     * - unknown backend-looking routes are denied explicitly
     * - only allowlisted SPA/static/OAuth routes remain public
     */

    @Bean
    UserDetailsService userDetailsService() {
        return username -> {
            throw new UsernameNotFoundException("Password authentication is not supported.");
        };
    }

    @Bean
    BffLogoutSuccessHandler bffLogoutSuccessHandler(
            BffSecurityProperties bffSecurityProperties,
            ObjectMapper objectMapper,
            AlertServiceMetrics metrics
    ) {
        return new BffLogoutSuccessHandler(bffSecurityProperties, objectMapper, metrics);
    }

    @Bean
    AlertEndpointAuthorizationRules alertEndpointAuthorizationRules() {
        return new AlertEndpointAuthorizationRules();
    }

    @Bean
    BffSessionSecurityConfigurer bffSessionSecurityConfigurer() {
        return new BffSessionSecurityConfigurer();
    }

    @Bean
    JwtResourceServerSecurityConfigurer jwtResourceServerSecurityConfigurer() {
        return new JwtResourceServerSecurityConfigurer();
    }

    @Bean
    DemoAuthSecurityConfigurer demoAuthSecurityConfigurer() {
        return new DemoAuthSecurityConfigurer();
    }

    @Bean
    SecurityFilterChain alertSecurityFilterChain(
            HttpSecurity http,
            ObjectProvider<DemoAuthFilter> demoAuthFilter,
            ObjectProvider<JwtDecoder> jwtDecoder,
            ObjectProvider<JwtAnalystAuthenticationConverter> jwtAnalystAuthenticationConverter,
            ObjectProvider<OidcAnalystAuthoritiesMapper> oidcAnalystAuthoritiesMapper,
            BffSecurityProperties bffSecurityProperties,
            BffLogoutSuccessHandler bffLogoutSuccessHandler,
            AlertEndpointAuthorizationRules endpointAuthorizationRules,
            BffSessionSecurityConfigurer bffSessionSecurityConfigurer,
            JwtResourceServerSecurityConfigurer jwtResourceServerSecurityConfigurer,
            DemoAuthSecurityConfigurer demoAuthSecurityConfigurer,
            Environment environment,
            ApiAuthenticationEntryPoint authenticationEntryPoint,
            ApiAccessDeniedHandler accessDeniedHandler
    ) throws Exception {
        boolean bffEnabled = bffSecurityProperties.enabled();
        bffSecurityProperties.validate(environment);
        http
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(
                        bffEnabled ? SessionCreationPolicy.IF_REQUIRED : SessionCreationPolicy.STATELESS))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler)
                )
                .authorizeHttpRequests(endpointAuthorizationRules::configure);

        bffSessionSecurityConfigurer.configure(
                http,
                bffEnabled,
                oidcAnalystAuthoritiesMapper,
                bffLogoutSuccessHandler
        );
        jwtResourceServerSecurityConfigurer.configure(
                http,
                jwtDecoder,
                jwtAnalystAuthenticationConverter
        );
        demoAuthSecurityConfigurer.configure(http, demoAuthFilter);
        return http.build();
    }
}
