package com.frauddetection.alert.security.config;

import java.util.ArrayList;
import java.util.List;

/**
 * Test contract only. Runtime security source of truth remains the *AuthorizationRules classes composed by
 * AlertEndpointAuthorizationRules.
 */
final class SecurityRouteOwnershipRegistry {

    static final List<RouteOwnership> MVC_ROUTES = mvcRoutes();
    static final List<String> DENY_BY_DEFAULT_FAMILIES = List.of(
            "/api/**",
            "/api/v1/**",
            "/governance/**",
            "/system/**",
            "/internal/**",
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
        route(routes, "GET", "/api/v1/fraud-cases/work-queue/summary", "FraudCaseAuthorizationRules");
        route(routes, "GET", "/api/v1/transactions/scored", "TransactionAuthorizationRules");
        route(routes, "GET", "/api/v1/transactions/scored/{transactionId}", "TransactionAuthorizationRules");
        route(routes, "GET", "/api/v1/transactions/scored/{transactionId}/engine-intelligence",
                "TransactionAuthorizationRules");
        route(routes, "GET", "/api/v1/transactions/scored/{transactionId}/engine-intelligence/feedback",
                "TransactionAuthorizationRules");
        route(routes, "POST", "/api/v1/transactions/scored/{transactionId}/engine-intelligence/feedback",
                "TransactionAuthorizationRules");
        route(routes, "GET", "/api/v1/transactions/scored/{transactionId}/feedback",
                "TransactionAuthorizationRules");
        route(routes, "POST", "/api/v1/transactions/scored/{transactionId}/feedback",
                "TransactionAuthorizationRules");
        route(routes, "GET", "/internal/suspicious-transactions", "SuspiciousTransactionAuthorizationRules");
        route(routes, "GET", "/internal/suspicious-transactions/summary", "SuspiciousTransactionAuthorizationRules");
        route(routes, "GET", "/internal/suspicious-transactions/{suspiciousTransactionId}/linked-alert",
                "SuspiciousTransactionAuthorizationRules");
        route(routes, "GET", "/internal/suspicious-transactions/{suspiciousTransactionId}",
                "SuspiciousTransactionAuthorizationRules");
        route(routes, "GET", "/governance/advisories/analytics", "GovernanceAuthorizationRules");
        route(routes, "GET", "/governance/advisories", "GovernanceAuthorizationRules");
        route(routes, "GET", "/governance/advisories/{eventId}", "GovernanceAuthorizationRules");
        route(routes, "GET", "/governance/advisories/{eventId}/audit", "GovernanceAuthorizationRules");
        route(routes, "POST", "/governance/advisories/{eventId}/audit", "GovernanceAuthorizationRules");
        route(routes, "GET", "/api/v2/governance/shadow-performance/summary/current",
                "GovernanceAuthorizationRules");
        route(routes, "GET", "/api/v1/governance/promotion-review-readiness/current",
                "GovernanceAuthorizationRules");
        route(routes, "GET", "/api/v1/governance/model-card/current", "GovernanceAuthorizationRules");
        route(routes, "GET", "/api/v1/governance/model-shadow-performance", "GovernanceAuthorizationRules");
        route(routes, "GET", "/api/v1/governance/model-promotion/state", "GovernanceAuthorizationRules");
        route(routes, "GET", "/api/v1/governance/model-promotion/assessment", "GovernanceAuthorizationRules");
        route(routes, "POST", "/api/v1/governance/model-promotion/candidates", "GovernanceAuthorizationRules");
        route(routes, "POST", "/api/v1/governance/model-promotion/proposals", "GovernanceAuthorizationRules");
        route(routes, "POST", "/api/v1/governance/model-promotion/retirement-proposals", "GovernanceAuthorizationRules");
        route(routes, "POST", "/api/v1/governance/model-promotion/proposals/{proposalId}/reviews",
                "GovernanceAuthorizationRules");
        route(routes, "POST", "/api/v1/governance/model-promotion/proposals/{proposalId}/reviews/challenges",
                "GovernanceAuthorizationRules");
        route(routes, "POST", "/api/v1/governance/model-promotion/proposals/{proposalId}/reviews/signed",
                "GovernanceAuthorizationRules");
        route(routes, "GET", "/api/v1/governance/model-promotion/proposals/{proposalId}/reviews/operations/{operationId}",
                "GovernanceAuthorizationRules");
        route(routes, "POST", "/api/v1/governance/model-promotion/proposals/{proposalId}/reviews/emergency-authorizations",
                "GovernanceAuthorizationRules");
        route(routes, "POST", "/api/v1/governance/model-promotion/proposals/{proposalId}/reviews/emergency",
                "GovernanceAuthorizationRules");
        route(routes, "POST", "/api/v1/governance/model-promotion/emergency-authorizations/{authorizationId}/claim",
                "GovernanceAuthorizationRules");
        route(routes, "GET", "/api/v1/governance/model-promotion/emergency-authorizations/active",
                "GovernanceAuthorizationRules");
        route(routes, "POST", "/api/v1/governance/model-promotion/emergency-authorizations/active/revoke",
                "GovernanceAuthorizationRules");
        route(routes, "GET", "/api/v1/governance/model-promotion/emergency-authorizations/{authorizationId}",
                "GovernanceAuthorizationRules");
        route(routes, "GET", "/api/v1/governance/model-promotion/emergency-authorizations/{authorizationId}/audit",
                "GovernanceAuthorizationRules");
        route(routes, "POST", "/api/v1/governance/model-promotion/emergency-authorizations/{authorizationId}/approve",
                "GovernanceAuthorizationRules");
        route(routes, "POST", "/api/v1/governance/model-promotion/emergency-authorizations/{authorizationId}/revoke",
                "GovernanceAuthorizationRules");
        policyGovernanceRoutes(routes, "/api/v1/governance/model-policies");
        route(routes, "GET", "/api/v1/governance/policy-security/me", "GovernanceAuthorizationRules");
        route(routes, "GET", "/api/v1/governance/policy-security/operations", "GovernanceAuthorizationRules");
        route(routes, "GET", "/api/v1/governance/audit/commitments/{kind}/{eventId}",
                "GovernanceAuthorizationRules");
        route(routes, "GET", "/api/v1/governance/policy-trust/registries", "GovernanceAuthorizationRules");
        route(routes, "GET", "/api/v1/governance/policy-trust/requests", "GovernanceAuthorizationRules");
        route(routes, "GET", "/api/v1/governance/policy-trust/requests/{id}/audit", "GovernanceAuthorizationRules");
        route(routes, "GET", "/api/v1/governance/policy-trust/requests/{id}/deployment", "GovernanceAuthorizationRules");
        route(routes, "GET", "/api/v1/governance/policy-trust/requests/{id}/candidate", "GovernanceAuthorizationRules");
        route(routes, "GET", "/api/v1/governance/policy-trust/requests/{id}/installation", "GovernanceAuthorizationRules");
        route(routes, "POST", "/api/v1/governance/policy-trust/requests/preview", "GovernanceAuthorizationRules");
        route(routes, "POST", "/api/v1/governance/policy-trust/requests/challenges", "GovernanceAuthorizationRules");
        route(routes, "POST", "/api/v1/governance/policy-trust/requests", "GovernanceAuthorizationRules");
        route(routes, "POST", "/api/v1/governance/policy-trust/requests/{id}/reviews/challenges", "GovernanceAuthorizationRules");
        route(routes, "POST", "/api/v1/governance/policy-trust/requests/{id}/reviews", "GovernanceAuthorizationRules");
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
        route(routes, "POST", "/api/v1/regulated-mutations/recover", "RecoveryAuthorizationRules");
        route(routes, "GET", "/api/v1/regulated-mutations/recovery/backlog", "RecoveryAuthorizationRules");
        route(routes, "GET", "/api/v1/regulated-mutations/by-command/{commandId}", "RecoveryAuthorizationRules");
        route(routes, "GET", "/api/v1/regulated-mutations/by-idempotency-hash/{hash}",
                "RecoveryAuthorizationRules");
        route(routes, "GET", "/api/v1/outbox/recovery/backlog", "RecoveryAuthorizationRules");
        route(routes, "POST", "/api/v1/outbox/recovery/run", "RecoveryAuthorizationRules");
        route(routes, "POST", "/api/v1/outbox/{eventId}/resolve-confirmation", "RecoveryAuthorizationRules");
        route(routes, "GET", "/api/v1/outbox/fraud-alerts/recovery/backlog", "RecoveryAuthorizationRules");
        route(routes, "POST", "/api/v1/outbox/fraud-alerts/{eventId}/resolve-confirmation", "RecoveryAuthorizationRules");
        return List.copyOf(routes);
    }

