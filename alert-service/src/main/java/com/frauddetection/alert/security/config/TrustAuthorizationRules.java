package com.frauddetection.alert.security.config;

import com.frauddetection.alert.security.authorization.AnalystAuthority;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer;

class TrustAuthorizationRules {

    void configure(AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizationManagerRequestMatcherRegistry authorize) {
        authorize
                .requestMatchers(HttpMethod.GET, "/api/v1/trust/incidents").hasAuthority(AnalystAuthority.TRUST_INCIDENT_READ)
                .requestMatchers(HttpMethod.GET, "/api/v1/trust/incidents/signals/preview").hasAuthority(AnalystAuthority.TRUST_INCIDENT_READ)
                .requestMatchers(HttpMethod.POST, "/api/v1/trust/incidents/refresh").hasAnyAuthority(AnalystAuthority.TRUST_INCIDENT_REFRESH, AnalystAuthority.TRUST_INCIDENT_RESOLVE)
                .requestMatchers(HttpMethod.POST, "/api/v1/trust/incidents/{incidentId}/ack").hasAuthority(AnalystAuthority.TRUST_INCIDENT_ACK)
                .requestMatchers(HttpMethod.POST, "/api/v1/trust/incidents/{incidentId}/resolve").hasAuthority(AnalystAuthority.TRUST_INCIDENT_RESOLVE)
                .requestMatchers(HttpMethod.GET, "/system/trust-level").hasAuthority(AnalystAuthority.AUDIT_VERIFY);
    }
}
