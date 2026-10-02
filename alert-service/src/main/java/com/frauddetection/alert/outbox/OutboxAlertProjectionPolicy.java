package com.frauddetection.alert.outbox;

import com.frauddetection.alert.audit.ResolutionEvidenceReference;
import com.frauddetection.alert.audit.ResolutionEvidenceType;
import com.frauddetection.alert.regulated.RegulatedMutationIntentHasher;
import com.frauddetection.alert.service.DecisionOutboxStatus;
import org.bson.Document;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Set;

public final class OutboxAlertProjectionPolicy {

    private static final String MANUAL_RECOVERY_REQUIRED = "MANUAL_RECOVERY_REQUIRED";
    private static final Set<TransactionalOutboxStatus> STABLE_PROJECTED_STATUSES = Set.of(
            TransactionalOutboxStatus.PUBLISHED,
            TransactionalOutboxStatus.PUBLISH_CONFIRMATION_UNKNOWN,
            TransactionalOutboxStatus.FAILED_RETRYABLE,
            TransactionalOutboxStatus.FAILED_TERMINAL,
            TransactionalOutboxStatus.RECOVERY_REQUIRED
    );

    private OutboxAlertProjectionPolicy() {
    }

    static Projection transition(
            TransactionalOutboxRecordDocument record,
            TransactionalOutboxStatus sourceStatus,
            String reason,
            Instant publishedAt
    ) {
        return projection(record, sourceStatus, reason, publishedAt);
    }

    static Projection recovery(TransactionalOutboxRecordDocument record) {
        return authoritative(record);
    }

    public static Projection manualResolution(TransactionalOutboxRecordDocument record) {
        return authoritative(record);
    }

    static List<String> persistedProjectionViolations(Document source, Document alert) {
        TransactionalOutboxRecordDocument record = persistedRecord(source);
        if (record == null || !STABLE_PROJECTED_STATUSES.contains(record.getStatus())) {
            return List.of();
        }
        Document update = recovery(record).update().getUpdateObject();
        Document expectedSet = update.get("$set", Document.class);
        Document expectedUnset = update.get("$unset", Document.class);
        List<String> violations = new ArrayList<>();
        if (expectedSet != null) {
            expectedSet.forEach((field, expected) -> {
                if (!persistedValueEquals(expected, alert.get(field))) {
                    violations.add(projectionMismatch(field));
                }
            });
        }
        if (expectedUnset != null) {
            expectedUnset.keySet().stream()
                    .filter(alert::containsKey)
                    .map(OutboxAlertProjectionPolicy::projectionMismatch)
                    .forEach(violations::add);
        }
        return violations;
    }

    public static boolean brokerPublicationHasNoManualMetadata(TransactionalOutboxRecordDocument record) {
        return record.getResolutionControlMode() == null
                && !record.isResolutionPending()
                && record.getResolutionRequestId() == null
                && record.getResolutionProposedOutcome() == null
                && record.getResolutionRequestedBy() == null
                && record.getResolutionRequestedAt() == null
                && record.getResolutionRequestReason() == null
                && record.getResolutionApprovalReason() == null
                && record.getResolutionEvidenceType() == null
                && record.getResolutionEvidenceReference() == null
                && record.getResolutionEvidenceVerifiedAt() == null
                && record.getResolutionEvidenceVerifiedBy() == null
                && record.getResolutionEvidenceFingerprint() == null
                && record.getResolutionApprovalEvidenceType() == null
                && record.getResolutionApprovalEvidenceReference() == null
                && record.getResolutionApprovalEvidenceVerifiedAt() == null
                && record.getResolutionApprovalEvidenceVerifiedBy() == null
                && record.getResolutionApprovalEvidenceFingerprint() == null
                && record.getResolutionApprovedBy() == null
                && record.getResolutionApprovedAt() == null;
    }

    public static boolean validDualControlPublicationEvidence(TransactionalOutboxRecordDocument record) {
        return validDualControlEvidence(record, "PUBLISHED");
    }

