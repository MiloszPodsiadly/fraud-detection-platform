package com.frauddetection.alert.outbox;

import com.frauddetection.alert.service.DecisionOutboxStatus;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.time.Instant;

public final class OutboxAlertProjectionPolicy {

    private static final String MANUAL_RECOVERY_REQUIRED = "MANUAL_RECOVERY_REQUIRED";

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
