package com.frauddetection.alert.security.config;

import com.frauddetection.alert.security.authorization.AnalystAuthority;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer;

class FraudCaseAuthorizationRules implements EndpointAuthorizationRuleGroup {

    public void configure(AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizationManagerRequestMatcherRegistry authorize) {
        authorize
                .requestMatchers(HttpMethod.GET, "/api/v1/fraud-cases/work-queue").hasAuthority(AnalystAuthority.FRAUD_CASE_READ)
                .requestMatchers(HttpMethod.GET, "/api/v1/fraud-cases/work-queue/summary").hasAuthority(AnalystAuthority.FRAUD_CASE_READ)
                .requestMatchers(HttpMethod.GET, "/api/v1/fraud-cases/{caseId}").hasAuthority(AnalystAuthority.FRAUD_CASE_READ)
                .requestMatchers(HttpMethod.GET, "/api/v1/fraud-cases/{caseId}/evidence-summary").hasAuthority(AnalystAuthority.FRAUD_CASE_READ)
                .requestMatchers(HttpMethod.GET, "/api/v1/fraud-cases/{caseId}/evidence-timeline").hasAuthority(AnalystAuthority.FRAUD_CASE_READ)
                .requestMatchers(HttpMethod.PATCH, "/api/v1/fraud-cases/{caseId}").hasAuthority(AnalystAuthority.FRAUD_CASE_UPDATE);
    }
}