    static boolean validDualControlEvidence(
            TransactionalOutboxRecordDocument record,
            String expectedOutcome
    ) {
        return "DUAL_CONTROL_APPROVED".equals(record.getResolutionControlMode())
                && !record.isResolutionPending()
                && hasText(record.getResolutionRequestId())
                && expectedOutcome.equals(record.getResolutionProposedOutcome())
                && hasText(record.getResolutionRequestedBy())
                && hasText(record.getResolutionApprovedBy())
                && !record.getResolutionRequestedBy().equals(record.getResolutionApprovedBy())
                && record.getResolutionRequestedAt() != null
                && record.getResolutionApprovedAt() != null
                && !record.getResolutionApprovedAt().isBefore(record.getResolutionRequestedAt())
                && record.getResolutionEvidenceVerifiedAt() != null
                && !record.getResolutionEvidenceVerifiedAt().isAfter(record.getResolutionRequestedAt())
                && record.getResolutionApprovalEvidenceVerifiedAt() != null
                && !record.getResolutionApprovalEvidenceVerifiedAt().isAfter(record.getResolutionApprovedAt())
                && hasText(record.getResolutionRequestReason())
                && hasText(record.getResolutionApprovalReason())
                && evidenceFingerprintMatches(
                        record.getResolutionEvidenceType(),
                        record.getResolutionEvidenceReference(),
                        record.getResolutionEvidenceVerifiedAt(),
                        record.getResolutionEvidenceVerifiedBy(),
                        record.getResolutionEvidenceFingerprint()
                )
                && evidenceFingerprintMatches(
                        record.getResolutionApprovalEvidenceType(),
                        record.getResolutionApprovalEvidenceReference(),
                        record.getResolutionApprovalEvidenceVerifiedAt(),
                        record.getResolutionApprovalEvidenceVerifiedBy(),
                        record.getResolutionApprovalEvidenceFingerprint()
                );
    }

    static boolean validPendingDualControlEvidence(TransactionalOutboxRecordDocument record) {
        return "DUAL_CONTROL_REQUESTED".equals(record.getResolutionControlMode())
                && record.isResolutionPending()
                && hasText(record.getResolutionRequestId())
                && hasText(record.getResolutionProposedOutcome())
                && hasText(record.getResolutionRequestedBy())
                && record.getResolutionRequestedAt() != null
                && hasText(record.getResolutionRequestReason())
                && record.getResolutionEvidenceVerifiedAt() != null
                && !record.getResolutionEvidenceVerifiedAt().isAfter(record.getResolutionRequestedAt())
                && evidenceFingerprintMatches(
                        record.getResolutionEvidenceType(),
                        record.getResolutionEvidenceReference(),
                        record.getResolutionEvidenceVerifiedAt(),
                        record.getResolutionEvidenceVerifiedBy(),
                        record.getResolutionEvidenceFingerprint()
                );
    }

    public static boolean validSingleControlPublicationEvidence(TransactionalOutboxRecordDocument record) {
        return "SINGLE_CONTROL_OPERATOR_ATTESTED".equals(record.getResolutionControlMode())
                && !record.isResolutionPending()
                && hasText(record.getResolutionApprovedBy())
                && record.getResolutionApprovedAt() != null
                && hasText(record.getResolutionApprovalReason())
                && record.getResolutionEvidenceVerifiedAt() != null
                && !record.getResolutionEvidenceVerifiedAt().isAfter(record.getResolutionApprovedAt())
                && evidenceFingerprintMatches(
                        record.getResolutionEvidenceType(),
                        record.getResolutionEvidenceReference(),
                        record.getResolutionEvidenceVerifiedAt(),
                        record.getResolutionEvidenceVerifiedBy(),
                        record.getResolutionEvidenceFingerprint()
                );
    }

    private static Projection authoritative(TransactionalOutboxRecordDocument record) {
        String reason = record.getStatus() == TransactionalOutboxStatus.RECOVERY_REQUIRED
                ? MANUAL_RECOVERY_REQUIRED
                : record.getLastError();
        return projection(record, record.getStatus(), reason, record.getPublishedAt());
    }

