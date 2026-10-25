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
import com.frauddetection.alert.governance.modelpromotion.ModelPromotionSignedReviewService;
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
import java.util.Map;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/** Historical evidence read. Local chain verification is distinct from independent external anchoring. */
@Service
public class GovernanceEventVerificationService {
    private final MongoTemplate mongo;
    private final AuditEventRepository auditEvents;
    private final AuditAnchorRepository anchors;
    private final PolicyCredentialGrantReviewService grantReviews;
    private final PolicyTrustChangeService trustChanges;
    private final GovernanceExternalAnchorVerifier externalAnchors;
    private final ModelPromotionSignedReviewService signedReviews;

    @Autowired
    public GovernanceEventVerificationService(MongoTemplate mongo, AuditEventRepository auditEvents,
            AuditAnchorRepository anchors, PolicyCredentialGrantReviewService grantReviews,
            PolicyTrustChangeService trustChanges, GovernanceExternalAnchorVerifier externalAnchors,
            ModelPromotionSignedReviewService signedReviews) {
        this.mongo = mongo;
        this.auditEvents = auditEvents;
        this.anchors = anchors;
        this.grantReviews = grantReviews;
        this.trustChanges = trustChanges;
        this.externalAnchors = externalAnchors;
        this.signedReviews = signedReviews;
    }

