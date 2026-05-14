package com.frauddetection.alert.security.config;

import java.util.ArrayList;
import java.util.List;

final class SecurityRouteOwnershipRegistry {

    static final List<RouteOwnership> MVC_ROUTES = mvcRoutes();
    static final List<String> DENY_BY_DEFAULT_FAMILIES = List.of(
            "/api/**",
            "/api/v1/**",
            "/governance/**",
            "/system/**",
            "/bff/**",
            "/actuator/**"
    );

    private SecurityRouteOwnershipRegistry() {
    }

    static boolean hasMvcOwnership(String method, String pattern) {
        return MVC_ROUTES.stream()
                .anyMatch(route -> route.method().equals(method) && route.pattern().equals(pattern));
    }

    private static List<RouteOwnership> mvcRoutes() {
        List<RouteOwnership> routes = new ArrayList<>();
        route(routes, "GET", "/api/v1/session", "SessionAuthorizationRules");
        route(routes, "GET", "/api/v1/alerts", "AlertAuthorizationRules");
        route(routes, "GET", "/api/v1/alerts/{alertId}", "AlertAuthorizationRules");
        route(routes, "GET", "/api/v1/alerts/{alertId}/assistant-summary", "AlertAuthorizationRules");
        route(routes, "POST", "/api/v1/alerts/{alertId}/decision", "AlertAuthorizationRules");
        fraudCaseRoutes(routes, "/api/v1/fraud-cases");
        fraudCaseRoutes(routes, "/api/fraud-cases");
        route(routes, "GET", "/api/v1/fraud-cases/work-queue/summary", "FraudCaseAuthorizationRules");
        route(routes, "GET", "/api/v1/transactions/scored", "TransactionAuthorizationRules");
        route(routes, "GET", "/governance/advisories/analytics", "GovernanceAuthorizationRules");
        route(routes, "GET", "/governance/advisories", "GovernanceAuthorizationRules");
        route(routes, "GET", "/governance/advisories/{eventId}", "GovernanceAuthorizationRules");
        route(routes, "GET", "/governance/advisories/{eventId}/audit", "GovernanceAuthorizationRules");
        route(routes, "POST", "/governance/advisories/{eventId}/audit", "GovernanceAuthorizationRules");
        route(routes, "GET", "/api/v1/audit/events", "AuditAuthorizationRules");
        route(routes, "GET", "/api/v1/audit/integrity", "AuditAuthorizationRules");
        route(routes, "GET", "/api/v1/audit/integrity/external", "AuditAuthorizationRules");
        route(routes, "GET", "/api/v1/audit/integrity/external/coverage", "AuditAuthorizationRules");
        route(routes, "GET", "/api/v1/audit/evidence/export", "AuditAuthorizationRules");
        route(routes, "GET", "/api/v1/audit/trust/attestation", "AuditAuthorizationRules");
        route(routes, "GET", "/api/v1/audit/trust/keys", "AuditAuthorizationRules");
        route(routes, "GET", "/api/v1/audit/degradations", "AuditAuthorizationRules");
        route(routes, "POST", "/api/v1/audit/degradations/{auditId}/resolve", "AuditAuthorizationRules");
        route(routes, "GET", "/system/trust-level", "TrustAuthorizationRules");
        route(routes, "GET", "/api/v1/trust/incidents", "TrustAuthorizationRules");
        route(routes, "GET", "/api/v1/trust/incidents/signals/preview", "TrustAuthorizationRules");
        route(routes, "POST", "/api/v1/trust/incidents/refresh", "TrustAuthorizationRules");
        route(routes, "POST", "/api/v1/trust/incidents/{incidentId}/ack", "TrustAuthorizationRules");
        route(routes, "POST", "/api/v1/trust/incidents/{incidentId}/resolve", "TrustAuthorizationRules");
        route(routes, "GET", "/api/v1/decision-outbox/unknown-confirmations", "RecoveryAuthorizationRules");
        route(routes, "POST", "/api/v1/decision-outbox/unknown-confirmations/{alertId}/resolve",
                "RecoveryAuthorizationRules");
        route(routes, "POST", "/api/v1/regulated-mutations/recover", "RecoveryAuthorizationRules");
        route(routes, "GET", "/api/v1/regulated-mutations/recovery/backlog", "RecoveryAuthorizationRules");
        route(routes, "GET", "/api/v1/regulated-mutations/{idempotencyKey}", "RecoveryAuthorizationRules");
        route(routes, "GET", "/api/v1/regulated-mutations/by-command/{commandId}", "RecoveryAuthorizationRules");
        route(routes, "GET", "/api/v1/regulated-mutations/by-idempotency-hash/{hash}",
                "RecoveryAuthorizationRules");
        route(routes, "GET", "/api/v1/outbox/recovery/backlog", "RecoveryAuthorizationRules");
        route(routes, "POST", "/api/v1/outbox/recovery/run", "RecoveryAuthorizationRules");
        route(routes, "POST", "/api/v1/outbox/{eventId}/resolve-confirmation", "RecoveryAuthorizationRules");
        return List.copyOf(routes);
    }

    private static void fraudCaseRoutes(List<RouteOwnership> routes, String base) {
        route(routes, "GET", base, "FraudCaseAuthorizationRules");
        route(routes, "GET", base + "/work-queue", "FraudCaseAuthorizationRules");
        route(routes, "POST", base, "FraudCaseAuthorizationRules");
        route(routes, "GET", base + "/{caseId}", "FraudCaseAuthorizationRules");
        route(routes, "POST", base + "/{caseId}/assign", "FraudCaseAuthorizationRules");
        route(routes, "POST", base + "/{caseId}/notes", "FraudCaseAuthorizationRules");
        route(routes, "POST", base + "/{caseId}/decisions", "FraudCaseAuthorizationRules");
        route(routes, "POST", base + "/{caseId}/transition", "FraudCaseAuthorizationRules");
        route(routes, "POST", base + "/{caseId}/close", "FraudCaseAuthorizationRules");
        route(routes, "POST", base + "/{caseId}/reopen", "FraudCaseAuthorizationRules");
        route(routes, "GET", base + "/{caseId}/audit", "FraudCaseAuthorizationRules");
        route(routes, "PATCH", base + "/{caseId}", "FraudCaseAuthorizationRules");
    }

    private static void route(List<RouteOwnership> routes, String method, String pattern, String owner) {
        routes.add(new RouteOwnership(method, pattern, owner));
    }

    record RouteOwnership(String method, String pattern, String owner) {
    }
}