    static TransactionalOutboxStatus sourceStatus(String projectionStatus) {
        return switch (projectionStatus) {
            case DecisionOutboxStatus.PUBLISHED -> TransactionalOutboxStatus.PUBLISHED;
            case DecisionOutboxStatus.PUBLISH_CONFIRMATION_UNKNOWN ->
                    TransactionalOutboxStatus.PUBLISH_CONFIRMATION_UNKNOWN;
            case DecisionOutboxStatus.FAILED_TERMINAL -> TransactionalOutboxStatus.FAILED_TERMINAL;
            case DecisionOutboxStatus.FAILED_RETRYABLE -> TransactionalOutboxStatus.FAILED_RETRYABLE;
            default -> throw new IllegalArgumentException("Unsupported transactional outbox projection status.");
        };
    }

    private static Projection projection(
            TransactionalOutboxRecordDocument record,
            TransactionalOutboxStatus sourceStatus,
            String reason,
            Instant publishedAt
    ) {
        String projectionStatus = switch (sourceStatus) {
            case PUBLISHED -> DecisionOutboxStatus.PUBLISHED;
            case PUBLISH_CONFIRMATION_UNKNOWN -> DecisionOutboxStatus.PUBLISH_CONFIRMATION_UNKNOWN;
            case FAILED_RETRYABLE -> DecisionOutboxStatus.FAILED_RETRYABLE;
            case FAILED_TERMINAL, RECOVERY_REQUIRED -> DecisionOutboxStatus.FAILED_TERMINAL;
            default -> throw new IllegalArgumentException(
                    "Unsupported transactional outbox projection source status: " + sourceStatus
            );
        };
        String effectiveReason = sourceStatus == TransactionalOutboxStatus.RECOVERY_REQUIRED
                ? MANUAL_RECOVERY_REQUIRED
                : reason;
        Update update = new Update()
                .set("decisionOutboxEventId", record.getEventId())
                .set("decisionOutboxProjectionRevision", record.getProjectionRevision())
                .set("decisionOutboxStatus", projectionStatus)
                .set("decisionOutboxAttempts", record.getAttempts())
                .unset("decisionOutboxLeaseOwner")
                .unset("decisionOutboxLeaseExpiresAt");
        if (sourceStatus == TransactionalOutboxStatus.PUBLISHED && publishedAt != null) {
            update.set("decisionOutboxPublishedAt", publishedAt);
        } else {
            update.unset("decisionOutboxPublishedAt");
        }
        String publicationConfirmationProvenance = sourceStatus == TransactionalOutboxStatus.PUBLISHED
                && record.getPublicationConfirmationProvenance() != null
                ? record.getPublicationConfirmationProvenance().name()
                : null;
        copyOrUnset(
                update,
                "decisionOutboxPublicationConfirmationProvenance",
                publicationConfirmationProvenance
        );
        if (effectiveReason == null) {
            update.unset("decisionOutboxLastError").unset("decisionOutboxFailureReason");
        } else {
            update.set("decisionOutboxLastError", effectiveReason)
                    .set("decisionOutboxFailureReason", effectiveReason);
        }
        copyResolutionFields(update, record);
        return new Projection(
                sourceStatus,
                record.getEventId(),
                record.getProjectionRevision(),
                projectionStatus,
                update
        );
    }