    public GovernanceEventVerificationService(MongoTemplate mongo, AuditEventRepository auditEvents,
            AuditAnchorRepository anchors, PolicyCredentialGrantReviewService grantReviews,
            PolicyTrustChangeService trustChanges, GovernanceExternalAnchorVerifier externalAnchors) {
        this(mongo, auditEvents, anchors, grantReviews, trustChanges, externalAnchors, null);
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
            String chainStatus = completeAuditChain("source_service:alert-service");
            if ("INVALID".equals(chainStatus)) {
                return failure(kind, eventId, "AUDIT_CHAIN_INVALID_OR_INCOMPLETE");
            }
            if ("INCOMPLETE".equals(chainStatus)) {
                return new Verification(kind.name(), eventId, "OUTCOME_UNKNOWN", hash,
                        null, null, null, "NOT_VERIFIED", "AUDIT_CHAIN_EXCEEDS_VERIFICATION_BOUND");
            }
            return new Verification(kind.name(), eventId,
                    "CONFIRMED".equals(state) ? "AUDIT_EVENT_MISSING" : state,
                    hash, null, null, null, "NOT_VERIFIED", null);
        }
        if (audit.action() != AuditAction.COMMIT_GOVERNANCE_EVENT
                || audit.resourceType() != AuditResourceType.MODEL_GOVERNANCE_EVENT
                || !Objects.equals(audit.resourceId(), obligation.getResourceId())
                || !Objects.equals(audit.actorId(), obligation.getActor())
                || !"SHA-256".equals(audit.hashAlgorithm()) || !"1.0".equals(audit.schemaVersion())
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
                || !Objects.equals(audit.previousEventHash(), matchingAnchors.getFirst().previousEventHash())
                || !Objects.equals(audit.hashAlgorithm(), matchingAnchors.getFirst().hashAlgorithm())) {
            return failure(kind, eventId, "LOCAL_ANCHOR_MISSING_OR_MISMATCHED");
        }
        var head = auditEvents.findLatestByPartitionKey(audit.partitionKey()).orElse(null);
        if (head == null || head.chainPosition() == null
                || head.chainPosition() < audit.chainPosition()
                || auditEvents.countByPartitionKey(audit.partitionKey()) != head.chainPosition()
                || !"SHA-256".equals(head.hashAlgorithm()) || !"1.0".equals(head.schemaVersion())
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
                    externalStatus, external.reason(), "NOT_REVERIFIED", EventIntegrity.VERIFIED,
                    ChainRangeIntegrity.NOT_CHECKED, HistoricalActionProof.NOT_CHECKED,
                    WitnessAssurance.INVALID, CurrentAuthorization.NOT_ASSESSED,
                    "LOCAL_AUDIT_TRUST_AUTHORITY", external.signingAuthority(), external.signingKeyId());
        }
        String proofStatus = historicalProofStatus(actor, kind, event);
        if ("INVALID_OR_UNAVAILABLE".equals(proofStatus)) {
            return new Verification(kind.name(), eventId, "HISTORICAL_PROOF_INVALID", hash,
                    audit.eventHash(), audit.chainPosition(), matchingAnchors.getFirst().anchorId(),
                    externalStatus, "REFERENCED_PROOF_FAILED", proofStatus, EventIntegrity.VERIFIED,
                    ChainRangeIntegrity.NOT_CHECKED, HistoricalActionProof.INVALID_OR_UNAVAILABLE,
                    WitnessAssurance.from(externalStatus), CurrentAuthorization.NOT_ASSESSED,
                    "LOCAL_AUDIT_TRUST_AUTHORITY", external == null ? null : external.signingAuthority(),
                    external == null ? null : external.signingKeyId());
        }
        return new Verification(kind.name(), eventId,
                "CONFIRMED".equals(state)
                        ? "SIGNED_ENFORCED_WITNESS".equals(externalStatus)
                            ? "LOCAL_VERIFIED_SIGNED_ENFORCED_WITNESS"
                            : "LOCAL_VERIFIED_EXTERNAL_UNCONFIRMED"
                        : state,
                hash, audit.eventHash(), audit.chainPosition(), matchingAnchors.getFirst().anchorId(),
                externalStatus, null, proofStatus,
                EventIntegrity.VERIFIED, ChainRangeIntegrity.NOT_CHECKED,
                HistoricalActionProof.from(proofStatus), WitnessAssurance.from(externalStatus),
                CurrentAuthorization.NOT_ASSESSED, "LOCAL_AUDIT_TRUST_AUTHORITY",
                external == null ? null : external.signingAuthority(),
                external == null ? null : external.signingKeyId());
    }

    /** Historical proof-only verification; it deliberately makes no event-chain or current-authorization claim. */
    public HistoricalProofVerification verifyModelPromotionReviewProof(PolicyGovernanceActor actor,
            String proposalId) {
        if (actor == null || !actor.authorities().contains(ModelPolicyAuthority.AUDIT_READ)
                || !actor.authorities().contains(ModelPolicyAuthority.PRODUCTION)) {
            throw new AccessDeniedException("Production governance audit mandate required");
        }
        if (proposalId == null || !proposalId.matches("^[A-Za-z0-9._:-]{1,160}$")) {
            return new HistoricalProofVerification("INVALID_REQUEST", EventIntegrity.NOT_CHECKED,
                    ChainRangeIntegrity.NOT_CHECKED, HistoricalActionProof.NOT_CHECKED,
                    WitnessAssurance.NOT_ASSESSED, CurrentAuthorization.NOT_ASSESSED,
                    "MODEL_PROMOTION_SIGNED_REVIEW_VERIFIER", "INVALID_PROPOSAL_ID");
        }
        try {
            if (signedReviews == null) throw new IllegalStateException("Historical verifier unavailable");
            signedReviews.verifyHistoricalReview(actor, proposalId);
            return new HistoricalProofVerification("HISTORICALLY_VERIFIED", EventIntegrity.NOT_CHECKED,
                    ChainRangeIntegrity.NOT_CHECKED, HistoricalActionProof.VERIFIED,
                    WitnessAssurance.NOT_ASSESSED, CurrentAuthorization.NOT_ASSESSED,
                    "MODEL_PROMOTION_SIGNED_REVIEW_VERIFIER", null);
        } catch (AccessDeniedException denied) {
            throw denied;
        } catch (RuntimeException unavailableOrInvalid) {
            return new HistoricalProofVerification("INVALID_OR_UNAVAILABLE", EventIntegrity.NOT_CHECKED,
                    ChainRangeIntegrity.NOT_CHECKED, HistoricalActionProof.INVALID_OR_UNAVAILABLE,
                    WitnessAssurance.NOT_ASSESSED, CurrentAuthorization.NOT_ASSESSED,
                    "MODEL_PROMOTION_SIGNED_REVIEW_VERIFIER", "REFERENCED_PROOF_FAILED_OR_UNAVAILABLE");
        }
    }

    /** Absence is retry-safe only when the complete bounded local chain and every local anchor verify. */
    private String completeAuditChain(String partitionKey) {
        long count = auditEvents.countByPartitionKey(partitionKey);
        if (count > 10_000) return "INCOMPLETE";
        List<AuditEventDocument> events = auditEvents.findFullChain(partitionKey, 10_001);
        if (events.size() != count) return "INVALID";
        AuditEventDocument previous = null;
        for (int offset = 0; offset < events.size(); offset += 100) {
            List<AuditEventDocument> batch = events.subList(offset, Math.min(events.size(), offset + 100));
            Set<Long> positions = batch.stream().map(AuditEventDocument::chainPosition)
                    .filter(Objects::nonNull).collect(java.util.stream.Collectors.toSet());
            var matching = anchors.findByPartitionKeyAndChainPositionIn(partitionKey, positions);
            Map<Long, List<com.frauddetection.alert.audit.AuditAnchorDocument>> byPosition = new HashMap<>();
            matching.forEach(anchor -> byPosition.computeIfAbsent(anchor.chainPosition(), ignored -> new ArrayList<>()).add(anchor));
            for (int index = 0; index < batch.size(); index++) {
                AuditEventDocument current = batch.get(index);
                long expectedPosition = offset + index + 1L;
                if (!Objects.equals(current.partitionKey(), partitionKey)
                        || current.chainPosition() == null || current.chainPosition() != expectedPosition
                        || !"SHA-256".equals(current.hashAlgorithm()) || !"1.0".equals(current.schemaVersion())
                        || !AuditEventHasher.matches(current)
                        || !Objects.equals(current.previousEventHash(), previous == null ? null : previous.eventHash()))
                    return "INVALID";
                List<com.frauddetection.alert.audit.AuditAnchorDocument> atPosition = byPosition.get(current.chainPosition());
                if (atPosition == null || atPosition.size() != 1
                        || !Objects.equals(atPosition.getFirst().lastEventHash(), current.eventHash())
                        || !Objects.equals(atPosition.getFirst().previousEventHash(), current.previousEventHash())
                        || !Objects.equals(atPosition.getFirst().hashAlgorithm(), current.hashAlgorithm()))
                    return "INVALID";
                previous = current;
            }
        }
        var head = auditEvents.findLatestByPartitionKey(partitionKey).orElse(null);
        var anchor = anchors.findLatestByPartitionKey(partitionKey).orElse(null);
        boolean complete = count == 0 ? head == null && anchor == null
                : previous != null && head != null && anchor != null
                    && Objects.equals(head.eventHash(), previous.eventHash())
                    && Objects.equals(anchor.lastEventHash(), head.eventHash())
                    && Objects.equals(anchor.chainPosition(), head.chainPosition())
                    && anchor.chainPosition() == count;
        return complete ? "VALID" : "INVALID";
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

    public enum EventIntegrity { VERIFIED, INVALID, NOT_CHECKED }
    public enum ChainRangeIntegrity { VERIFIED, NOT_CHECKED }
    public enum HistoricalActionProof { VERIFIED, DIGEST_BOUND_NOT_REVERIFIED, INVALID_OR_UNAVAILABLE, NOT_CHECKED;
        static HistoricalActionProof from(String status) {
            return switch (status == null ? "" : status) {
                case "HISTORICALLY_VERIFIED" -> VERIFIED;
                case "DIGEST_BOUND_NOT_REVERIFIED" -> DIGEST_BOUND_NOT_REVERIFIED;
                case "INVALID_OR_UNAVAILABLE" -> INVALID_OR_UNAVAILABLE;
                default -> NOT_CHECKED;
            };
        }
    }
    public enum WitnessAssurance { SIGNED_ENFORCED_LOCAL, SIGNED_LOCAL_OR_UNENFORCED,
        INVALID, UNSIGNED, NOT_VERIFIED, UNAVAILABLE, NOT_ASSESSED;
        static WitnessAssurance from(String status) {
            if (status == null) return NOT_VERIFIED;
            return switch (status) {
                case "SIGNED_ENFORCED_WITNESS" -> SIGNED_ENFORCED_LOCAL;
                case "SIGNED_LOCAL_OR_UNENFORCED" -> SIGNED_LOCAL_OR_UNENFORCED;
                case "INVALID", "UNKNOWN_KEY", "KEY_REVOKED" -> INVALID;
                case "UNSIGNED" -> UNSIGNED;
                case "UNAVAILABLE" -> UNAVAILABLE;
                default -> NOT_VERIFIED;
            };
        }
    }
    public enum CurrentAuthorization { AUTHORIZED, NOT_AUTHORIZED, NOT_ASSESSED }

    public record HistoricalProofVerification(String status, EventIntegrity eventIntegrity,
            ChainRangeIntegrity chainRangeIntegrity, HistoricalActionProof historicalActionProof,
            WitnessAssurance externalWitness, CurrentAuthorization currentAuthorization,
            String verificationAuthority, String reason) { }

    public record Verification(String kind, String eventId, String status,
            String canonicalSha256, String auditEventSha256, Long chainPosition,
            String localAnchorId, String independentAnchorStatus, String reason,
            String historicalProofStatus, EventIntegrity eventIntegrity,
            ChainRangeIntegrity chainRangeIntegrity, HistoricalActionProof historicalActionProof,
            WitnessAssurance externalWitness, CurrentAuthorization currentAuthorization,
            String witnessVerificationAuthority, String witnessSigningAuthority, String witnessSigningKeyId) {
        public Verification(String kind, String eventId, String status,
                String canonicalSha256, String auditEventSha256, Long chainPosition,
                String localAnchorId, String independentAnchorStatus, String reason,
                String historicalProofStatus) {
            this(kind, eventId, status, canonicalSha256, auditEventSha256, chainPosition,
                    localAnchorId, independentAnchorStatus, reason, historicalProofStatus,
                    "TAMPERED".equals(status) || "HISTORICAL_PROOF_INVALID".equals(status)
                            ? EventIntegrity.INVALID : "LOCAL_VERIFIED_EXTERNAL_UNCONFIRMED".equals(status)
                                || "LOCAL_VERIFIED_SIGNED_ENFORCED_WITNESS".equals(status)
                                    ? EventIntegrity.VERIFIED : EventIntegrity.NOT_CHECKED,
                    ChainRangeIntegrity.NOT_CHECKED, HistoricalActionProof.from(historicalProofStatus),
                    WitnessAssurance.from(independentAnchorStatus), CurrentAuthorization.NOT_ASSESSED,
                    "LOCAL_AUDIT_TRUST_AUTHORITY", null, null);
        }
        public Verification(String kind, String eventId, String status,
                String canonicalSha256, String auditEventSha256, Long chainPosition,
                String localAnchorId, String independentAnchorStatus, String reason) {
            this(kind, eventId, status, canonicalSha256, auditEventSha256, chainPosition,
                    localAnchorId, independentAnchorStatus, reason, "NOT_REVERIFIED");
        }
    }
}
