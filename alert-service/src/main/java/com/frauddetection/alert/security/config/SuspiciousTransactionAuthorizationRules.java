package com.frauddetection.alert.security.config;

import com.frauddetection.alert.security.authorization.AnalystAuthority;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer;

class SuspiciousTransactionAuthorizationRules implements EndpointAuthorizationRuleGroup {

    public void configure(AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizationManagerRequestMatcherRegistry authorize) {
        authorize
                .requestMatchers(HttpMethod.GET, "/internal/suspicious-transactions")
                .hasAuthority(AnalystAuthority.SUSPICIOUS_TRANSACTION_READ)
                .requestMatchers(HttpMethod.GET, "/internal/suspicious-transactions/summary")
                .hasAuthority(AnalystAuthority.SUSPICIOUS_TRANSACTION_READ)
                .requestMatchers(HttpMethod.GET, "/internal/suspicious-transactions/{suspiciousTransactionId}")
                .hasAuthority(AnalystAuthority.SUSPICIOUS_TRANSACTION_READ);
    }
}