    private static void copyResolutionFields(Update update, TransactionalOutboxRecordDocument record) {
        if (record.isResolutionPending()) {
            update.set("decisionOutboxResolutionPending", true);
        } else {
            update.unset("decisionOutboxResolutionPending");
        }
        copyOrUnset(update, "decisionOutboxResolutionRequestId", record.getResolutionRequestId());
        copyOrUnset(update, "decisionOutboxResolutionProposedOutcome", record.getResolutionProposedOutcome());
        copyOrUnset(update, "decisionOutboxResolutionRequestedAt", record.getResolutionRequestedAt());
        copyOrUnset(update, "decisionOutboxResolutionRequestedBy", record.getResolutionRequestedBy());
        copyOrUnset(update, "decisionOutboxResolutionRequestReason", record.getResolutionRequestReason());
        copyOrUnset(update, "decisionOutboxResolutionApprovalReason", record.getResolutionApprovalReason());
        copyOrUnset(update, "decisionOutboxResolutionEvidenceType", record.getResolutionEvidenceType());
        copyOrUnset(update, "decisionOutboxResolutionEvidenceReference", record.getResolutionEvidenceReference());
        copyOrUnset(update, "decisionOutboxResolutionEvidenceVerifiedAt", record.getResolutionEvidenceVerifiedAt());
        copyOrUnset(update, "decisionOutboxResolutionEvidenceVerifiedBy", record.getResolutionEvidenceVerifiedBy());
        copyOrUnset(update, "decisionOutboxResolutionEvidenceFingerprint", record.getResolutionEvidenceFingerprint());
        copyOrUnset(update, "decisionOutboxResolutionApprovalEvidenceType", record.getResolutionApprovalEvidenceType());
        copyOrUnset(update, "decisionOutboxResolutionApprovalEvidenceReference", record.getResolutionApprovalEvidenceReference());
        copyOrUnset(update, "decisionOutboxResolutionApprovalEvidenceVerifiedAt", record.getResolutionApprovalEvidenceVerifiedAt());
        copyOrUnset(update, "decisionOutboxResolutionApprovalEvidenceVerifiedBy", record.getResolutionApprovalEvidenceVerifiedBy());
        copyOrUnset(update, "decisionOutboxResolutionApprovalEvidenceFingerprint", record.getResolutionApprovalEvidenceFingerprint());
        copyOrUnset(update, "decisionOutboxResolutionApprovedAt", record.getResolutionApprovedAt());
        copyOrUnset(update, "decisionOutboxResolutionApprovedBy", record.getResolutionApprovedBy());
    }

    private static void copyOrUnset(Update update, String field, Object value) {
        if (value == null) {
            update.unset(field);
        } else {
            update.set(field, value);
        }
    }

    static TransactionalOutboxRecordDocument persistedRecord(Document source) {
        String status = string(source, "status");
        TransactionalOutboxStatus parsedStatus;
        try {
            parsedStatus = TransactionalOutboxStatus.valueOf(status);
        } catch (RuntimeException exception) {
            return null;
        }
        TransactionalOutboxRecordDocument record = new TransactionalOutboxRecordDocument();
        record.setEventId(string(source, "_id"));
        record.setStatus(parsedStatus);
        record.setAttempts(number(source.get("attempts"), 0));
        record.setProjectionRevision(number(source.get("projection_revision"), 0));
        record.setLastError(string(source, "last_error"));
        record.setPublishedAt(instant(source.get("published_at")));
        String provenance = string(source, "publication_confirmation_provenance");
        if (provenance != null) {
            try {
                record.setPublicationConfirmationProvenance(
                        OutboxPublicationConfirmationProvenance.valueOf(provenance)
                );
            } catch (IllegalArgumentException ignored) {
                // Schema validation reports unsupported provenance before semantic comparison.
            }
        }
        record.setResolutionPending(Boolean.TRUE.equals(source.get("resolution_pending")));
        record.setResolutionControlMode(string(source, "resolution_control_mode"));
        record.setResolutionRequestId(string(source, "resolution_request_id"));
        record.setResolutionProposedOutcome(string(source, "resolution_proposed_outcome"));
        record.setResolutionRequestedBy(string(source, "resolution_requested_by"));
        record.setResolutionRequestedAt(instant(source.get("resolution_requested_at")));
        record.setResolutionRequestReason(string(source, "resolution_request_reason"));
        record.setResolutionApprovalReason(string(source, "resolution_approval_reason"));
        record.setResolutionEvidenceType(string(source, "resolution_evidence_type"));
        record.setResolutionEvidenceReference(string(source, "resolution_evidence_reference"));
        record.setResolutionEvidenceVerifiedAt(instant(source.get("resolution_evidence_verified_at")));
        record.setResolutionEvidenceVerifiedBy(string(source, "resolution_evidence_verified_by"));
        record.setResolutionEvidenceFingerprint(string(source, "resolution_evidence_fingerprint"));
        record.setResolutionApprovalEvidenceType(string(source, "resolution_approval_evidence_type"));
        record.setResolutionApprovalEvidenceReference(string(source, "resolution_approval_evidence_reference"));
        record.setResolutionApprovalEvidenceVerifiedAt(
                instant(source.get("resolution_approval_evidence_verified_at"))
        );
        record.setResolutionApprovalEvidenceVerifiedBy(string(source, "resolution_approval_evidence_verified_by"));
        record.setResolutionApprovalEvidenceFingerprint(string(source, "resolution_approval_evidence_fingerprint"));
        record.setResolutionApprovedBy(string(source, "resolution_approved_by"));
        record.setResolutionApprovedAt(instant(source.get("resolution_approved_at")));
        return record;
    }

