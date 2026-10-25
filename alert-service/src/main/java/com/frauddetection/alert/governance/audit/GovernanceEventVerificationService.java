package com.frauddetection.alert.governance.audit;

import com.frauddetection.alert.audit.AuditAction;
import com.frauddetection.alert.audit.AuditAnchorRepository;
import com.frauddetection.alert.audit.AuditEventDocument;
import com.frauddetection.alert.audit.AuditEventHasher;
import com.frauddetection.alert.audit.AuditEventRepository;
import com.frauddetection.alert.audit.AuditAnchorDocument;
import com.frauddetection.alert.audit.AuditChainLockDocument;
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
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.Objects;
import java.util.Set;
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
    private final TransactionTemplate snapshotTransaction;

    @Autowired
    public GovernanceEventVerificationService(MongoTemplate mongo, AuditEventRepository auditEvents,
            AuditAnchorRepository anchors, PolicyCredentialGrantReviewService grantReviews,
            PolicyTrustChangeService trustChanges, GovernanceExternalAnchorVerifier externalAnchors,
            ModelPromotionSignedReviewService signedReviews, MongoTransactionManager transactionManager) {
        this.mongo = mongo;
        this.auditEvents = auditEvents;
        this.anchors = anchors;
        this.grantReviews = grantReviews;
        this.trustChanges = trustChanges;
        this.externalAnchors = externalAnchors;
        this.signedReviews = signedReviews;
        this.snapshotTransaction = transactionManager == null ? null : new TransactionTemplate(transactionManager);
    }

    public GovernanceEventVerificationService(MongoTemplate mongo, AuditEventRepository auditEvents,
            AuditAnchorRepository anchors, PolicyCredentialGrantReviewService grantReviews,
            PolicyTrustChangeService trustChanges, GovernanceExternalAnchorVerifier externalAnchors,
            ModelPromotionSignedReviewService signedReviews) {
        this(mongo, auditEvents, anchors, grantReviews, trustChanges, externalAnchors, signedReviews, null);
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
        VerificationSnapshot snapshot = readSnapshot(kind, eventId);
        var obligation = snapshot.obligation();
        Object event = snapshot.governanceEvent();
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
        AuditEventDocument audit = snapshot.auditEvent();
        String state = obligationState(obligation.getStatus());
        if (audit == null) {
            if (!validHeadSnapshot(snapshot)) {
                return failure(kind, eventId, "AUDIT_HEAD_BROKEN_OR_INCOMPLETE");
            }
            if (!safeToProveAbsence(snapshot)) {
                return new Verification(kind.name(), eventId, "OUTCOME_UNKNOWN", hash,
                        null, null, null, "NOT_VERIFIED", "APPEND_OR_CLAIM_MAY_STILL_BE_IN_FLIGHT");
            }
            return new Verification(kind.name(), eventId,
                    "CONFIRMED".equals(state) ? "AUDIT_EVENT_MISSING" : state,
                    hash, null, null, null, "NOT_VERIFIED", null);
        }
        if (!Objects.equals(audit.requestId(), requestId)
                || audit.action() != AuditAction.COMMIT_GOVERNANCE_EVENT
                || audit.eventType() != AuditAction.COMMIT_GOVERNANCE_EVENT
                || audit.resourceType() != AuditResourceType.MODEL_GOVERNANCE_EVENT
                || !Objects.equals(audit.resourceId(), obligation.getResourceId())
                || !Objects.equals(audit.actorId(), obligation.getActor())
                || !Objects.equals(audit.correlationId(), obligation.getCorrelationId())
                || audit.outcome() != obligation.getOutcome()
                || audit.failureReason() != null
                || obligation.getMetadataSummary() != null
                    && !Objects.equals(audit.metadataSummary(), obligation.getMetadataSummary())
                || !Objects.equals(audit.sourceService(), "alert-service")
                || !Objects.equals(audit.partitionKey(), "source_service:alert-service")
                || !"SHA-256".equals(audit.hashAlgorithm()) || !"1.0".equals(audit.schemaVersion())
                || !AuditEventHasher.matches(audit)
                || audit.chainPosition() == null || audit.chainPosition() < 1) {
            return failure(kind, eventId, "AUDIT_EVENT_MISMATCH");
        }
        AuditEventDocument predecessor = snapshot.predecessor();
        if (audit.chainPosition() == 1L ? audit.previousEventHash() != null
                : predecessor == null || predecessor.chainPosition() == null
                    || predecessor.chainPosition() != audit.chainPosition() - 1
                    || !Objects.equals(predecessor.eventHash(), audit.previousEventHash())
                    || !AuditEventHasher.matches(predecessor)) {
            return failure(kind, eventId, "AUDIT_CHAIN_BROKEN_OR_REPLAYED");
        }
        List<AuditAnchorDocument> matchingAnchors = snapshot.eventAnchors();
        if (matchingAnchors.size() != 1
                || !audit.eventHash().equals(matchingAnchors.getFirst().lastEventHash())
                || !Objects.equals(audit.previousEventHash(), matchingAnchors.getFirst().previousEventHash())
                || !Objects.equals(audit.hashAlgorithm(), matchingAnchors.getFirst().hashAlgorithm())) {
            return failure(kind, eventId, "LOCAL_ANCHOR_MISSING_OR_MISMATCHED");
        }
        var head = snapshot.head();
        var headAnchors = snapshot.headAnchors();
        if (head == null || head.chainPosition() == null
                || head.chainPosition() < audit.chainPosition()
                || !"SHA-256".equals(head.hashAlgorithm()) || !"1.0".equals(head.schemaVersion())
                || !AuditEventHasher.matches(head)
                || headAnchors.size() != 1
                || headAnchors.getFirst().chainPosition() != head.chainPosition()
                || !Objects.equals(headAnchors.getFirst().lastEventHash(), head.eventHash())
                || snapshot.latestAnchor() == null
                || snapshot.latestAnchor().chainPosition() != head.chainPosition()
                || !Objects.equals(snapshot.latestAnchor().lastEventHash(), head.eventHash())
                || head.chainPosition() > 1L
                    && (snapshot.headPredecessor() == null
                        || snapshot.headPredecessor().chainPosition() != head.chainPosition() - 1L
                        || !Objects.equals(snapshot.headPredecessor().eventHash(), head.previousEventHash())
                        || !AuditEventHasher.matches(snapshot.headPredecessor()))
                || audit.chainPosition() < head.chainPosition()
                    && !validImmediateSuccessor(audit, snapshot.successor())) {
            return failure(kind, eventId, "AUDIT_HEAD_BROKEN_OR_INCOMPLETE");
        }
        com.frauddetection.alert.audit.external.GovernanceExternalAnchorVerifier.Result external = null;
        String externalStatus = "NOT_VERIFIED";
        if (externalAnchors != null) {
            try {
                external = externalAnchors.verify(matchingAnchors.getFirst());
                externalStatus = external == null ? "NOT_VERIFIED" : external.status();
            } catch (RuntimeException unavailable) {
                externalStatus = "UNAVAILABLE";
            }
        }
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

    private VerificationSnapshot readSnapshot(GovernanceEventCommitment.Kind kind, String eventId) {
        java.util.function.Supplier<VerificationSnapshot> load = () -> loadSnapshot(kind, eventId);
        return snapshotTransaction == null ? load.get() : snapshotTransaction.execute(status -> load.get());
    }

    private VerificationSnapshot loadSnapshot(GovernanceEventCommitment.Kind kind, String eventId) {
        String outboxId = GovernanceEventCommitment.idFor(kind, eventId);
        WriteActionAuditOutboxRecord obligation = mongo.findById(outboxId, WriteActionAuditOutboxRecord.class);
        Object source = event(kind, eventId);
        AuditEventDocument audit = null;
        if (obligation != null && source != null && obligation.getGovernanceOccurredAt() != null) {
            String hash = GovernanceEventCommitment.hash(kind, eventId, source,
                    obligation.getGovernanceScope(), obligation.getActor(),
                    obligation.getGovernanceObjectId(), obligation.getGovernanceOperationId(),
                    obligation.getGovernanceProofDigest(), obligation.getGovernanceResultId(),
                    obligation.getGovernanceOccurredAt());
            if (Objects.equals(hash, obligation.getGovernanceEvidenceHash())) {
                audit = auditEvents.findByRequestId("gov:" + hash).orElse(null);
            }
        }

        AuditEventDocument head = auditEvents.findLatestByPartitionKey("source_service:alert-service").orElse(null);
        List<AuditAnchorDocument> eventAnchors = audit == null || audit.chainPosition() == null ? List.of()
                : anchors.findByPartitionKeyAndChainPositionIn(audit.partitionKey(), Set.of(audit.chainPosition()));
        AuditEventDocument predecessor = null;
        AuditEventDocument successor = null;
        if (audit != null && audit.chainPosition() != null && audit.chainPosition() > 1L) {
            List<AuditEventDocument> rows = auditEvents.findByPartitionKeyAndChainPositionBetween(
                    audit.partitionKey(), audit.chainPosition() - 1L, audit.chainPosition() - 1L, 2);
            predecessor = rows.size() == 1 ? rows.getFirst() : null;
        }
        if (audit != null && head != null && audit.chainPosition() != null
                && audit.chainPosition() < head.chainPosition()) {
            List<AuditEventDocument> rows = auditEvents.findByPartitionKeyAndChainPositionBetween(
                    audit.partitionKey(), audit.chainPosition() + 1L, audit.chainPosition() + 1L, 2);
            successor = rows.size() == 1 ? rows.getFirst() : null;
        }
        AuditEventDocument headPredecessor = null;
        if (head != null && head.chainPosition() != null && head.chainPosition() > 1L) {
            if (audit != null && Objects.equals(audit.chainPosition(), head.chainPosition())) {
                headPredecessor = predecessor;
            } else {
                List<AuditEventDocument> rows = auditEvents.findByPartitionKeyAndChainPositionBetween(
                        head.partitionKey(), head.chainPosition() - 1L, head.chainPosition() - 1L, 2);
                headPredecessor = rows.size() == 1 ? rows.getFirst() : null;
            }
        }
        List<AuditAnchorDocument> headAnchors = head == null || head.chainPosition() == null ? List.of()
                : anchors.findByPartitionKeyAndChainPositionIn(head.partitionKey(), Set.of(head.chainPosition()));
        AuditAnchorDocument latestAnchor = anchors.findLatestByPartitionKey("source_service:alert-service").orElse(null);
        AuditChainLockDocument lock = mongo.findById("source_service:alert-service", AuditChainLockDocument.class);
        return new VerificationSnapshot(obligation, source, audit, eventAnchors, predecessor, successor,
                head, headPredecessor, headAnchors, latestAnchor, lock);
    }

    private boolean safeToProveAbsence(VerificationSnapshot snapshot) {
        WriteActionAuditOutboxRecord obligation = snapshot.obligation();
        if (obligation == null
                || !(obligation.getStatus() == WriteActionAuditOutboxStatus.FAILED_PERMANENT
                    || obligation.getStatus() == WriteActionAuditOutboxStatus.FAILED_RETRYABLE)
                || obligation.getClaimOwner() != null
                || obligation.getClaimToken() != null
                || obligation.getClaimedAt() != null
                || obligation.getClaimExpiresAt() != null) {
            return false;
        }
        AuditChainLockDocument lock = snapshot.lock();
        return lock == null || (lock.ownerToken() == null || lock.ownerToken().isBlank())
                && (lock.lockedUntil() == null || !lock.lockedUntil().isAfter(java.time.Instant.EPOCH));
    }

    private boolean validHeadSnapshot(VerificationSnapshot snapshot) {
        AuditEventDocument head = snapshot.head();
        if (head == null) {
            return snapshot.headAnchors().isEmpty() && snapshot.latestAnchor() == null;
        }
        return head.chainPosition() != null && head.chainPosition() > 0L
                && "SHA-256".equals(head.hashAlgorithm()) && "1.0".equals(head.schemaVersion())
                && AuditEventHasher.matches(head)
                && snapshot.headAnchors().size() == 1
                && snapshot.latestAnchor() != null
                && snapshot.headAnchors().getFirst().chainPosition() == head.chainPosition()
                && Objects.equals(snapshot.headAnchors().getFirst().lastEventHash(), head.eventHash())
                && snapshot.latestAnchor().chainPosition() == head.chainPosition()
                && Objects.equals(snapshot.latestAnchor().lastEventHash(), head.eventHash())
                && (head.chainPosition() == 1L || snapshot.headPredecessor() != null
                    && snapshot.headPredecessor().chainPosition() == head.chainPosition() - 1L
                    && Objects.equals(snapshot.headPredecessor().eventHash(), head.previousEventHash())
                    && AuditEventHasher.matches(snapshot.headPredecessor()));
    }

    private boolean validImmediateSuccessor(AuditEventDocument audit, AuditEventDocument successor) {
        return successor != null
                && successor.chainPosition() != null
                && successor.chainPosition() == audit.chainPosition() + 1L
                && Objects.equals(successor.previousEventHash(), audit.eventHash())
                && AuditEventHasher.matches(successor);
    }

    private record VerificationSnapshot(WriteActionAuditOutboxRecord obligation, Object governanceEvent,
            AuditEventDocument auditEvent, List<AuditAnchorDocument> eventAnchors,
            AuditEventDocument predecessor, AuditEventDocument successor, AuditEventDocument head,
            AuditEventDocument headPredecessor, List<AuditAnchorDocument> headAnchors, AuditAnchorDocument latestAnchor,
            AuditChainLockDocument lock) { }

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
