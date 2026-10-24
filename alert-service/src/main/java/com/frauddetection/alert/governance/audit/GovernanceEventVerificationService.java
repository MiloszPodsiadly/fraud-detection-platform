package com.frauddetection.alert.governance.audit;

import com.frauddetection.alert.audit.AuditAction;
import com.frauddetection.alert.audit.AuditAnchorRepository;
import com.frauddetection.alert.audit.AuditEventDocument;
import com.frauddetection.alert.audit.AuditEventHasher;
import com.frauddetection.alert.audit.AuditEventRepository;
import com.frauddetection.alert.audit.AuditResourceType;
import com.frauddetection.alert.audit.external.GovernanceExternalAnchorVerifier;
import com.frauddetection.alert.audit.outbox.WriteActionAuditOutboxRecord;
import com.frauddetection.alert.audit.outbox.WriteActionAuditOutboxStatus;
import com.frauddetection.alert.governance.modelpolicy.PolicyCredentialSecurityEventDocument;
import com.frauddetection.alert.governance.modelpolicy.PolicyCredentialGrantReviewService;
import com.frauddetection.alert.governance.modelpolicy.PolicyGovernanceActor;
import com.frauddetection.alert.governance.modelpolicy.PolicyGovernanceHistoryDocument;
import com.frauddetection.alert.governance.modelpolicy.PolicyPublicationReconciliationEventDocument;
import com.frauddetection.alert.governance.modelpolicy.PolicyPublicationRecoveryEventDocument;
import com.frauddetection.alert.governance.modelpromotion.ModelPromotionEmergencyAuditDocument;
import com.frauddetection.alert.governance.modelpromotion.PolicyTrustChangeAuditDocument;
import com.frauddetection.alert.governance.modelpromotion.PolicyTrustChangeService;
import com.frauddetection.alert.security.authorization.ModelPolicyAuthority;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Objects;
import java.util.Set;

/** Historical evidence read. Local chain verification is distinct from independent external anchoring. */
@Service
public class GovernanceEventVerificationService {
    private final MongoTemplate mongo;
    private final AuditEventRepository auditEvents;
    private final AuditAnchorRepository anchors;
    private final PolicyCredentialGrantReviewService grantReviews;
    private final PolicyTrustChangeService trustChanges;
    private final GovernanceExternalAnchorVerifier externalAnchors;

    @Autowired
    public GovernanceEventVerificationService(MongoTemplate mongo, AuditEventRepository auditEvents,
            AuditAnchorRepository anchors, PolicyCredentialGrantReviewService grantReviews,
            PolicyTrustChangeService trustChanges, GovernanceExternalAnchorVerifier externalAnchors) {
        this.mongo = mongo;
        this.auditEvents = auditEvents;
        this.anchors = anchors;
        this.grantReviews = grantReviews;
        this.trustChanges = trustChanges;
        this.externalAnchors = externalAnchors;
    }

    public GovernanceEventVerificationService(MongoTemplate mongo, AuditEventRepository auditEvents,
            AuditAnchorRepository anchors) {
        this(mongo, auditEvents, anchors, null, null, null);
    }

