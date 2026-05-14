package com.frauddetection.alert.security.config;

import com.frauddetection.alert.security.authorization.AnalystAuthority;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer;

class GovernanceAuthorizationRules implements EndpointAuthorizationRuleGroup {

    public void configure(AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizationManagerRequestMatcherRegistry authorize) {
        authorize
                .requestMatchers(HttpMethod.GET, "/governance/advisories/analytics").hasAuthority(AnalystAuthority.TRANSACTION_MONITOR_READ)
                .requestMatchers(HttpMethod.GET, "/governance/advisories").hasAuthority(AnalystAuthority.TRANSACTION_MONITOR_READ)
                .requestMatchers(HttpMethod.GET, "/governance/advisories/{eventId}").hasAuthority(AnalystAuthority.TRANSACTION_MONITOR_READ)
                .requestMatchers(HttpMethod.GET, "/governance/advisories/{eventId}/audit").hasAuthority(AnalystAuthority.TRANSACTION_MONITOR_READ)
                .requestMatchers(HttpMethod.POST, "/governance/advisories/{eventId}/audit").hasAuthority(AnalystAuthority.GOVERNANCE_ADVISORY_AUDIT_WRITE);
    }
}
