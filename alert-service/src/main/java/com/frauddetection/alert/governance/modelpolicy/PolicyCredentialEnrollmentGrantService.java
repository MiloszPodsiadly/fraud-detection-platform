package com.frauddetection.alert.governance.modelpolicy;

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
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;

/** Two distinct, explicitly mandated operators approve an opaque, short-lived enrollment grant. */
@Service
public class PolicyCredentialEnrollmentGrantService {
    private static final int REQUEST_SECONDS = 3600;
    private static final int GRANT_SECONDS = 600;
    private final MongoTemplate mongo;
    private final TransactionTemplate transaction;
    private final MongoPolicyGovernanceStore clock;
    private final SecureRandom random = new SecureRandom();

    public PolicyCredentialEnrollmentGrantService(MongoTemplate mongo, MongoTransactionManager manager,
            MongoPolicyGovernanceStore clock) {
        this.mongo = mongo;
        this.transaction = new TransactionTemplate(manager);
        this.clock = clock;
    }

    @PostConstruct
    void ensureIndexes() {
        mongo.indexOps(PolicyCredentialEnrollmentGrantDocument.class).ensureIndex(new Index()
                .on("targetActorId", Sort.Direction.ASC).on("expiresAt", Sort.Direction.ASC)
                .named("policy_credential_grant_target_expiry_v1"));
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
        if (operator.id().equals(targetId)) throw new AccessDeniedException("Operator cannot grant own enrollment");
        Instant now = clock.now();
        var document = new PolicyCredentialEnrollmentGrantDocument(request.operationId(), targetId,
                request.targetIssuer(), request.targetSubject(), request.purpose(), request.caseReference().trim(),
                operator.id(), now, null, null, null, now.plusSeconds(REQUEST_SECONDS), null, null, null);
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

    public IssuedGrant approve(PolicyGovernanceActor reviewer, String requestId) {
        requireAdmin(reviewer);
        if (requestId == null || !requestId.matches("^[A-Za-z0-9._:-]{1,128}$")) {
            throw new IllegalArgumentException("Valid grant request ID required");
        }
        Instant now = clock.now();
        byte[] secret = new byte[32];
        random.nextBytes(secret);
        String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        String token = requestId + "." + encoded;
        String digest = sha256(token);
        return transaction.execute(ignored -> {
            var current = mongo.findById(requestId, PolicyCredentialEnrollmentGrantDocument.class);
            if (current == null || current.approvedAt() != null || !now.isBefore(current.expiresAt())) {
                throw new PolicyGovernanceConflictException("Grant request unavailable or expired");
            }
            if (reviewer.id().equals(current.requestedBy()) || reviewer.id().equals(current.targetActorId())) {
                throw new AccessDeniedException("Independent operator approval required");
            }
            var query = Query.query(Criteria.where("_id").is(requestId).and("approvedAt").is(null)
                    .and("expiresAt").gt(now));
            var approved = mongo.findAndModify(query, new Update().set("approvedBy", reviewer.id())
                    .set("approvedAt", now).set("secretSha256", digest)
                    .set("expiresAt", now.plusSeconds(GRANT_SECONDS)),
                    FindAndModifyOptions.options().returnNew(true), PolicyCredentialEnrollmentGrantDocument.class);
            if (approved == null) throw new PolicyGovernanceConflictException("Grant already approved");
            audit("grant-approval:" + requestId, "GRANT_APPROVED", approved,
                    current.requestedBy(), reviewer.id(), null, now);
            return new IssuedGrant(requestId, token, approved.expiresAt());
        });
    }

    public PolicyCredentialEnrollmentGrantDocument validate(PolicyGovernanceActor actor, String token,
            PolicyCredentialEnrollmentGrantDocument.Purpose expectedPurpose) {
        if (token == null || !token.matches("^[A-Za-z0-9._:-]{1,128}\\.[A-Za-z0-9_-]{43}$")) {
            throw new AccessDeniedException("Enrollment grant required");
        }
        String id = token.substring(0, token.lastIndexOf('.'));
        var grant = mongo.findById(id, PolicyCredentialEnrollmentGrantDocument.class);
        if (grant == null || grant.approvedAt() == null || grant.consumedAt() != null
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
        var query = Query.query(Criteria.where("_id").is(grant.id()).and("consumedAt").is(null)
                .and("secretSha256").is(grant.secretSha256()).and("expiresAt").gt(now));
        var updated = mongo.findAndModify(query, new Update().set("consumedAt", now)
                .set("credentialId", credentialId).set("registrationResponseSha256", responseSha256),
                FindAndModifyOptions.options().returnNew(true), PolicyCredentialEnrollmentGrantDocument.class);
        if (updated == null) throw new PolicyGovernanceConflictException("Enrollment grant already used");
        audit(eventPrefix + grant.id(), action, updated,
                grant.targetActorId(), grant.approvedBy(), credentialId, now);
    }

    public void audit(String id, String action, PolicyCredentialEnrollmentGrantDocument grant,
            String actorId, String reviewerId, String credentialId, Instant now) {
        mongo.insert(new PolicyCredentialSecurityEventDocument(id, action, grant.targetActorId(),
                grant.targetIssuer(), grant.targetSubject(), actorId, reviewerId,
                grant.caseReference(), credentialId, now));
    }

    private void requireAdmin(PolicyGovernanceActor actor) {
        if (actor == null || !actor.authorities().contains(ModelPolicyAuthority.CREDENTIAL_ADMIN)
                || actor.issuer() == null || actor.subject() == null
                || !actor.id().equals(PolicyGovernanceActor.identityId(actor.issuer(), actor.subject()))) {
            throw new AccessDeniedException("Dedicated credential administration mandate required");
        }
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
}