    private static String projectionMismatch(String field) {
        return switch (field) {
            case "decisionOutboxStatus" -> "ALERT_STATUS_DOES_NOT_MATCH_SOURCE";
            case "decisionOutboxPublishedAt" -> "ALERT_PUBLISHED_AT_DOES_NOT_MATCH_SOURCE";
            case "decisionOutboxPublicationConfirmationProvenance" ->
                    "ALERT_PUBLICATION_PROVENANCE_DOES_NOT_MATCH_SOURCE";
            default -> "ALERT_PROJECTION_SEMANTIC_MISMATCH_" + field;
        };
    }

    private static boolean persistedValueEquals(Object expected, Object actual) {
        Instant expectedInstant = instant(expected);
        Instant actualInstant = instant(actual);
        if (expectedInstant != null || actualInstant != null) {
            return expectedInstant != null && expectedInstant.equals(actualInstant);
        }
        if (expected instanceof Number expectedNumber && actual instanceof Number actualNumber) {
            return expectedNumber.longValue() == actualNumber.longValue();
        }
        return java.util.Objects.equals(expected, actual);
    }

    private static int number(Object value, int fallback) {
        return value instanceof Number number ? number.intValue() : fallback;
    }

    private static long number(Object value, long fallback) {
        return value instanceof Number number ? number.longValue() : fallback;
    }

    private static Instant instant(Object value) {
        if (value instanceof Instant instant) {
            return instant;
        }
        return value instanceof Date date ? date.toInstant() : null;
    }

    private static String string(Document document, String field) {
        Object value = document.get(field);
        return value instanceof String string ? string : null;
    }

    private static boolean evidenceFingerprintMatches(
            String type,
            String reference,
            Instant verifiedAt,
            String verifiedBy,
            String expectedFingerprint
    ) {
        if (!ResolutionEvidenceType.BROKER_OFFSET.name().equals(type)
                || !hasText(reference)
                || verifiedAt == null
                || !hasText(verifiedBy)
                || !hasText(expectedFingerprint)) {
            return false;
        }
        ResolutionEvidenceReference evidence = new ResolutionEvidenceReference(
                ResolutionEvidenceType.BROKER_OFFSET,
                reference,
                verifiedAt,
                verifiedBy
        );
        return RegulatedMutationIntentHasher.hash(evidence).equals(expectedFingerprint);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    public record Projection(
            TransactionalOutboxStatus sourceStatus,
            String eventId,
            long revision,
            String projectionStatus,
            Update update
    ) {
        public Query target(String resourceId) {
            return Query.query(new Criteria().andOperator(
                    Criteria.where("_id").is(resourceId),
                    Criteria.where("decisionOutboxEventId").is(eventId),
                    Criteria.where("decisionOutboxProjectionRevision").lte(revision)
            ));
        }
    }
}
