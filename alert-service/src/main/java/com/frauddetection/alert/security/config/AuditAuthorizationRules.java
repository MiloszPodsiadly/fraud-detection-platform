package com.frauddetection.alert.security.config;

import com.frauddetection.alert.security.authorization.AnalystAuthority;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer;

class AuditAuthorizationRules implements EndpointAuthorizationRuleGroup {

    public void configure(AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizationManagerRequestMatcherRegistry authorize) {
        authorize
                .requestMatchers(HttpMethod.GET, "/api/v1/audit/events").hasAuthority(AnalystAuthority.AUDIT_READ)
                .requestMatchers(HttpMethod.GET, "/api/v1/audit/integrity").hasAuthority(AnalystAuthority.AUDIT_READ)
                .requestMatchers(HttpMethod.GET, "/api/v1/audit/integrity/external").hasAuthority(AnalystAuthority.AUDIT_VERIFY)
                .requestMatchers(HttpMethod.GET, "/api/v1/audit/integrity/external/coverage").hasAuthority(AnalystAuthority.AUDIT_VERIFY)
                .requestMatchers(HttpMethod.GET, "/api/v1/audit/evidence/export").hasAuthority(AnalystAuthority.AUDIT_EXPORT)
                .requestMatchers(HttpMethod.GET, "/api/v1/audit/trust/attestation").hasAuthority(AnalystAuthority.AUDIT_VERIFY)
                .requestMatchers(HttpMethod.GET, "/api/v1/audit/trust/keys").hasAuthority(AnalystAuthority.AUDIT_VERIFY)
                .requestMatchers(HttpMethod.GET, "/api/v1/audit/degradations").hasAuthority(AnalystAuthority.AUDIT_VERIFY)
                .requestMatchers(HttpMethod.POST, "/api/v1/audit/degradations/{auditId}/resolve").hasAuthority(AnalystAuthority.AUDIT_DEGRADATION_RESOLVE);
    }
}