    public Verification verify(PolicyGovernanceActor actor, GovernanceEventCommitment.Kind kind,
            String eventId) {
        if (actor == null || !actor.authorities().contains(ModelPolicyAuthority.AUDIT_READ)
                || !actor.authorities().contains(ModelPolicyAuthority.PRODUCTION)) {
            throw new AccessDeniedException("Production governance audit mandate required");
        }
        if (kind == null || eventId == null || !eventId.matches("^[A-Za-z0-9._:-]{1,160}$")) {
            throw new IllegalArgumentException("Valid governance audit event required");
        }
        String outboxId = GovernanceEventCommitment.idFor(kind, eventId);
        var obligation = mongo.findById(outboxId, WriteActionAuditOutboxRecord.class);
        Object event = event(kind, eventId);
        if (obligation == null || event == null) {
            return new Verification(kind.name(), eventId, "MISSING_EVIDENCE", null, null,
                    null, null, "NOT_VERIFIED", obligation == null ? "AUDIT_OBLIGATION_MISSING" : "BUSINESS_EVENT_MISSING");
        }
        if (!Objects.equals(obligation.getOutboxId(), outboxId)
                || !Objects.equals(obligation.getIdempotencyKey(), outboxId)
                || !GovernanceEventCommitment.VERSION.equals(obligation.getContractVersion())
                || obligation.getAction() != AuditAction.COMMIT_GOVERNANCE_EVENT
                || obligation.getResourceType() != AuditResourceType.MODEL_GOVERNANCE_EVENT
                || !kind.name().equals(obligation.getGovernanceEventKind())
                || !eventId.equals(obligation.getGovernanceEventId())
                || obligation.getGovernanceOccurredAt() == null) {
            return failure(kind, eventId, "OBLIGATION_METADATA_MISMATCH");
        }
        String hash = GovernanceEventCommitment.hash(kind, eventId, event,
                obligation.getGovernanceScope(), obligation.getActor(),
                obligation.getGovernanceObjectId(), obligation.getGovernanceOperationId(),
                obligation.getGovernanceProofDigest(), obligation.getGovernanceResultId(),
                obligation.getGovernanceOccurredAt());
        if (!hash.equals(obligation.getGovernanceEvidenceHash())) {
            return failure(kind, eventId, "EVENT_HASH_MISMATCH");
        }
        String requestId = "gov:" + hash;
        AuditEventDocument audit = auditEvents.findByRequestId(requestId).orElse(null);
        String state = obligationState(obligation.getStatus());
        if (audit == null) {
            return new Verification(kind.name(), eventId,
                    "CONFIRMED".equals(state) ? "AUDIT_EVENT_MISSING" : state,
                    hash, null, null, null, "NOT_VERIFIED", null);
        }
        if (audit.action() != AuditAction.COMMIT_GOVERNANCE_EVENT
                || audit.resourceType() != AuditResourceType.MODEL_GOVERNANCE_EVENT
                || !Objects.equals(audit.resourceId(), obligation.getResourceId())
                || !Objects.equals(audit.actorId(), obligation.getActor())
                || !AuditEventHasher.matches(audit)
                || audit.chainPosition() == null || audit.chainPosition() < 1) {
            return failure(kind, eventId, "AUDIT_EVENT_MISMATCH");
        }
        long countAtPosition = mongo.count(Query.query(Criteria.where("partition_key").is(audit.partitionKey())
                .and("chain_position").is(audit.chainPosition())), AuditEventDocument.class);
        if (countAtPosition != 1 || audit.chainPosition() > 1
                && (audit.previousEventHash() == null || auditEvents.findByPartitionKeyAndEventHash(
                        audit.partitionKey(), audit.previousEventHash())
                    .filter(previous -> previous.chainPosition() != null
                            && previous.chainPosition() + 1 == audit.chainPosition()
                            && AuditEventHasher.matches(previous)).isEmpty())) {
            return failure(kind, eventId, "AUDIT_CHAIN_BROKEN_OR_REPLAYED");
        }
        var matchingAnchors = anchors.findByPartitionKeyAndChainPositionIn(
                audit.partitionKey(), Set.of(audit.chainPosition()));
        if (matchingAnchors.size() != 1
                || !audit.eventHash().equals(matchingAnchors.getFirst().lastEventHash())
                || !Objects.equals(audit.previousEventHash(), matchingAnchors.getFirst().previousEventHash())) {
            return failure(kind, eventId, "LOCAL_ANCHOR_MISSING_OR_MISMATCHED");
        }
        var head = auditEvents.findLatestByPartitionKey(audit.partitionKey()).orElse(null);
        if (head == null || head.chainPosition() == null
                || head.chainPosition() < audit.chainPosition()
                || auditEvents.countByPartitionKey(audit.partitionKey()) != head.chainPosition()
                || !AuditEventHasher.matches(head)
                || anchors.findLatestByPartitionKey(audit.partitionKey())
                    .filter(anchor -> anchor.chainPosition() == head.chainPosition()
                            && Objects.equals(anchor.lastEventHash(), head.eventHash())).isEmpty()) {
            return failure(kind, eventId, "AUDIT_HEAD_BROKEN_OR_INCOMPLETE");
        }
        var external = externalAnchors == null ? null : externalAnchors.verify(matchingAnchors.getFirst());
        String externalStatus = external == null ? "NOT_VERIFIED" : external.status();
        if ("INVALID".equals(externalStatus)) {
            return new Verification(kind.name(), eventId, "TAMPERED", hash,
                    audit.eventHash(), audit.chainPosition(), matchingAnchors.getFirst().anchorId(),
                    externalStatus, external.reason());
        }
        String proofStatus = historicalProofStatus(actor, kind, event);
        if ("INVALID_OR_UNAVAILABLE".equals(proofStatus)) {
            return new Verification(kind.name(), eventId, "HISTORICAL_PROOF_INVALID", hash,
                    audit.eventHash(), audit.chainPosition(), matchingAnchors.getFirst().anchorId(),
                    externalStatus, "REFERENCED_PROOF_FAILED", proofStatus);
        }
        return new Verification(kind.name(), eventId,
                "CONFIRMED".equals(state)
                        ? "SIGNED_ENFORCED_WITNESS".equals(externalStatus)
                            ? "LOCAL_VERIFIED_SIGNED_ENFORCED_WITNESS"
                            : "LOCAL_VERIFIED_EXTERNAL_UNCONFIRMED"
                        : state,
                hash, audit.eventHash(), audit.chainPosition(), matchingAnchors.getFirst().anchorId(),
                externalStatus, null, proofStatus);
    }

