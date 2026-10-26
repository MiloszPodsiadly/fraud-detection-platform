package com.frauddetection.alert.governance.modelpolicy;

import com.frauddetection.alert.governance.audit.GovernanceEventCommitment;

import com.frauddetection.alert.security.authorization.ModelPolicyAuthority;
import jakarta.annotation.PostConstruct;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;
import java.util.List;
import java.util.ArrayList;

/** Target-bound, single-use credential grant ledger. Approval is handled by signed review or protected bootstrap. */
@Service
public class PolicyCredentialEnrollmentGrantService {
    private static final int REQUEST_SECONDS = 3600;
    private final MongoTemplate mongo;
    private final TransactionTemplate transaction;
    private final MongoPolicyGovernanceStore clock;
    private final PolicyCredentialGrantAuthorizationVerifier authorization;
    private final SecureRandom random = new SecureRandom();

    public PolicyCredentialEnrollmentGrantService(MongoTemplate mongo, MongoTransactionManager manager,
            MongoPolicyGovernanceStore clock, PolicyCredentialGrantAuthorizationVerifier authorization) {
        this.mongo = mongo;
        this.transaction = new TransactionTemplate(manager);
        this.clock = clock;
        this.authorization = authorization;
    }

    @PostConstruct
    void ensureIndexes() {
        mongo.indexOps(PolicyCredentialEnrollmentGrantDocument.class).ensureIndex(new Index()
                .on("targetActorId", Sort.Direction.ASC).on("expiresAt", Sort.Direction.ASC)
                .named("policy_credential_grant_target_expiry_v1"));
        mongo.indexOps(PolicyCredentialEnrollmentGrantDocument.class).ensureIndex(new Index()
                .on("requestedBy", Sort.Direction.ASC).on("_id", Sort.Direction.ASC)
                .named("policy_credential_grant_requester_incident_v1"));
        mongo.indexOps(PolicyCredentialEnrollmentGrantDocument.class).ensureIndex(new Index()
                .on("approvedBy", Sort.Direction.ASC).on("_id", Sort.Direction.ASC)
                .named("policy_credential_grant_approver_incident_v1"));
    }

    @Scheduled(cron = "0 23 3 * * *")
    void clearExpiredGrantDigests() {
        mongo.updateMulti(Query.query(Criteria.where("expiresAt").lt(clock.now())
                        .and("secretSha256").ne(null)),
                new Update().unset("secretSha256"), PolicyCredentialEnrollmentGrantDocument.class);
    }

    public GrantRequestSummary request(PolicyGovernanceActor operator, GrantRequest request) {
        requireAdmin(operator);
        if (request == null || request.operationId() == null
                || !request.operationId().matches("^[A-Za-z0-9._:-]{1,128}$")
                || request.purpose() == null || request.caseReference() == null
                || request.caseReference().isBlank() || request.caseReference().length() > 128) {
            throw new IllegalArgumentException("Complete enrollment approval request required");
        }
        String targetId = PolicyGovernanceActor.identityId(request.targetIssuer(), request.targetSubject());
        if (!operator.issuer().equals(request.targetIssuer()))
            throw new AccessDeniedException("Credential grant target must use the institutional issuer");
        if (operator.id().equals(targetId)) throw new AccessDeniedException("Operator cannot grant own enrollment");
        Instant now = clock.now();
        var document = new PolicyCredentialEnrollmentGrantDocument(request.operationId(), targetId,
                request.targetIssuer(), request.targetSubject(), request.purpose(), request.caseReference().trim(),
                operator.id(), now, null, null, null, now.plusSeconds(REQUEST_SECONDS), null, null, null,
                null, null, null, null, null, null, null, now.plusSeconds(REQUEST_SECONDS), null);
        try {
            return transaction.execute(ignored -> {
                mongo.insert(document);
                audit("grant-request:" + document.id(), "GRANT_REQUESTED", document,
                        operator.id(), null, null, now);
                return new GrantRequestSummary(document.id(), document.purpose(), document.expiresAt());
            });
        } catch (DuplicateKeyException duplicate) {
            var existing = mongo.findById(request.operationId(), PolicyCredentialEnrollmentGrantDocument.class);
            if (existing == null || !Objects.equals(existing.requestedBy(), operator.id())
                    || !Objects.equals(existing.targetActorId(), targetId)
                    || existing.purpose() != request.purpose()
                    || !Objects.equals(existing.caseReference(), request.caseReference().trim())) {
                throw new PolicyGovernanceConflictException("Grant operation ID already used");
            }
            return new GrantRequestSummary(existing.id(), existing.purpose(), existing.expiresAt());
        }
    }

