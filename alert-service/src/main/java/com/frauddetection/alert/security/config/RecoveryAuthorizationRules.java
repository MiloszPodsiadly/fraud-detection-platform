package com.frauddetection.alert.security.config;

import com.frauddetection.alert.security.authorization.AnalystAuthority;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer;

class RecoveryAuthorizationRules {

    void configure(AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizationManagerRequestMatcherRegistry authorize) {
        authorize
                .requestMatchers(HttpMethod.GET, "/api/v1/decision-outbox/unknown-confirmations").hasAuthority(AnalystAuthority.AUDIT_VERIFY)
                .requestMatchers(HttpMethod.POST, "/api/v1/decision-outbox/unknown-confirmations/{alertId}/resolve").hasAuthority(AnalystAuthority.DECISION_OUTBOX_RECONCILE)
                .requestMatchers(HttpMethod.POST, "/api/v1/regulated-mutations/recover").hasAuthority(AnalystAuthority.REGULATED_MUTATION_RECOVER)
                .requestMatchers(HttpMethod.GET, "/api/v1/regulated-mutations/recovery/backlog").hasAnyAuthority(AnalystAuthority.REGULATED_MUTATION_RECOVER, AnalystAuthority.AUDIT_VERIFY)
                .requestMatchers(HttpMethod.GET, "/api/v1/regulated-mutations/by-command/{commandId}").hasAnyAuthority(AnalystAuthority.REGULATED_MUTATION_RECOVER, AnalystAuthority.AUDIT_VERIFY)
                .requestMatchers(HttpMethod.GET, "/api/v1/regulated-mutations/by-idempotency-hash/{hash}").hasAnyAuthority(AnalystAuthority.REGULATED_MUTATION_RECOVER, AnalystAuthority.AUDIT_VERIFY)
                .requestMatchers(HttpMethod.GET, "/api/v1/regulated-mutations/{idempotencyKey}").hasAnyAuthority(AnalystAuthority.REGULATED_MUTATION_RECOVER, AnalystAuthority.AUDIT_VERIFY)
                .requestMatchers(HttpMethod.GET, "/api/v1/outbox/recovery/backlog").hasAuthority(AnalystAuthority.OUTBOX_INSPECT)
                .requestMatchers(HttpMethod.POST, "/api/v1/outbox/recovery/run").hasAuthority(AnalystAuthority.OUTBOX_RECOVER)
                .requestMatchers(HttpMethod.POST, "/api/v1/outbox/{eventId}/resolve-confirmation").hasAuthority(AnalystAuthority.OUTBOX_RESOLVE);
    }
}
