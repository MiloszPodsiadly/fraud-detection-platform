package com.frauddetection.alert.security.config;

import com.frauddetection.alert.security.authorization.AnalystAuthority;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer;

class GovernanceAuthorizationRules implements EndpointAuthorizationRuleGroup {

    public void configure(AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizationManagerRequestMatcherRegistry authorize) {
        authorize
                .requestMatchers(HttpMethod.GET, "/api/v2/governance/shadow-performance/summary/current")
                .hasAuthority(AnalystAuthority.SHADOW_PERFORMANCE_READ)
                .requestMatchers(HttpMethod.GET, "/api/v1/governance/promotion-review-readiness/current")
                .hasAuthority(AnalystAuthority.PROMOTION_READINESS_READ)
                .requestMatchers(HttpMethod.GET, "/api/v1/governance/model-card/current")
                .hasAuthority(AnalystAuthority.MODEL_CARD_READ)
                .requestMatchers(HttpMethod.GET, "/api/v1/governance/model-shadow-performance")
                .hasAuthority(AnalystAuthority.MODEL_SHADOW_PERFORMANCE_READ)
                .requestMatchers(HttpMethod.GET, "/api/v1/governance/model-promotion/state")
                .hasAuthority(AnalystAuthority.MODEL_PROMOTION_READ)
                .requestMatchers(HttpMethod.GET, "/api/v1/governance/model-promotion/assessment")
                .hasAuthority(AnalystAuthority.MODEL_PROMOTION_READ)
                .requestMatchers(HttpMethod.POST, "/api/v1/governance/model-promotion/candidates")
                .hasAuthority(AnalystAuthority.MODEL_PROMOTION_REGISTER)
                .requestMatchers(HttpMethod.POST, "/api/v1/governance/model-promotion/proposals")
                .hasAuthority(AnalystAuthority.MODEL_PROMOTION_PROPOSE)
                .requestMatchers(HttpMethod.POST, "/api/v1/governance/model-promotion/retirement-proposals")
                .hasAuthority(AnalystAuthority.MODEL_PROMOTION_RETIRE)
                .requestMatchers(HttpMethod.POST, "/api/v1/governance/model-promotion/proposals/{proposalId}/reviews")
                .hasAnyAuthority(AnalystAuthority.MODEL_PROMOTION_APPROVE, AnalystAuthority.MODEL_PROMOTION_REJECT)
                .requestMatchers(HttpMethod.GET, "/governance/advisories/analytics").hasAuthority(AnalystAuthority.TRANSACTION_MONITOR_READ)
                .requestMatchers(HttpMethod.GET, "/governance/advisories").hasAuthority(AnalystAuthority.TRANSACTION_MONITOR_READ)
                .requestMatchers(HttpMethod.GET, "/governance/advisories/{eventId}").hasAuthority(AnalystAuthority.TRANSACTION_MONITOR_READ)
                .requestMatchers(HttpMethod.GET, "/governance/advisories/{eventId}/audit").hasAuthority(AnalystAuthority.TRANSACTION_MONITOR_READ)
                .requestMatchers(HttpMethod.POST, "/governance/advisories/{eventId}/audit").hasAuthority(AnalystAuthority.GOVERNANCE_ADVISORY_AUDIT_WRITE);
    }
}
