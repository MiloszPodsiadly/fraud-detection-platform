package com.frauddetection.alert.governance.modelpolicy;

import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;

/** Rechecks the authorization before a target-bound bearer capability can be issued. */
@Component
public class PolicyCredentialGrantAuthorizationVerifier {
    private final MongoTemplate mongo;
    private final PolicyCredentialBootstrapAuthority bootstrap;
    private final GovernanceBoundWebAuthnProofVerifier signatures;

    public PolicyCredentialGrantAuthorizationVerifier(MongoTemplate mongo, ObjectMapper mapper,
            PolicyCredentialBootstrapAuthority bootstrap) {
        this.mongo = mongo;
        this.bootstrap = bootstrap;
        this.signatures = new GovernanceBoundWebAuthnProofVerifier(mapper);
    }

    public void verify(PolicyCredentialEnrollmentGrantDocument grant, Instant now) {
        if (grant == null || grant.approvedAt() == null || grant.approvedBy() == null
                || grant.requestExpiresAt() == null
                || (grant.reviewProofId() == null) == (grant.bootstrapAuthorizationId() == null))
            throw new SecurityException("Independent credential grant authorization missing");
        var audit = mongo.findById("grant-review:" + grant.id(),
                PolicyCredentialSecurityEventDocument.class);
        if (audit == null || !grant.targetActorId().equals(audit.targetActorId())
                || !grant.targetIssuer().equals(audit.targetIssuer())
                || !grant.targetSubject().equals(audit.targetSubject())
                || !grant.requestedBy().equals(audit.actorId())
                || !grant.approvedBy().equals(audit.reviewerActorId())
                || !grant.caseReference().equals(audit.approvalReference())
                || !grant.approvedAt().equals(audit.occurredAt()))
            throw new SecurityException("Credential grant authorization audit differs");
        if (grant.bootstrapAuthorizationId() != null) {
            var claim = mongo.findById(grant.bootstrapAuthorizationId(),
                    PolicyCredentialBootstrapClaimDocument.class);
            if (claim == null || !grant.id().equals(claim.requestId())
                    || !grant.approvedBy().equals(claim.approvedBy())
                    || !grant.approvedAt().equals(claim.consumedAt())
                    || !"GRANT_BOOTSTRAP_APPROVED".equals(audit.action()))
                throw new SecurityException("First-install authority claim differs");
            if (!bootstrap.require(grant, grant.approvedBy(), now).authorizationId()
                    .equals(grant.bootstrapAuthorizationId()))
                throw new SecurityException("First-install authority changed");
            return;
        }
        if (!"GRANT_APPROVED".equals(audit.action()) || grant.reviewOperationId() == null
                || grant.reviewCredentialId() == null || grant.reviewAssertionSha256() == null
                || grant.plannedGrantExpiresAt() == null
                || !grant.plannedGrantExpiresAt().equals(grant.expiresAt()))
            throw new SecurityException("Signed grant approval receipt missing");
        var plan = mongo.findById(grant.reviewProofId(), PolicyCredentialGrantReviewPlanDocument.class);
        if (plan == null || !grant.id().equals(plan.requestId())
                || !grant.approvedBy().equals(plan.reviewerId())
                || plan.decision() != PolicyCredentialGrantReviewService.Decision.APPROVE
                || !grant.plannedGrantExpiresAt().equals(plan.grantExpiresAt()))
            throw new SecurityException("Signed grant approval plan differs");
        var proof = mongo.findById(grant.reviewProofId(), GovernanceBoundWebAuthnProof.class);
        var credential = mongo.findById(grant.reviewCredentialId(), PolicyWebAuthnCredential.class);
        if (proof == null || credential == null || !credential.canSignNewAction()
                || !grant.approvedBy().equals(credential.actorId())
                || !grant.reviewProofId().equals(proof.id())
                || !"credential-grant-review".equals(proof.namespace())
                || !grant.approvedBy().equals(proof.actorId())
                || !grant.reviewCredentialId().equals(proof.credentialId())
                || !grant.reviewAssertionSha256().equals(proof.assertionSha256()))
            throw new SecurityException("Credential grant proof or reviewer credential differs");
        signatures.verifyHistorical(proof, PolicyCredentialGrantReviewService.contract(grant,
                grant.approvedBy(), new PolicyCredentialGrantReviewService.ReviewIntent(
                        grant.reviewOperationId(), PolicyCredentialGrantReviewService.Decision.APPROVE),
                grant.plannedGrantExpiresAt()));
    }
}