    private static void fraudCaseRoutes(List<RouteOwnership> routes, String base) {
        route(routes, "GET", base + "/work-queue", "FraudCaseAuthorizationRules");
        route(routes, "GET", base + "/{caseId}", "FraudCaseAuthorizationRules");
        route(routes, "GET", base + "/{caseId}/evidence-summary", "FraudCaseAuthorizationRules");
        route(routes, "GET", base + "/{caseId}/evidence-timeline", "FraudCaseAuthorizationRules");
        route(routes, "PATCH", base + "/{caseId}", "FraudCaseAuthorizationRules");
    }

    private static void policyGovernanceRoutes(List<RouteOwnership> routes, String base) {
        route(routes, "GET", base, "GovernanceAuthorizationRules");
        route(routes, "GET", base + "/page", "GovernanceAuthorizationRules");
        route(routes, "GET", base + "/me", "GovernanceAuthorizationRules");
        route(routes, "GET", base + "/schema", "GovernanceAuthorizationRules");
        route(routes, "GET", base + "/approvers", "GovernanceAuthorizationRules");
        route(routes, "GET", base + "/operations/{operationId}", "GovernanceAuthorizationRules");
        route(routes, "GET", base + "/{id}", "GovernanceAuthorizationRules");
        route(routes, "GET", base + "/{id}/history", "GovernanceAuthorizationRules");
        route(routes, "GET", base + "/{id}/history/page", "GovernanceAuthorizationRules");
        route(routes, "GET", base + "/{id}/publication", "GovernanceAuthorizationRules");
        route(routes, "GET", base + "/{id}/publication/recovery", "GovernanceAuthorizationRules");
        route(routes, "POST", base + "/{id}/publication/recovery", "GovernanceAuthorizationRules");
        route(routes, "POST", base + "/{id}/publication/recovery/reconcile", "GovernanceAuthorizationRules");
        route(routes, "POST", base + "/drafts", "GovernanceAuthorizationRules");
        route(routes, "PUT", base + "/drafts/{id}", "GovernanceAuthorizationRules");
        route(routes, "POST", base + "/{id}/submit", "GovernanceAuthorizationRules");
        route(routes, "POST", base + "/{id}/submit/challenges", "GovernanceAuthorizationRules");
        route(routes, "POST", base + "/{id}/review", "GovernanceAuthorizationRules");
        route(routes, "POST", base + "/{id}/review/challenges", "GovernanceAuthorizationRules");
        route(routes, "POST", base + "/{id}/publication-requests", "GovernanceAuthorizationRules");
        route(routes, "POST", base + "/webauthn/registrations", "GovernanceAuthorizationRules");
        route(routes, "POST", base + "/webauthn/registrations/complete", "GovernanceAuthorizationRules");
        route(routes, "GET", base + "/webauthn/credentials", "GovernanceAuthorizationRules");
        route(routes, "GET", base + "/webauthn/security-events", "GovernanceAuthorizationRules");
        route(routes, "PUT", base + "/webauthn/credentials/{credentialId}/label", "GovernanceAuthorizationRules");
        route(routes, "POST", base + "/webauthn/credentials/{credentialId}/revocation-challenges", "GovernanceAuthorizationRules");
        route(routes, "POST", base + "/webauthn/credentials/{credentialId}/revoke", "GovernanceAuthorizationRules");
        route(routes, "POST", base + "/webauthn/credentials/{credentialId}/protected-revoke", "GovernanceAuthorizationRules");
        route(routes, "POST", base + "/webauthn/enrollment-grants", "GovernanceAuthorizationRules");
        route(routes, "GET", base + "/webauthn/enrollment-grants/mine", "GovernanceAuthorizationRules");
        route(routes, "GET", base + "/webauthn/enrollment-grants/incidents", "GovernanceAuthorizationRules");
        route(routes, "POST", base + "/webauthn/enrollment-grants/{id}/claim", "GovernanceAuthorizationRules");
        route(routes, "GET", base + "/webauthn/enrollment-grants/{id}/status", "GovernanceAuthorizationRules");
        route(routes, "POST", base + "/webauthn/enrollment-grants/{id}/revoke", "GovernanceAuthorizationRules");
        route(routes, "GET", base + "/webauthn/enrollment-grants/{id}/review", "GovernanceAuthorizationRules");
        route(routes, "POST", base + "/webauthn/enrollment-grants/{id}/review/challenges", "GovernanceAuthorizationRules");
        route(routes, "POST", base + "/webauthn/enrollment-grants/{id}/review", "GovernanceAuthorizationRules");
        route(routes, "POST", base + "/webauthn/enrollment-grants/{id}/bootstrap-approval", "GovernanceAuthorizationRules");
        route(routes, "GET", base + "/webauthn/enrollment-grants/{id}/evidence", "GovernanceAuthorizationRules");
    }

    private static void route(List<RouteOwnership> routes, String method, String pattern, String owner) {
        routes.add(new RouteOwnership(method, pattern, owner));
    }

    record RouteOwnership(String method, String pattern, String owner) {
    }
}
