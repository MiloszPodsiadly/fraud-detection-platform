package com.frauddetection.alert.governance.modelpolicy;

import com.frauddetection.alert.security.authorization.ModelPolicyAuthority;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Independent, per-decision WebAuthn authorization; no bearer capability is returned to the reviewer. */
@Service
public class PolicyCredentialGrantReviewService {
    private static final String NAMESPACE = "credential-grant-review";
    private static final String DOMAIN = "com.frauddetection.credential-grant.review.v1";
    private static final int GRANT_SECONDS = 600;
    private final MongoTemplate mongo;
    private final TransactionTemplate transactions;
    private final MongoPolicyGovernanceStore clock;
    private final PolicyWebAuthnService webAuthn;
    private final PolicyCredentialEnrollmentGrantService grants;
    private final GovernanceBoundWebAuthnProofVerifier verifier;
    private final PolicyCredentialBootstrapAuthority bootstrap;
    private final PolicyWebAuthnCredentialRepository credentials;

    public PolicyCredentialGrantReviewService(MongoTemplate mongo, MongoTransactionManager manager,
            MongoPolicyGovernanceStore clock, PolicyWebAuthnService webAuthn,
            PolicyCredentialEnrollmentGrantService grants, ObjectMapper mapper,
            PolicyCredentialBootstrapAuthority bootstrap,
            PolicyWebAuthnCredentialRepository credentials) {
        this.mongo = mongo;
        this.transactions = new TransactionTemplate(manager);
        this.clock = clock;
        this.webAuthn = webAuthn;
        this.grants = grants;
        this.verifier = new GovernanceBoundWebAuthnProofVerifier(mapper);
        this.bootstrap = bootstrap;
        this.credentials = credentials;
    }

    public PolicyWebAuthnService.CeremonyOptions start(PolicyGovernanceActor reviewer,
            String requestId, ReviewIntent intent) {
        var request = pending(reviewer, requestId, intent);
        Instant grantExpiresAt = clock.now().plusSeconds(GRANT_SECONDS);
        var ceremony = webAuthn.startBoundAssertion(reviewer, NAMESPACE, intent.operationId(),
                contract(request, reviewer.id(), intent, grantExpiresAt));
        mongo.insert(new PolicyCredentialGrantReviewPlanDocument(ceremony.challengeId(),
                requestId, reviewer.id(), intent.decision(), grantExpiresAt));
        return ceremony;
    }

    public ReviewResult finish(PolicyGovernanceActor reviewer, String requestId,
            FinishReview command) {
        if (command == null || command.challengeId() == null || command.intent() == null
                || !command.challengeId().equals(NAMESPACE + ":" + command.intent().operationId()))
            throw new PolicyGovernanceConflictException("Credential grant review operation changed");
        return transactions.execute(ignored -> {
            var request = pending(reviewer, requestId, command.intent());
            var plan = mongo.findById(command.challengeId(),
                    PolicyCredentialGrantReviewPlanDocument.class);
            if (plan == null || !requestId.equals(plan.requestId())
                    || !reviewer.id().equals(plan.reviewerId())
                    || command.intent().decision() != plan.decision()
                    || !clock.now().isBefore(plan.grantExpiresAt()))
                throw new PolicyGovernanceConflictException("Exact grant review plan unavailable or expired");
            var proof = webAuthn.acceptBoundAssertion(reviewer, NAMESPACE,
                    command.challengeId(), command.responseJson(),
                    contract(request, reviewer.id(), command.intent(), plan.grantExpiresAt()));
            Instant now = clock.now();
            Update update = new Update().set("approvedBy", reviewer.id())
                    .set("reviewOperationId", command.intent().operationId())
                    .set("reviewProofId", proof.challengeId())
                    .set("reviewCredentialId", proof.credentialId())
                    .set("reviewAssertionSha256", proof.assertionSha256())
                    .set("plannedGrantExpiresAt", plan.grantExpiresAt());
            if (command.intent().decision() == Decision.APPROVE) {
                update.set("approvedAt", now).set("expiresAt", plan.grantExpiresAt());
            } else {
                update.set("rejectedAt", now);
            }
            var reviewed = mongo.findAndModify(Query.query(Criteria.where("_id").is(requestId)
                            .and("approvedAt").is(null).and("rejectedAt").is(null)
                            .and("revokedAt").is(null)
                            .and("expiresAt").gt(now)), update,
                    FindAndModifyOptions.options().returnNew(true),
                    PolicyCredentialEnrollmentGrantDocument.class);
            if (reviewed == null) throw new PolicyGovernanceConflictException("Grant reviewed concurrently");
            grants.audit("grant-review:" + requestId,
                    command.intent().decision() == Decision.APPROVE ? "GRANT_APPROVED" : "GRANT_REJECTED",
                    reviewed, request.requestedBy(), reviewer.id(), null, now);
            return new ReviewResult(requestId, command.intent().decision(),
                    command.intent().decision() == Decision.APPROVE ? reviewed.expiresAt() : null);
        });
    }

