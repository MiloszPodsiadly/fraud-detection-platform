package com.frauddetection.alert.security.config;

import com.frauddetection.alert.security.authorization.AnalystAuthority;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer;

class FraudCaseAuthorizationRules implements EndpointAuthorizationRuleGroup {

    public void configure(AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizationManagerRequestMatcherRegistry authorize) {
        authorize
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
                .requestMatchers(HttpMethod.PATCH, "/api/fraud-cases/{caseId}").hasAuthority(AnalystAuthority.FRAUD_CASE_UPDATE);
    }
}