    public IssuedGrant claim(PolicyGovernanceActor target, String requestId) {
        requireTarget(target);
        if (requestId == null || !requestId.matches("^[A-Za-z0-9._:-]{1,128}$"))
            throw new AccessDeniedException("Approved enrollment grant unavailable");
        byte[] secret = new byte[32];
        random.nextBytes(secret);
        String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        String token = requestId + "." + encoded;
        String digest = sha256(token);
        return transaction.execute(ignored -> {
            Instant now = clock.now();
            var current = mongo.findById(requestId, PolicyCredentialEnrollmentGrantDocument.class);
            if (current == null || current.approvedAt() == null || current.rejectedAt() != null
                    || current.claimedAt() != null || current.consumedAt() != null
                    || current.revokedAt() != null
                    || !now.isBefore(current.expiresAt()) || !target.id().equals(current.targetActorId())
                    || !target.issuer().equals(current.targetIssuer())
                    || !target.subject().equals(current.targetSubject())) {
                throw new AccessDeniedException("Approved enrollment grant unavailable for this identity");
            }
            authorization.verify(current, now);
            var query = Query.query(Criteria.where("_id").is(requestId).and("approvedAt").ne(null)
                    .and("rejectedAt").is(null).and("claimedAt").is(null)
                    .and("consumedAt").is(null).and("revokedAt").is(null).and("expiresAt").gt(now)
                    .and("targetActorId").is(target.id()).and("targetIssuer").is(target.issuer())
                    .and("targetSubject").is(target.subject()));
            var claimed = mongo.findAndModify(query, new Update().set("claimedAt", now)
                    .set("secretSha256", digest),
                    FindAndModifyOptions.options().returnNew(true), PolicyCredentialEnrollmentGrantDocument.class);
            if (claimed == null) throw new PolicyGovernanceConflictException("Grant already claimed");
            audit("grant-claim:" + requestId, "GRANT_CLAIMED", claimed,
                    target.id(), claimed.approvedBy(), null, now);
            return new IssuedGrant(requestId, token, claimed.expiresAt());
        });
    }

    public List<GrantView> mine(PolicyGovernanceActor target) {
        requireTarget(target);
        Instant now = clock.now();
        return mongo.find(Query.query(Criteria.where("targetActorId").is(target.id())
                        .and("targetIssuer").is(target.issuer()).and("targetSubject").is(target.subject())
                        .and("approvedAt").ne(null).and("rejectedAt").is(null)
                        .and("consumedAt").is(null).and("revokedAt").is(null).and("expiresAt").gt(now))
                        .with(Sort.by(Sort.Direction.DESC, "approvedAt")).limit(20),
                PolicyCredentialEnrollmentGrantDocument.class).stream()
                .map(grant -> new GrantView(grant.id(), grant.purpose(), grant.caseReference(),
                        grant.expiresAt(), grant.claimedAt() != null)).toList();
    }

    public GrantReviewView reviewView(PolicyGovernanceActor reviewer, String requestId) {
        requireAdmin(reviewer);
        var grant = requestId == null ? null : mongo.findById(requestId,
                PolicyCredentialEnrollmentGrantDocument.class);
        if (grant == null || reviewer.id().equals(grant.requestedBy())
                || reviewer.id().equals(grant.targetActorId()))
            throw new AccessDeniedException("Independent credential grant review unavailable");
        return new GrantReviewView(grant.id(), grant.purpose(), grant.targetIssuer(),
                grant.targetSubject(), grant.requestedBy(), grant.caseReference(),
                grant.expiresAt(), grant.approvedAt() != null, grant.rejectedAt() != null,
                grant.reviewOperationId(), grant.approvedBy());
    }

    /** Status is derived from durable facts, with committed consumption taking precedence. */
    public GrantStatus status(PolicyGovernanceActor operator, String requestId) {
        requireStatusReader(operator);
        var grant = grantForOperator(operator, requestId);
        return statusOf(grant);
    }

