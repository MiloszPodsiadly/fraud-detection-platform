package com.frauddetection.alert.security.config;

import com.frauddetection.alert.security.authorization.AnalystAuthority;
import com.frauddetection.alert.security.authorization.ModelPolicyAuthority;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer;

class GovernanceAuthorizationRules implements EndpointAuthorizationRuleGroup {

    public void configure(AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizationManagerRequestMatcherRegistry authorize) {
        authorize
                .requestMatchers(HttpMethod.GET, "/api/v1/governance/model-policies")
                .hasAuthority(ModelPolicyAuthority.READ)
                .requestMatchers(HttpMethod.GET, "/api/v1/governance/model-policies/page")
                .hasAuthority(ModelPolicyAuthority.READ)
                .requestMatchers(HttpMethod.GET, "/api/v1/governance/model-policies/me")
                .hasAuthority(ModelPolicyAuthority.READ)
                .requestMatchers(HttpMethod.GET, "/api/v1/governance/model-policies/schema")
                .hasAuthority(ModelPolicyAuthority.READ)
                .requestMatchers(HttpMethod.GET, "/api/v1/governance/model-policies/operations/*")
                .hasAuthority(ModelPolicyAuthority.READ)
                .requestMatchers(HttpMethod.GET, "/api/v1/governance/model-policies/approvers")
                .hasAuthority(ModelPolicyAuthority.DIRECTORY_READ)
                .requestMatchers(HttpMethod.GET, "/api/v1/governance/policy-trust/registries",
                        "/api/v1/governance/policy-trust/requests",
                        "/api/v1/governance/policy-trust/requests/*/audit",
                        "/api/v1/governance/policy-trust/requests/*/deployment",
                        "/api/v1/governance/policy-trust/requests/*/candidate")
                .hasAuthority(ModelPolicyAuthority.TRUST_READ)
                .requestMatchers(HttpMethod.GET, "/api/v1/governance/policy-trust/requests/*/installation")
                .hasAuthority(ModelPolicyAuthority.AUDIT_READ)
                .requestMatchers(HttpMethod.GET, "/api/v1/governance/policy-security/me")
                .hasAnyAuthority(ModelPolicyAuthority.CREDENTIAL_ENROLL, ModelPolicyAuthority.CREDENTIAL_ADMIN,
                        ModelPolicyAuthority.TRUST_READ, ModelPolicyAuthority.TRUST_REQUEST,
                        ModelPolicyAuthority.TRUST_APPROVE, ModelPolicyAuthority.PUBLICATION_RECOVER)
                .requestMatchers(HttpMethod.GET, "/api/v1/governance/policy-security/operations")
                .hasAuthority(ModelPolicyAuthority.TRUST_READ)
                .requestMatchers(HttpMethod.POST, "/api/v1/governance/policy-trust/requests/preview",
                        "/api/v1/governance/policy-trust/requests/challenges",
                        "/api/v1/governance/policy-trust/requests")
                .hasAuthority(ModelPolicyAuthority.TRUST_REQUEST)
                .requestMatchers(HttpMethod.POST, "/api/v1/governance/policy-trust/requests/*/reviews/challenges",
                        "/api/v1/governance/policy-trust/requests/*/reviews")
                .hasAuthority(ModelPolicyAuthority.TRUST_APPROVE)
                .requestMatchers(HttpMethod.GET,
                        "/api/v1/governance/model-policies/webauthn/enrollment-grants/*/review")
                .hasAuthority(ModelPolicyAuthority.CREDENTIAL_ADMIN)
                .requestMatchers(HttpMethod.GET,
                        "/api/v1/governance/model-policies/webauthn/enrollment-grants/*/evidence")
                .hasAuthority(ModelPolicyAuthority.AUDIT_READ)
                .requestMatchers(HttpMethod.GET,
                        "/api/v1/governance/model-policies/webauthn/enrollment-grants/mine")
                .hasAuthority(ModelPolicyAuthority.CREDENTIAL_ENROLL)
                .requestMatchers(HttpMethod.POST, "/api/v1/governance/model-policies/webauthn/enrollment-grants",
                        "/api/v1/governance/model-policies/webauthn/enrollment-grants/*/review/challenges",
                        "/api/v1/governance/model-policies/webauthn/enrollment-grants/*/review",
                        "/api/v1/governance/model-policies/webauthn/enrollment-grants/*/bootstrap-approval")
                .hasAuthority(ModelPolicyAuthority.CREDENTIAL_ADMIN)
                .requestMatchers(HttpMethod.POST,
                        "/api/v1/governance/model-policies/webauthn/enrollment-grants/*/claim")
                .hasAuthority(ModelPolicyAuthority.CREDENTIAL_ENROLL)
                .requestMatchers(HttpMethod.POST, "/api/v1/governance/model-policies/webauthn/registrations",
                        "/api/v1/governance/model-policies/webauthn/registrations/complete")
                .hasAuthority(ModelPolicyAuthority.CREDENTIAL_ENROLL)
                .requestMatchers(HttpMethod.GET, "/api/v1/governance/model-policies/webauthn/credentials")
                .hasAuthority(ModelPolicyAuthority.CREDENTIAL_ENROLL)
                .requestMatchers(HttpMethod.GET, "/api/v1/governance/model-policies/webauthn/security-events")
                .hasAuthority(ModelPolicyAuthority.CREDENTIAL_ENROLL)
                .requestMatchers(HttpMethod.PUT, "/api/v1/governance/model-policies/webauthn/credentials/*/label")
                .hasAuthority(ModelPolicyAuthority.CREDENTIAL_ENROLL)
                .requestMatchers(HttpMethod.POST,
                        "/api/v1/governance/model-policies/webauthn/credentials/*/revocation-challenges",
                        "/api/v1/governance/model-policies/webauthn/credentials/*/revoke",
                        "/api/v1/governance/model-policies/webauthn/credentials/*/protected-revoke")
                .hasAuthority(ModelPolicyAuthority.CREDENTIAL_ENROLL)
                .requestMatchers(HttpMethod.GET, "/api/v1/governance/model-policies/*/history")
                .hasAuthority(ModelPolicyAuthority.AUDIT_READ)
                .requestMatchers(HttpMethod.GET, "/api/v1/governance/model-policies/*/history/page")
                .hasAuthority(ModelPolicyAuthority.AUDIT_READ)
                .requestMatchers(HttpMethod.GET, "/api/v1/governance/model-policies/*/publication")
                .hasAuthority(ModelPolicyAuthority.READ)
                .requestMatchers(HttpMethod.GET, "/api/v1/governance/model-policies/*/publication/recovery")
                .hasAuthority(ModelPolicyAuthority.PUBLICATION_RECOVER)
                .requestMatchers(HttpMethod.POST, "/api/v1/governance/model-policies/*/publication/recovery")
                .hasAuthority(ModelPolicyAuthority.PUBLICATION_RECOVER)
                .requestMatchers(HttpMethod.GET, "/api/v1/governance/model-policies/*")
                .hasAuthority(ModelPolicyAuthority.READ)
                .requestMatchers(HttpMethod.POST, "/api/v1/governance/model-policies/drafts")
                .hasAuthority(ModelPolicyAuthority.CREATE)
                .requestMatchers(HttpMethod.PUT, "/api/v1/governance/model-policies/drafts/*")
                .hasAuthority(ModelPolicyAuthority.CREATE)
                .requestMatchers(HttpMethod.POST, "/api/v1/governance/model-policies/*/submit")
                .hasAuthority(ModelPolicyAuthority.SUBMIT)
                .requestMatchers(HttpMethod.POST, "/api/v1/governance/model-policies/*/submit/challenges")
                .hasAuthority(ModelPolicyAuthority.SUBMIT)
                .requestMatchers(HttpMethod.POST, "/api/v1/governance/model-policies/*/review")
                .hasAnyAuthority(ModelPolicyAuthority.APPROVE, ModelPolicyAuthority.REJECT)
                .requestMatchers(HttpMethod.POST, "/api/v1/governance/model-policies/*/review/challenges")
                .hasAnyAuthority(ModelPolicyAuthority.APPROVE, ModelPolicyAuthority.REJECT)
                .requestMatchers(HttpMethod.POST, "/api/v1/governance/model-policies/*/publication-requests")
                .hasAuthority(ModelPolicyAuthority.PUBLISH_REQUEST)
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
                .requestMatchers(HttpMethod.POST,
                        "/api/v1/governance/model-promotion/proposals/{proposalId}/reviews/challenges",
                        "/api/v1/governance/model-promotion/proposals/{proposalId}/reviews/signed")
                .hasAnyAuthority(AnalystAuthority.MODEL_PROMOTION_APPROVE, AnalystAuthority.MODEL_PROMOTION_REJECT)
                .requestMatchers(HttpMethod.GET,
                        "/api/v1/governance/model-promotion/proposals/{proposalId}/reviews/operations/{operationId}")
                .hasAnyAuthority(AnalystAuthority.MODEL_PROMOTION_APPROVE, AnalystAuthority.MODEL_PROMOTION_REJECT)
                .requestMatchers(HttpMethod.POST,
                        "/api/v1/governance/model-promotion/proposals/{proposalId}/reviews/emergency-authorizations",
                        "/api/v1/governance/model-promotion/proposals/{proposalId}/reviews/emergency")
                .hasAuthority(AnalystAuthority.MODEL_PROMOTION_EMERGENCY_REQUEST)
                .requestMatchers(HttpMethod.POST,
                        "/api/v1/governance/model-promotion/emergency-authorizations/{authorizationId}/approve",
                        "/api/v1/governance/model-promotion/emergency-authorizations/{authorizationId}/revoke")
                .hasAuthority(AnalystAuthority.MODEL_PROMOTION_EMERGENCY_AUTHORIZE)
                .requestMatchers(HttpMethod.GET,
                        "/api/v1/governance/model-promotion/emergency-authorizations/{authorizationId}")
                .hasAuthority(AnalystAuthority.MODEL_PROMOTION_EMERGENCY_AUTHORIZE)
                .requestMatchers(HttpMethod.GET,
                        "/api/v1/governance/model-promotion/emergency-authorizations/{authorizationId}/audit")
                .hasAuthority(ModelPolicyAuthority.AUDIT_READ)
                .requestMatchers(HttpMethod.GET, "/governance/advisories/analytics").hasAuthority(AnalystAuthority.TRANSACTION_MONITOR_READ)
                .requestMatchers(HttpMethod.GET, "/governance/advisories").hasAuthority(AnalystAuthority.TRANSACTION_MONITOR_READ)
                .requestMatchers(HttpMethod.GET, "/governance/advisories/{eventId}").hasAuthority(AnalystAuthority.TRANSACTION_MONITOR_READ)
                .requestMatchers(HttpMethod.GET, "/governance/advisories/{eventId}/audit").hasAuthority(AnalystAuthority.TRANSACTION_MONITOR_READ)
                .requestMatchers(HttpMethod.POST, "/governance/advisories/{eventId}/audit").hasAuthority(AnalystAuthority.GOVERNANCE_ADVISORY_AUDIT_WRITE);
    }
}
