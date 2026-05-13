package com.frauddetection.alert.security.config;

import com.frauddetection.alert.security.authorization.AnalystAuthority;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer;

import java.util.Set;

class AlertEndpointAuthorizationRules {

    void configure(AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizationManagerRequestMatcherRegistry authorize) {
        authorize
                // Public technical endpoints for local orchestration and health checks.
                .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/session").permitAll()
                .requestMatchers("/oauth2/**", "/login/oauth2/**", "/error").permitAll()
                .requestMatchers(HttpMethod.GET,
                        "/",
                        "/index.html",
                        "/favicon.ico",
                        "/manifest.webmanifest",
                        "/robots.txt",
                        "/assets/**",
                        "/static/**"
                ).permitAll()

                // Protected analyst business endpoints.
                .requestMatchers(HttpMethod.GET, "/api/v1/alerts").hasAuthority(AnalystAuthority.ALERT_READ)
                .requestMatchers(HttpMethod.GET, "/api/v1/alerts/{alertId}").hasAuthority(AnalystAuthority.ALERT_READ)
                .requestMatchers(HttpMethod.GET, "/api/v1/alerts/{alertId}/assistant-summary").hasAuthority(AnalystAuthority.ASSISTANT_SUMMARY_READ)
                .requestMatchers(HttpMethod.POST, "/api/v1/alerts/{alertId}/decision").hasAuthority(AnalystAuthority.ALERT_DECISION_SUBMIT)
                .requestMatchers(HttpMethod.GET, "/api/v1/fraud-cases").hasAuthority(AnalystAuthority.FRAUD_CASE_READ)
                .requestMatchers(HttpMethod.GET, "/api/v1/fraud-cases/work-queue").hasAuthority(AnalystAuthority.FRAUD_CASE_READ)
                .requestMatchers(HttpMethod.GET, "/api/v1/fraud-cases/work-queue/summary").hasAuthority(AnalystAuthority.FRAUD_CASE_READ)
                .requestMatchers(HttpMethod.GET, "/api/v1/fraud-cases/{caseId}").hasAuthority(AnalystAuthority.FRAUD_CASE_READ)
                .requestMatchers(HttpMethod.GET, "/api/v1/fraud-cases/{caseId}/audit").hasAuthority(AnalystAuthority.FRAUD_CASE_AUDIT_READ)
                .requestMatchers(HttpMethod.POST, "/api/v1/fraud-cases").hasAuthority(AnalystAuthority.FRAUD_CASE_UPDATE)
                .requestMatchers(HttpMethod.POST, "/api/v1/fraud-cases/{caseId}/assign").hasAuthority(AnalystAuthority.FRAUD_CASE_UPDATE)
                .requestMatchers(HttpMethod.POST, "/api/v1/fraud-cases/{caseId}/notes").hasAuthority(AnalystAuthority.FRAUD_CASE_UPDATE)
                .requestMatchers(HttpMethod.POST, "/api/v1/fraud-cases/{caseId}/decisions").hasAuthority(AnalystAuthority.FRAUD_CASE_UPDATE)
                .requestMatchers(HttpMethod.POST, "/api/v1/fraud-cases/{caseId}/transition").hasAuthority(AnalystAuthority.FRAUD_CASE_UPDATE)
                .requestMatchers(HttpMethod.POST, "/api/v1/fraud-cases/{caseId}/close").hasAuthority(AnalystAuthority.FRAUD_CASE_UPDATE)
                .requestMatchers(HttpMethod.POST, "/api/v1/fraud-cases/{caseId}/reopen").hasAuthority(AnalystAuthority.FRAUD_CASE_UPDATE)
                .requestMatchers(HttpMethod.PATCH, "/api/v1/fraud-cases/{caseId}").hasAuthority(AnalystAuthority.FRAUD_CASE_UPDATE)
                .requestMatchers(HttpMethod.GET, "/api/fraud-cases").hasAuthority(AnalystAuthority.FRAUD_CASE_READ)
                .requestMatchers(HttpMethod.GET, "/api/fraud-cases/work-queue").hasAuthority(AnalystAuthority.FRAUD_CASE_READ)
                .requestMatchers(HttpMethod.GET, "/api/fraud-cases/{caseId}").hasAuthority(AnalystAuthority.FRAUD_CASE_READ)
                .requestMatchers(HttpMethod.GET, "/api/fraud-cases/{caseId}/audit").hasAuthority(AnalystAuthority.FRAUD_CASE_AUDIT_READ)
                .requestMatchers(HttpMethod.POST, "/api/fraud-cases").hasAuthority(AnalystAuthority.FRAUD_CASE_UPDATE)
                .requestMatchers(HttpMethod.POST, "/api/fraud-cases/{caseId}/assign").hasAuthority(AnalystAuthority.FRAUD_CASE_UPDATE)
                .requestMatchers(HttpMethod.POST, "/api/fraud-cases/{caseId}/notes").hasAuthority(AnalystAuthority.FRAUD_CASE_UPDATE)
                .requestMatchers(HttpMethod.POST, "/api/fraud-cases/{caseId}/decisions").hasAuthority(AnalystAuthority.FRAUD_CASE_UPDATE)
                .requestMatchers(HttpMethod.POST, "/api/fraud-cases/{caseId}/transition").hasAuthority(AnalystAuthority.FRAUD_CASE_UPDATE)
                .requestMatchers(HttpMethod.POST, "/api/fraud-cases/{caseId}/close").hasAuthority(AnalystAuthority.FRAUD_CASE_UPDATE)
                .requestMatchers(HttpMethod.POST, "/api/fraud-cases/{caseId}/reopen").hasAuthority(AnalystAuthority.FRAUD_CASE_UPDATE)
                .requestMatchers(HttpMethod.PATCH, "/api/fraud-cases/{caseId}").hasAuthority(AnalystAuthority.FRAUD_CASE_UPDATE)
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
                .requestMatchers(HttpMethod.GET, "/api/v1/outbox/recovery/backlog").hasAuthority(AnalystAuthority.OUTBOX_INSPECT)
                .requestMatchers(HttpMethod.POST, "/api/v1/outbox/recovery/run").hasAuthority(AnalystAuthority.OUTBOX_RECOVER)
                .requestMatchers(HttpMethod.POST, "/api/v1/outbox/{eventId}/resolve-confirmation").hasAuthority(AnalystAuthority.OUTBOX_RESOLVE)
                .requestMatchers(HttpMethod.GET, "/api/v1/trust/incidents").hasAuthority(AnalystAuthority.TRUST_INCIDENT_READ)
                .requestMatchers(HttpMethod.GET, "/api/v1/trust/incidents/signals/preview").hasAuthority(AnalystAuthority.TRUST_INCIDENT_READ)
                .requestMatchers(HttpMethod.POST, "/api/v1/trust/incidents/refresh").hasAnyAuthority(AnalystAuthority.TRUST_INCIDENT_REFRESH, AnalystAuthority.TRUST_INCIDENT_RESOLVE)
                .requestMatchers(HttpMethod.POST, "/api/v1/trust/incidents/{incidentId}/ack").hasAuthority(AnalystAuthority.TRUST_INCIDENT_ACK)
                .requestMatchers(HttpMethod.POST, "/api/v1/trust/incidents/{incidentId}/resolve").hasAuthority(AnalystAuthority.TRUST_INCIDENT_RESOLVE)
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
                .requestMatchers("/api/**").denyAll()
                .requestMatchers("/governance/**").denyAll()
                .requestMatchers("/system/**").denyAll()
                .requestMatchers("/bff/**").denyAll()
                .requestMatchers("/actuator/**").denyAll()

                // SPA fallback is intentionally narrow; backend-looking routes stay fail-closed.
                .requestMatchers(this::isSpaFallbackRoute).permitAll()
                .anyRequest().denyAll();
    }

    private boolean isSpaFallbackRoute(HttpServletRequest request) {
        if (!HttpMethod.GET.matches(request.getMethod())) {
            return false;
        }
        String path = request.getRequestURI();
        return Set.of(
                "/analyst-console",
                "/fraud-case",
                "/fraud-transaction",
                "/transaction-scoring",
                "/compliance",
                "/reports",
                "/auth/callback"
        ).contains(path);
    }
}