    public ReviewResult bootstrapApprove(PolicyGovernanceActor reviewer, String requestId) {
        return transactions.execute(ignored -> {
            var request = pending(reviewer, requestId,
                    new ReviewIntent("first-install", Decision.APPROVE));
            if (request.purpose() != PolicyCredentialEnrollmentGrantDocument.Purpose.FIRST
                    || !credentials.allForActor(reviewer.id()).isEmpty()
                    || !credentials.allForActor(request.targetActorId()).isEmpty())
                throw new AccessDeniedException("Bootstrap is limited to first enrollment");
            Instant now = clock.now();
            var authorization = bootstrap.require(request, reviewer.id(), now);
            mongo.insert(new PolicyCredentialBootstrapClaimDocument(authorization.authorizationId(),
                    requestId, reviewer.id(), now));
            var approved = mongo.findAndModify(Query.query(Criteria.where("_id").is(requestId)
                            .and("approvedAt").is(null).and("rejectedAt").is(null)
                            .and("revokedAt").is(null)
                            .and("expiresAt").gt(now)),
                    new Update().set("approvedBy", reviewer.id()).set("approvedAt", now)
                            .set("expiresAt", now.plusSeconds(GRANT_SECONDS))
                            .set("bootstrapAuthorizationId", authorization.authorizationId()),
                    FindAndModifyOptions.options().returnNew(true),
                    PolicyCredentialEnrollmentGrantDocument.class);
            if (approved == null) throw new PolicyGovernanceConflictException("Grant reviewed concurrently");
            grants.audit("grant-review:" + requestId, "GRANT_BOOTSTRAP_APPROVED",
                    approved, request.requestedBy(), reviewer.id(), null, now);
            return new ReviewResult(requestId, Decision.APPROVE, approved.expiresAt());
        });
    }

    public ReviewEvidence verifyHistorical(PolicyGovernanceActor auditor, String requestId) {
        if (auditor == null || !auditor.authorities().contains(ModelPolicyAuthority.AUDIT_READ))
            throw new AccessDeniedException("Credential grant audit mandate required");
        var reviewed = mongo.findById(requestId, PolicyCredentialEnrollmentGrantDocument.class);
        if (reviewed == null || reviewed.reviewProofId() == null
                || reviewed.reviewOperationId() == null || reviewed.approvedBy() == null
                || reviewed.plannedGrantExpiresAt() == null)
            throw new SecurityException("Signed credential grant review unavailable");
        Decision decision = reviewed.approvedAt() != null ? Decision.APPROVE
                : reviewed.rejectedAt() != null ? Decision.REJECT : null;
        if (decision == null) throw new SecurityException("Credential grant decision missing");
        var plan = mongo.findById(reviewed.reviewProofId(),
                PolicyCredentialGrantReviewPlanDocument.class);
        if (plan == null || !reviewed.id().equals(plan.requestId())
                || !reviewed.approvedBy().equals(plan.reviewerId())
                || decision != plan.decision()
                || !reviewed.plannedGrantExpiresAt().equals(plan.grantExpiresAt())
                || decision == Decision.APPROVE
                    && !reviewed.expiresAt().equals(reviewed.plannedGrantExpiresAt()))
            throw new SecurityException("Credential grant review plan differs");
        var proof = mongo.findById(reviewed.reviewProofId(), GovernanceBoundWebAuthnProof.class);
        var audit = mongo.findById("grant-review:" + requestId,
                PolicyCredentialSecurityEventDocument.class);
        if (proof == null || audit == null || !reviewed.approvedBy().equals(proof.actorId())
                || !NAMESPACE.equals(proof.namespace())
                || !reviewed.reviewCredentialId().equals(proof.credentialId())
                || !reviewed.reviewAssertionSha256().equals(proof.assertionSha256())
                || !reviewed.approvedBy().equals(audit.reviewerActorId())
                || !reviewed.targetActorId().equals(audit.targetActorId())
                || !reviewed.caseReference().equals(audit.approvalReference())
                || !(decision == Decision.APPROVE ? reviewed.approvedAt() : reviewed.rejectedAt())
                        .equals(audit.occurredAt())
                || !(decision == Decision.APPROVE ? "GRANT_APPROVED" : "GRANT_REJECTED")
                        .equals(audit.action())
                || !requestId.equals(reviewed.id()))
            throw new SecurityException("Credential grant review proof or audit differs");
        verifier.verifyHistorical(proof, contract(reviewed, reviewed.approvedBy(),
                new ReviewIntent(reviewed.reviewOperationId(), decision),
                reviewed.plannedGrantExpiresAt()));
        return new ReviewEvidence(requestId, decision, reviewed.reviewProofId(),
                reviewed.reviewCredentialId(), reviewed.reviewAssertionSha256(), proof.acceptedAt());
    }