    public GrantStatus revoke(PolicyGovernanceActor operator, String requestId, RevocationCommand command) {
        requireAdmin(operator);
        if (command == null || command.kind() == null || !validId(command.operationId())
                || !validText(command.reason()) || !validText(command.caseReference())
                || command.targetIssuer() == null || command.targetSubject() == null)
            throw new IllegalArgumentException("Exact credential grant revocation intent required");
        if (command.kind() == PolicyCredentialEnrollmentGrantDocument.RevocationKind.SECURITY_INCIDENT)
            requireIncident(operator);
        if (!operator.issuer().equals(command.targetIssuer()))
            throw new AccessDeniedException("Credential grant issuer outside institutional scope");
        var existing = grantForOperator(operator, requestId);
        if (!existing.targetIssuer().equals(command.targetIssuer())
                || !existing.targetSubject().equals(command.targetSubject()))
            throw new AccessDeniedException("Credential grant target differs");
        try {
            return transaction.execute(ignored -> {
                Instant now = clock.now();
                var current = grantForOperator(operator, requestId);
                if (current.revokedAt() != null) {
                    if (sameRevocation(current, operator, command)) return statusOf(current);
                    throw new PolicyGovernanceConflictException("Grant revoked by another operation");
                }
                if (current.consumedAt() != null || current.rejectedAt() != null
                        || !now.isBefore(current.expiresAt()))
                    throw new PolicyGovernanceConflictException("Terminal grant cannot be revoked");
                var query = Query.query(Criteria.where("_id").is(requestId)
                        .and("targetIssuer").is(command.targetIssuer())
                        .and("targetSubject").is(command.targetSubject())
                        .and("consumedAt").is(null).and("rejectedAt").is(null)
                        .and("revokedAt").is(null)
                        .and("expiresAt").gt(now));
                var revoked = mongo.findAndModify(query, new Update().set("revokedAt", now)
                                .set("revokedBy", operator.id()).set("revokedByIssuer", operator.issuer())
                                .set("revokedBySubject", operator.subject())
                                .set("revocationOperationId", command.operationId())
                                .set("revocationReason", command.reason().trim())
                                .set("revocationCaseReference", command.caseReference().trim())
                                .set("revocationKind", command.kind()).unset("secretSha256"),
                        FindAndModifyOptions.options().returnNew(true),
                        PolicyCredentialEnrollmentGrantDocument.class);
                if (revoked == null) throw new PolicyGovernanceConflictException("Grant state changed concurrently");
                var event = new PolicyCredentialSecurityEventDocument("grant-revoke:" + command.operationId(),
                        "GRANT_REVOKED", revoked.targetActorId(), revoked.targetIssuer(),
                        revoked.targetSubject(), operator.id(), revoked.approvedBy(),
                        revoked.caseReference(), null, now, revoked.id(), command.operationId(),
                        command.reason().trim(), command.caseReference().trim(),
                        approvalReference(revoked), command.kind());
                mongo.insert(event);
                GovernanceEventCommitment.record(mongo,
                        GovernanceEventCommitment.Kind.CREDENTIAL_GRANT_REVOCATION,
                        event.id(), event, "CURRENT_INSTITUTION", operator.id(), revoked.id(),
                        command.operationId(), revoked.reviewAssertionSha256(), revoked.id(), now);
                return statusOf(revoked);
            });
        } catch (RuntimeException failure) {
            // Reconcile an unknown commit result only against the exact durable intent and receipt.
            var durable = mongo.findById(requestId, PolicyCredentialEnrollmentGrantDocument.class);
            if (durable != null && sameRevocation(durable, operator, command))
                return statusOf(durable);
            var usedOperation = mongo.findById("grant-revoke:" + command.operationId(),
                    PolicyCredentialSecurityEventDocument.class);
            if (usedOperation != null) throw new PolicyGovernanceConflictException("Revocation operation ID already used");
            throw failure;
        }
    }

