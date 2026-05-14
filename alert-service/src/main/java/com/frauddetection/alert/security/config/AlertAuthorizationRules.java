package com.frauddetection.alert.security.config;

import com.frauddetection.alert.security.authorization.AnalystAuthority;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer;

class AlertAuthorizationRules {

    void configure(AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizationManagerRequestMatcherRegistry authorize) {
        authorize
                .requestMatchers(HttpMethod.GET, "/api/v1/alerts").hasAuthority(AnalystAuthority.ALERT_READ)
                .requestMatchers(HttpMethod.GET, "/api/v1/alerts/{alertId}").hasAuthority(AnalystAuthority.ALERT_READ)
                .requestMatchers(HttpMethod.GET, "/api/v1/alerts/{alertId}/assistant-summary").hasAuthority(AnalystAuthority.ASSISTANT_SUMMARY_READ)
                .requestMatchers(HttpMethod.POST, "/api/v1/alerts/{alertId}/decision").hasAuthority(AnalystAuthority.ALERT_DECISION_SUBMIT);
    }
}