    private PolicyCredentialEnrollmentGrantDocument pending(PolicyGovernanceActor reviewer,
            String requestId, ReviewIntent intent) {
        if (reviewer == null || !reviewer.authorities().contains(ModelPolicyAuthority.CREDENTIAL_ADMIN)
                || reviewer.issuer() == null || reviewer.subject() == null
                || !reviewer.id().equals(PolicyGovernanceActor.identityId(reviewer.issuer(), reviewer.subject())))
            throw new AccessDeniedException("Dedicated credential administration mandate required");
        if (requestId == null || !requestId.matches("^[A-Za-z0-9._:-]{1,128}$")
                || intent == null || intent.operationId() == null
                || !intent.operationId().matches("^[A-Za-z0-9._:-]{1,128}$")
                || intent.decision() == null)
            throw new IllegalArgumentException("Exact credential grant review required");
        var request = mongo.findById(requestId, PolicyCredentialEnrollmentGrantDocument.class);
        if (request == null || request.approvedAt() != null || request.rejectedAt() != null
                || request.revokedAt() != null
                || !clock.now().isBefore(request.expiresAt()))
            throw new PolicyGovernanceConflictException("Credential grant request unavailable or expired");
        if (reviewer.id().equals(request.requestedBy())
                || reviewer.id().equals(request.targetActorId())
                || !reviewer.issuer().equals(request.targetIssuer()))
            throw new AccessDeniedException("Independent credential administrator required");
        return request;
    }

    static Map<String, Object> contract(PolicyCredentialEnrollmentGrantDocument request,
            String reviewerId, ReviewIntent intent, Instant grantExpiresAt) {
        Map<String, Object> contract = new LinkedHashMap<>();
        contract.put("domain", DOMAIN);
        contract.put("requestId", request.id());
        contract.put("purpose", request.purpose().name());
        contract.put("targetIssuer", request.targetIssuer());
        contract.put("targetSubject", request.targetSubject());
        contract.put("requestedBy", request.requestedBy());
        contract.put("reviewedBy", reviewerId);
        contract.put("caseReference", request.caseReference());
        contract.put("requestExpiresAt", request.requestExpiresAt().toString());
        contract.put("grantExpiresAt", grantExpiresAt.toString());
        contract.put("operationId", intent.operationId());
        contract.put("decision", intent.decision().name());
        return contract;
    }

    public enum Decision { APPROVE, REJECT }
    public record ReviewIntent(String operationId, Decision decision) { }
    public record FinishReview(ReviewIntent intent, String challengeId, String responseJson) { }
    public record ReviewResult(String requestId, Decision decision, Instant expiresAt) { }
    public record ReviewEvidence(String requestId, Decision decision, String proofId,
            String credentialId, String assertionSha256, Instant acceptedAt) { }
}