    /** A bounded cursor page; each result still needs its own audited revocation operation. */
    public IncidentGrantPage incidentGrants(PolicyGovernanceActor operator, String actorId, String afterId) {
        requireAdmin(operator);
        requireIncident(operator);
        if (actorId == null || !actorId.matches("^oidc:[0-9a-f]{64}$")
                || afterId != null && !validId(afterId))
            throw new IllegalArgumentException("Exact compromised actor and valid cursor required");
        Instant now = clock.now();
        var query = Query.query(new Criteria().andOperator(
                Criteria.where("targetIssuer").is(operator.issuer()),
                Criteria.where("consumedAt").is(null), Criteria.where("revokedAt").is(null),
                Criteria.where("rejectedAt").is(null), Criteria.where("expiresAt").gt(now),
                new Criteria().orOperator(Criteria.where("requestedBy").is(actorId),
                        Criteria.where("approvedBy").is(actorId), Criteria.where("targetActorId").is(actorId))));
        if (afterId != null) query.addCriteria(Criteria.where("_id").gt(afterId));
        var found = mongo.find(query.with(Sort.by(Sort.Direction.ASC, "_id")).limit(21),
                PolicyCredentialEnrollmentGrantDocument.class);
        var page = found.stream().limit(20).map(this::statusOf).toList();
        return new IncidentGrantPage(page, found.size() > 20 ? page.get(page.size() - 1).requestId() : null);
    }

    private PolicyCredentialEnrollmentGrantDocument grantForOperator(PolicyGovernanceActor operator, String requestId) {
        if (!validId(requestId)) throw new IllegalArgumentException("Valid grant ID required");
        var grant = mongo.findById(requestId, PolicyCredentialEnrollmentGrantDocument.class);
        if (grant == null || !operator.issuer().equals(grant.targetIssuer()))
            throw new AccessDeniedException("Credential grant unavailable in institutional scope");
        return grant;
    }

    private GrantStatus statusOf(PolicyCredentialEnrollmentGrantDocument grant) {
        Instant now = clock.now();
        GrantState state = grant.consumedAt() != null ? GrantState.CONSUMED
                : grant.revokedAt() != null ? GrantState.REVOKED
                : grant.rejectedAt() != null ? GrantState.REJECTED
                : !now.isBefore(grant.expiresAt()) ? GrantState.EXPIRED
                : grant.claimedAt() != null ? GrantState.CLAIMED
                : grant.approvedAt() != null ? GrantState.APPROVED : GrantState.REQUESTED;
        var eventIds = new ArrayList<>(List.of("grant-request:" + grant.id(), "grant-review:" + grant.id(),
                "grant-claim:" + grant.id(), "credential-enrollment:" + grant.id(),
                "credential-recovery-revoke:" + grant.id()));
        if (grant.revocationOperationId() != null)
            eventIds.add("grant-revoke:" + grant.revocationOperationId());
        var history = mongo.find(Query.query(Criteria.where("_id").in(eventIds))
                        .with(Sort.by(Sort.Direction.ASC, "occurredAt")).limit(6),
                PolicyCredentialSecurityEventDocument.class).stream()
                .map(event -> new GrantEvent(event.action(), event.actorId(), event.reviewerActorId(),
                        event.occurredAt(), event.operationId(), event.reason(), event.caseReference()))
                .toList();
        return new GrantStatus(grant.id(), state, grant.purpose(), grant.targetIssuer(),
                grant.targetSubject(), grant.requestedBy(), grant.approvedBy(), grant.caseReference(),
                approvalReference(grant), grant.expiresAt(), grant.consumedAt(), grant.credentialId(),
                grant.revokedAt(), grant.revokedBy(), grant.revocationReason(),
                grant.revocationCaseReference(), grant.revocationOperationId(),
                grant.revocationKind(), history);
    }

    private boolean sameRevocation(PolicyCredentialEnrollmentGrantDocument grant,
            PolicyGovernanceActor operator, RevocationCommand command) {
        if (grant.revokedAt() == null || !Objects.equals(grant.revocationOperationId(), command.operationId())
                || !Objects.equals(grant.revokedBy(), operator.id())
                || !Objects.equals(grant.revokedByIssuer(), operator.issuer())
                || !Objects.equals(grant.revokedBySubject(), operator.subject())
                || !Objects.equals(grant.targetIssuer(), command.targetIssuer())
                || !Objects.equals(grant.targetSubject(), command.targetSubject())
                || !Objects.equals(grant.revocationReason(), command.reason().trim())
                || !Objects.equals(grant.revocationCaseReference(), command.caseReference().trim())
                || grant.revocationKind() != command.kind()) return false;
        var receipt = mongo.findById("grant-revoke:" + command.operationId(),
                PolicyCredentialSecurityEventDocument.class);
        return receipt != null && "GRANT_REVOKED".equals(receipt.action())
                && Objects.equals(receipt.grantId(), grant.id())
                && Objects.equals(receipt.actorId(), operator.id())
                && Objects.equals(receipt.targetIssuer(), grant.targetIssuer())
                && Objects.equals(receipt.targetSubject(), grant.targetSubject())
                && Objects.equals(receipt.originalApprovalReference(), approvalReference(grant))
                && Objects.equals(receipt.occurredAt(), grant.revokedAt());
    }

