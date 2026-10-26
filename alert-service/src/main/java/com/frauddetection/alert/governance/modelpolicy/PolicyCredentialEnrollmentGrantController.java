package com.frauddetection.alert.governance.modelpolicy;

import com.frauddetection.alert.audit.read.AuditedSensitiveRead;
import com.frauddetection.alert.audit.read.ReadAccessEndpointCategory;
import com.frauddetection.alert.audit.read.ReadAccessResourceType;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;

/** Institutional approval and target-only one-time claim; secrets never reach the reviewer. */
@RestController
@RequestMapping("/api/v1/governance/model-policies/webauthn/enrollment-grants")
public class PolicyCredentialEnrollmentGrantController {
    private final PolicyCredentialEnrollmentGrantService grants;
    private final PolicyCredentialGrantReviewService reviews;

    public PolicyCredentialEnrollmentGrantController(PolicyCredentialEnrollmentGrantService grants,
            PolicyCredentialGrantReviewService reviews) {
        this.grants = grants;
        this.reviews = reviews;
    }

    @PostMapping
    public ResponseEntity<PolicyCredentialEnrollmentGrantService.GrantRequestSummary> request(
            Authentication authentication, @RequestBody PolicyCredentialEnrollmentGrantService.GrantRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(grants.request(PolicyGovernanceActor.fromOidcSession(authentication), body));
    }

    @GetMapping("/mine")
    @AuditedSensitiveRead(category = ReadAccessEndpointCategory.POLICY_CREDENTIAL_GRANT_MINE,
            resourceType = ReadAccessResourceType.POLICY_CREDENTIAL_GRANT)
    public java.util.List<PolicyCredentialEnrollmentGrantService.GrantView> mine(
            Authentication authentication) {
        return grants.mine(PolicyGovernanceActor.fromOidcSession(authentication));
    }

    @PostMapping("/{id}/claim")
    public PolicyCredentialEnrollmentGrantService.IssuedGrant claim(Authentication authentication,
            @PathVariable String id) {
        return grants.claim(PolicyGovernanceActor.fromOidcSession(authentication), id);
    }

    @GetMapping("/{id}/review")
    @AuditedSensitiveRead(category = ReadAccessEndpointCategory.POLICY_CREDENTIAL_GRANT_REVIEW,
            resourceType = ReadAccessResourceType.POLICY_CREDENTIAL_GRANT, resourceIdVariable = "id")
    public PolicyCredentialEnrollmentGrantService.GrantReviewView reviewView(
            Authentication authentication, @PathVariable String id) {
        return grants.reviewView(PolicyGovernanceActor.fromOidcSession(authentication), id);
    }

    @AuditedSensitiveRead(category = ReadAccessEndpointCategory.POLICY_CREDENTIAL_GRANT_STATUS,
            resourceType = ReadAccessResourceType.POLICY_CREDENTIAL_GRANT, resourceIdVariable = "id")
    @GetMapping("/{id}/status")
    public PolicyCredentialEnrollmentGrantService.GrantStatus status(Authentication authentication,
            @PathVariable String id) {
        return grants.status(PolicyGovernanceActor.fromOidcSession(authentication), id);
    }

    @PostMapping("/{id}/revoke")
    public PolicyCredentialEnrollmentGrantService.GrantStatus revoke(Authentication authentication,
            @PathVariable String id,
            @RequestBody PolicyCredentialEnrollmentGrantService.RevocationCommand command) {
        return grants.revoke(PolicyGovernanceActor.fromOidcSession(authentication), id, command);
    }

    @AuditedSensitiveRead(category = ReadAccessEndpointCategory.POLICY_CREDENTIAL_GRANT_INCIDENTS,
            resourceType = ReadAccessResourceType.POLICY_CREDENTIAL_GRANT,
            resourceIdParameter = "actorId")
    @GetMapping("/incidents")
    public PolicyCredentialEnrollmentGrantService.IncidentGrantPage incidentGrants(
            Authentication authentication, @RequestParam String actorId,
            @RequestParam(required = false) String afterId) {
        return grants.incidentGrants(PolicyGovernanceActor.fromOidcSession(authentication), actorId, afterId);
    }

    @PostMapping("/{id}/review/challenges")
    public PolicyWebAuthnService.CeremonyOptions startReview(Authentication authentication,
            @PathVariable String id, @RequestBody PolicyCredentialGrantReviewService.ReviewIntent body) {
        return reviews.start(PolicyGovernanceActor.fromOidcSession(authentication), id, body);
    }

    @PostMapping("/{id}/review")
    public PolicyCredentialGrantReviewService.ReviewResult finishReview(Authentication authentication,
            @PathVariable String id, @RequestBody PolicyCredentialGrantReviewService.FinishReview body) {
        return reviews.finish(PolicyGovernanceActor.fromOidcSession(authentication), id, body);
    }

    @PostMapping("/{id}/bootstrap-approval")
    public PolicyCredentialGrantReviewService.ReviewResult bootstrapApprove(
            Authentication authentication, @PathVariable String id) {
        return reviews.bootstrapApprove(PolicyGovernanceActor.fromOidcSession(authentication), id);
    }

    @AuditedSensitiveRead(category = ReadAccessEndpointCategory.POLICY_CREDENTIAL_GRANT_EVIDENCE,
            resourceType = ReadAccessResourceType.POLICY_CREDENTIAL_GRANT, resourceIdVariable = "id")
    @GetMapping("/{id}/evidence")
    public PolicyCredentialGrantReviewService.ReviewEvidence evidence(
            Authentication authentication, @PathVariable String id) {
        return reviews.verifyHistorical(PolicyGovernanceActor.fromOidcSession(authentication), id);
    }
}