    private String historicalProofStatus(PolicyGovernanceActor actor,
            GovernanceEventCommitment.Kind kind, Object event) {
        try {
            if (kind == GovernanceEventCommitment.Kind.CREDENTIAL_GRANT
                    && grantReviews != null && event instanceof PolicyCredentialSecurityEventDocument credential
                    && "GRANT_APPROVED".equals(credential.action())
                    && credential.id().startsWith("grant-review:")) {
                grantReviews.verifyHistorical(actor, credential.id().substring("grant-review:".length()));
                return "HISTORICALLY_VERIFIED";
            }
            if (kind == GovernanceEventCommitment.Kind.TRUST_CHANGE && trustChanges != null
                    && event instanceof PolicyTrustChangeAuditDocument trust) {
                trustChanges.verifyHistoricalChange(actor, trust.requestId());
                return "HISTORICALLY_VERIFIED";
            }
            return "DIGEST_BOUND_NOT_REVERIFIED";
        } catch (RuntimeException invalid) {
            return "INVALID_OR_UNAVAILABLE";
        }
    }

    private Object event(GovernanceEventCommitment.Kind kind, String id) {
        return switch (kind) {
            case CREDENTIAL_GRANT, CREDENTIAL_GRANT_REVOCATION ->
                    mongo.findById(id, PolicyCredentialSecurityEventDocument.class);
            case EMERGENCY_RETIREMENT -> mongo.findById(id, ModelPromotionEmergencyAuditDocument.class);
            case TRUST_CHANGE -> mongo.findById(id, PolicyTrustChangeAuditDocument.class);
            case POLICY_GOVERNANCE, POLICY_PUBLICATION ->
                    mongo.findById(id, PolicyGovernanceHistoryDocument.class);
            case PUBLICATION_RECOVERY -> mongo.findById(id, PolicyPublicationRecoveryEventDocument.class);
            case PUBLICATION_RECONCILIATION -> mongo.findById(id, PolicyPublicationReconciliationEventDocument.class);
        };
    }

    private Verification failure(GovernanceEventCommitment.Kind kind, String id, String reason) {
        return new Verification(kind.name(), id, "TAMPERED", null, null, null, null,
                "NOT_VERIFIED", reason);
    }

    private String obligationState(WriteActionAuditOutboxStatus status) {
        if (status == null) return "UNKNOWN";
        return switch (status) {
            case PENDING, PUBLISHING -> "PENDING";
            case FAILED_RETRYABLE -> "RETRYABLE";
            case FAILED_PERMANENT -> "BLOCKED";
            case PUBLISHED -> "CONFIRMED";
        };
    }

    public record Verification(String kind, String eventId, String status,
            String canonicalSha256, String auditEventSha256, Long chainPosition,
            String localAnchorId, String independentAnchorStatus, String reason,
            String historicalProofStatus) {
        public Verification(String kind, String eventId, String status,
                String canonicalSha256, String auditEventSha256, Long chainPosition,
                String localAnchorId, String independentAnchorStatus, String reason) {
            this(kind, eventId, status, canonicalSha256, auditEventSha256, chainPosition,
                    localAnchorId, independentAnchorStatus, reason, "NOT_REVERIFIED");
        }
    }
}