    private String approvalReference(PolicyCredentialEnrollmentGrantDocument grant) {
        return grant.reviewProofId() != null ? grant.reviewProofId() : grant.bootstrapAuthorizationId();
    }

    private void requireIncident(PolicyGovernanceActor actor) {
        if (!actor.authorities().contains(ModelPolicyAuthority.CREDENTIAL_INCIDENT_REVOKE))
            throw new AccessDeniedException("Credential incident revocation mandate required");
    }

    private boolean validId(String value) {
        return value != null && value.matches("^[A-Za-z0-9._:-]{1,128}$");
    }

    private boolean validText(String value) {
        return value != null && !value.isBlank() && value.length() <= 256;
    }

    public PolicyCredentialEnrollmentGrantDocument validate(PolicyGovernanceActor actor, String token,
            PolicyCredentialEnrollmentGrantDocument.Purpose expectedPurpose) {
        if (token == null || !token.matches("^[A-Za-z0-9._:-]{1,128}\\.[A-Za-z0-9_-]{43}$")) {
            throw new AccessDeniedException("Enrollment grant required");
        }
        String id = token.substring(0, token.lastIndexOf('.'));
        var grant = mongo.findById(id, PolicyCredentialEnrollmentGrantDocument.class);
        if (grant == null || grant.approvedAt() == null || grant.claimedAt() == null
                || grant.rejectedAt() != null || grant.consumedAt() != null || grant.revokedAt() != null
                || !clock.now().isBefore(grant.expiresAt()) || grant.purpose() != expectedPurpose
                || !actor.id().equals(grant.targetActorId()) || !actor.issuer().equals(grant.targetIssuer())
                || !actor.subject().equals(grant.targetSubject()) || grant.secretSha256() == null
                || !MessageDigest.isEqual(grant.secretSha256().getBytes(StandardCharsets.US_ASCII),
                    sha256(token).getBytes(StandardCharsets.US_ASCII))) {
            throw new AccessDeniedException("Enrollment grant unavailable for this identity and purpose");
        }
        return grant;
    }

    public void consume(PolicyCredentialEnrollmentGrantDocument grant, String credentialId,
            String responseSha256, Instant now) {
        consume(grant, credentialId, responseSha256, now, "CREDENTIAL_ENROLLED", "credential-enrollment:");
    }

    public void consumeRevocation(PolicyCredentialEnrollmentGrantDocument grant, String credentialId,
            Instant now) {
        consume(grant, credentialId, null, now, "CREDENTIAL_PROTECTED_REVOCATION", "credential-recovery-revoke:");
    }

    private void consume(PolicyCredentialEnrollmentGrantDocument grant, String credentialId,
            String responseSha256, Instant now, String action, String eventPrefix) {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Enrollment grant consumption requires a Mongo transaction");
        var query = Query.query(Criteria.where("_id").is(grant.id()).and("consumedAt").is(null)
                .and("approvedAt").ne(null).and("rejectedAt").is(null).and("revokedAt").is(null)
                .and("claimedAt").ne(null).and("secretSha256").is(grant.secretSha256())
                .and("expiresAt").gt(now));
        var updated = mongo.findAndModify(query, new Update().set("consumedAt", now)
                .unset("secretSha256")
                .set("credentialId", credentialId).set("registrationResponseSha256", responseSha256),
                FindAndModifyOptions.options().returnNew(true), PolicyCredentialEnrollmentGrantDocument.class);
        if (updated == null) throw new PolicyGovernanceConflictException("Enrollment grant already used");
        audit(eventPrefix + grant.id(), action, updated,
                grant.targetActorId(), grant.approvedBy(), credentialId, now);
    }

