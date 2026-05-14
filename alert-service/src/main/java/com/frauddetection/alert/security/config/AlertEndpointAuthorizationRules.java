package com.frauddetection.alert.security.config;

import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer;

class AlertEndpointAuthorizationRules {

    private final PublicTechnicalAuthorizationRules publicTechnicalAuthorizationRules =
            new PublicTechnicalAuthorizationRules();
    private final SessionAuthorizationRules sessionAuthorizationRules = new SessionAuthorizationRules();
    private final AlertAuthorizationRules alertAuthorizationRules = new AlertAuthorizationRules();
    private final FraudCaseAuthorizationRules fraudCaseAuthorizationRules = new FraudCaseAuthorizationRules();
    private final TransactionAuthorizationRules transactionAuthorizationRules = new TransactionAuthorizationRules();
    private final AuditAuthorizationRules auditAuthorizationRules = new AuditAuthorizationRules();
    private final RecoveryAuthorizationRules recoveryAuthorizationRules = new RecoveryAuthorizationRules();
    private final TrustAuthorizationRules trustAuthorizationRules = new TrustAuthorizationRules();
    private final GovernanceAuthorizationRules governanceAuthorizationRules = new GovernanceAuthorizationRules();
    private final BffAuthorizationRules bffAuthorizationRules = new BffAuthorizationRules();
    private final DenyByDefaultAuthorizationRules denyByDefaultAuthorizationRules =
            new DenyByDefaultAuthorizationRules();
    private final SpaFallbackAuthorizationRules spaFallbackAuthorizationRules = new SpaFallbackAuthorizationRules();

    void configure(AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizationManagerRequestMatcherRegistry authorize) {
        // Matcher order is security-critical. Backend-looking denials must stay before the SPA fallback,
        // and DenyByDefaultAuthorizationRules is the only group allowed to call anyRequest().
        publicTechnicalAuthorizationRules.configure(authorize);
        sessionAuthorizationRules.configure(authorize);

        alertAuthorizationRules.configure(authorize);
        fraudCaseAuthorizationRules.configure(authorize);
        transactionAuthorizationRules.configure(authorize);
        auditAuthorizationRules.configure(authorize);
        recoveryAuthorizationRules.configure(authorize);
        trustAuthorizationRules.configure(authorize);
        governanceAuthorizationRules.configure(authorize);

        bffAuthorizationRules.configure(authorize);
        denyByDefaultAuthorizationRules.configureBackendRouteFamilies(authorize);
        spaFallbackAuthorizationRules.configure(authorize);
        denyByDefaultAuthorizationRules.configureFinalDeny(authorize);
    }
}
