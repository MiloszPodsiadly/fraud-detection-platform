package com.frauddetection.alert.security.config;

import com.frauddetection.alert.security.auth.DemoAuthFilter;
import com.frauddetection.alert.security.auth.JwtAnalystAuthenticationConverter;
import com.frauddetection.alert.security.authorization.AnalystAuthority;
import com.frauddetection.alert.security.error.ApiAccessDeniedHandler;
import com.frauddetection.alert.security.error.ApiAuthenticationEntryPoint;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
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
     * - unknown /api/v1/** routes are denied explicitly
     * - non-API routes remain permitted for local UI/static usage
     */

    @Bean
    UserDetailsService userDetailsService() {
        return username -> {
            throw new UsernameNotFoundException("Password authentication is not supported.");
        };
    }

    @Bean
    SecurityFilterChain alertSecurityFilterChain(
            HttpSecurity http,
            ObjectProvider<DemoAuthFilter> demoAuthFilter,
            ObjectProvider<JwtDecoder> jwtDecoder,
            ObjectProvider<JwtAnalystAuthenticationConverter> jwtAnalystAuthenticationConverter,
            ApiAuthenticationEntryPoint authenticationEntryPoint,
            ApiAccessDeniedHandler accessDeniedHandler
    ) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler)
                )
                .authorizeHttpRequests(authorize -> authorize
                        // Public technical endpoints for local orchestration and health checks.
                        .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()

                        // Protected analyst business endpoints.
                        .requestMatchers(HttpMethod.GET, "/api/v1/alerts").hasAuthority(AnalystAuthority.ALERT_READ)
                        .requestMatchers(HttpMethod.GET, "/api/v1/alerts/{alertId}").hasAuthority(AnalystAuthority.ALERT_READ)
                        .requestMatchers(HttpMethod.GET, "/api/v1/alerts/{alertId}/assistant-summary").hasAuthority(AnalystAuthority.ASSISTANT_SUMMARY_READ)
                        .requestMatchers(HttpMethod.POST, "/api/v1/alerts/{alertId}/decision").hasAuthority(AnalystAuthority.ALERT_DECISION_SUBMIT)
                        .requestMatchers(HttpMethod.GET, "/api/v1/fraud-cases").hasAuthority(AnalystAuthority.FRAUD_CASE_READ)
                        .requestMatchers(HttpMethod.GET, "/api/v1/fraud-cases/{caseId}").hasAuthority(AnalystAuthority.FRAUD_CASE_READ)
                        .requestMatchers(HttpMethod.PATCH, "/api/v1/fraud-cases/{caseId}").hasAuthority(AnalystAuthority.FRAUD_CASE_UPDATE)
                        .requestMatchers(HttpMethod.GET, "/api/v1/transactions/scored").hasAuthority(AnalystAuthority.TRANSACTION_MONITOR_READ)
                        .requestMatchers(HttpMethod.GET, "/api/v1/audit/events").hasAuthority(AnalystAuthority.AUDIT_READ)
                        .requestMatchers(HttpMethod.GET, "/api/v1/audit/integrity").hasAuthority(AnalystAuthority.AUDIT_READ)
                        .requestMatchers(HttpMethod.GET, "/api/v1/audit/integrity/external").hasAuthority(AnalystAuthority.AUDIT_VERIFY)
                        .requestMatchers(HttpMethod.GET, "/api/v1/audit/integrity/external/coverage").hasAuthority(AnalystAuthority.AUDIT_VERIFY)
                        .requestMatchers(HttpMethod.GET, "/api/v1/audit/evidence/export").hasAuthority(AnalystAuthority.AUDIT_EXPORT)
                        .requestMatchers(HttpMethod.GET, "/api/v1/audit/trust/attestation").hasAuthority(AnalystAuthority.AUDIT_VERIFY)
                        .requestMatchers(HttpMethod.GET, "/api/v1/audit/trust/keys").hasAuthority(AnalystAuthority.AUDIT_VERIFY)
                        .requestMatchers(HttpMethod.GET, "/api/v1/audit/degradations").hasAuthority(AnalystAuthority.AUDIT_VERIFY)
                        .requestMatchers(HttpMethod.POST, "/api/v1/audit/degradations/{auditId}/resolve").hasAuthority(AnalystAuthority.AUDIT_DEGRADATION_RESOLVE)
                        .requestMatchers(HttpMethod.GET, "/api/v1/decision-outbox/unknown-confirmations").hasAuthority(AnalystAuthority.AUDIT_VERIFY)
                        .requestMatchers(HttpMethod.POST, "/api/v1/decision-outbox/unknown-confirmations/{alertId}/resolve").hasAuthority(AnalystAuthority.DECISION_OUTBOX_RECONCILE)
                        .requestMatchers(HttpMethod.POST, "/api/v1/regulated-mutations/recover").hasAuthority(AnalystAuthority.REGULATED_MUTATION_RECOVER)
                        .requestMatchers(HttpMethod.GET, "/api/v1/regulated-mutations/recovery/backlog").hasAnyAuthority(AnalystAuthority.REGULATED_MUTATION_RECOVER, AnalystAuthority.AUDIT_VERIFY)
                        .requestMatchers(HttpMethod.GET, "/api/v1/regulated-mutations/by-command/{commandId}").hasAnyAuthority(AnalystAuthority.REGULATED_MUTATION_RECOVER, AnalystAuthority.AUDIT_VERIFY)
                        .requestMatchers(HttpMethod.GET, "/api/v1/regulated-mutations/by-idempotency-hash/{hash}").hasAnyAuthority(AnalystAuthority.REGULATED_MUTATION_RECOVER, AnalystAuthority.AUDIT_VERIFY)
                        .requestMatchers(HttpMethod.GET, "/api/v1/regulated-mutations/{idempotencyKey}").hasAnyAuthority(AnalystAuthority.REGULATED_MUTATION_RECOVER, AnalystAuthority.AUDIT_VERIFY)
                        .requestMatchers(HttpMethod.GET, "/system/trust-level").hasAuthority(AnalystAuthority.AUDIT_VERIFY)
                        .requestMatchers(HttpMethod.GET, "/governance/advisories/analytics").hasAuthority(AnalystAuthority.TRANSACTION_MONITOR_READ)
                        .requestMatchers(HttpMethod.GET, "/governance/advisories").hasAuthority(AnalystAuthority.TRANSACTION_MONITOR_READ)
                        .requestMatchers(HttpMethod.GET, "/governance/advisories/{eventId}").hasAuthority(AnalystAuthority.TRANSACTION_MONITOR_READ)
                        .requestMatchers(HttpMethod.GET, "/governance/advisories/{eventId}/audit").hasAuthority(AnalystAuthority.TRANSACTION_MONITOR_READ)
                        .requestMatchers(HttpMethod.POST, "/governance/advisories/{eventId}/audit").hasAuthority(AnalystAuthority.GOVERNANCE_ADVISORY_AUDIT_WRITE)

                        // Guardrail for future analyst endpoints added under /api/v1/** without explicit rules.
                        .requestMatchers("/api/v1/**").denyAll()

                        // Keep non-business routes open for local UI/static delivery.
                        .anyRequest().permitAll()
                );

        // Production auth path: enable JWT Resource Server only when a decoder is configured.
        if (jwtDecoder.getIfAvailable() != null) {
            http.oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt
                    .jwtAuthenticationConverter(token -> jwtAnalystAuthenticationConverter
                            .getObject()
                            .convert(token))
            ));
        }

        // Local/dev auth path: demo headers remain an explicit opt-in adapter.
        demoAuthFilter.ifAvailable(filter -> http.addFilterBefore(filter, UsernamePasswordAuthenticationFilter.class));
        return http.build();
    }
}