    void audit(String id, String action, PolicyCredentialEnrollmentGrantDocument grant,
            String actorId, String reviewerId, String credentialId, Instant now) {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Credential grant audit requires a Mongo transaction");
        var event = new PolicyCredentialSecurityEventDocument(id, action, grant.targetActorId(),
                grant.targetIssuer(), grant.targetSubject(), actorId, reviewerId,
                grant.caseReference(), credentialId, now);
        mongo.insert(event);
        if ("GRANT_APPROVED".equals(action) || "GRANT_BOOTSTRAP_APPROVED".equals(action)) {
            GovernanceEventCommitment.record(mongo, GovernanceEventCommitment.Kind.CREDENTIAL_GRANT,
                    event.id(), event, "CURRENT_INSTITUTION", actorId, grant.id(),
                    grant.reviewOperationId() == null ? event.id() : grant.reviewOperationId(),
                    grant.reviewAssertionSha256(), grant.id(), now);
        }
    }

    private void requireAdmin(PolicyGovernanceActor actor) {
        if (actor == null || !actor.authorities().contains(ModelPolicyAuthority.CREDENTIAL_ADMIN)
                || actor.issuer() == null || actor.subject() == null
                || !actor.id().equals(PolicyGovernanceActor.identityId(actor.issuer(), actor.subject()))) {
            throw new AccessDeniedException("Dedicated credential administration mandate required");
        }
    }

    private void requireStatusReader(PolicyGovernanceActor actor) {
        if (actor == null || actor.issuer() == null || actor.subject() == null
                || !actor.id().equals(PolicyGovernanceActor.identityId(actor.issuer(), actor.subject()))
                || !(actor.authorities().contains(ModelPolicyAuthority.CREDENTIAL_ADMIN)
                    || actor.authorities().contains(ModelPolicyAuthority.AUDIT_READ)))
            throw new AccessDeniedException("Credential administration or audit mandate required");
    }

    private void requireTarget(PolicyGovernanceActor actor) {
        if (actor == null || !actor.authorities().contains(ModelPolicyAuthority.CREDENTIAL_ENROLL)
                || actor.issuer() == null || actor.subject() == null
                || !actor.id().equals(PolicyGovernanceActor.identityId(actor.issuer(), actor.subject())))
            throw new AccessDeniedException("Institutional credential enrollment identity required");
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    public record GrantRequest(String operationId, String targetIssuer, String targetSubject,
            PolicyCredentialEnrollmentGrantDocument.Purpose purpose, String caseReference) { }
    public record GrantRequestSummary(String requestId,
            PolicyCredentialEnrollmentGrantDocument.Purpose purpose, Instant expiresAt) { }
    public record IssuedGrant(String requestId, String token, Instant expiresAt) { }
    public record GrantView(String requestId, PolicyCredentialEnrollmentGrantDocument.Purpose purpose,
            String caseReference, Instant expiresAt, boolean alreadyClaimed) { }
    public record GrantReviewView(String requestId, PolicyCredentialEnrollmentGrantDocument.Purpose purpose,
            String targetIssuer, String targetSubject, String requestedBy, String caseReference,
            Instant expiresAt, boolean approved, boolean rejected,
            String reviewOperationId, String reviewedBy) { }
    public enum GrantState { REQUESTED, APPROVED, CLAIMED, CONSUMED, REJECTED, EXPIRED, REVOKED }
    public record RevocationCommand(String operationId, String targetIssuer, String targetSubject,
            String reason, String caseReference,
            PolicyCredentialEnrollmentGrantDocument.RevocationKind kind) { }
    public record GrantEvent(String action, String actorId, String reviewerActorId, Instant occurredAt,
            String operationId, String reason, String caseReference) { }
    public record GrantStatus(String requestId, GrantState state,
            PolicyCredentialEnrollmentGrantDocument.Purpose purpose,
            String targetIssuer, String targetSubject, String requestedBy, String approvedBy,
            String caseReference, String approvalReference, Instant expiresAt, Instant consumedAt,
            String credentialId, Instant revokedAt, String revokedBy, String revocationReason,
            String revocationCaseReference, String revocationOperationId,
            PolicyCredentialEnrollmentGrantDocument.RevocationKind revocationKind,
            List<GrantEvent> history) { }
    public record IncidentGrantPage(List<GrantStatus> grants, String nextAfterId) { }
}
