package com.frauddetection.alert.outbox;

import org.bson.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class TransactionalOutboxPersistedDocumentValidator {

    private static final Set<String> CURRENT_STATUSES = enumNames(TransactionalOutboxStatus.values());
    private static final Set<String> PUBLICATION_PROVENANCE = enumNames(
            OutboxPublicationConfirmationProvenance.values()
    );
    private static final Set<String> RESOLUTION_OUTCOMES = Set.of("PUBLISHED", "RECOVERY_REQUIRED");
    private static final Set<String> RESOLUTION_CONTROL_MODES = Set.of(
            "DUAL_CONTROL_REQUESTED",
            "DUAL_CONTROL_APPROVED",
            "SINGLE_CONTROL_OPERATOR_ATTESTED"
    );
    private static final Set<String> ALERT_PROJECTION_STATUSES = Set.of(
            "PENDING",
            "PROCESSING",
            "PUBLISHED",
            "PUBLISH_CONFIRMATION_UNKNOWN",
            "FAILED_RETRYABLE",
            "FAILED_TERMINAL"
    );
    private static final List<String> REQUEST_FIELDS = List.of(
            "resolution_request_id",
            "resolution_proposed_outcome",
            "resolution_requested_by",
            "resolution_requested_at",
            "resolution_request_reason",
            "resolution_evidence_type",
            "resolution_evidence_reference",
            "resolution_evidence_verified_at",
            "resolution_evidence_verified_by",
            "resolution_evidence_fingerprint"
    );
    private static final List<String> APPROVAL_FIELDS = List.of(
            "resolution_approval_reason",
            "resolution_approved_by",
            "resolution_approved_at",
            "resolution_approval_evidence_type",
            "resolution_approval_evidence_reference",
            "resolution_approval_evidence_verified_at",
            "resolution_approval_evidence_verified_by",
            "resolution_approval_evidence_fingerprint"
    );
    private static final List<String> PRIMARY_EVIDENCE_FIELDS = List.of(
            "resolution_evidence_type",
            "resolution_evidence_reference",
            "resolution_evidence_verified_at",
            "resolution_evidence_verified_by",
            "resolution_evidence_fingerprint"
    );

    private TransactionalOutboxPersistedDocumentValidator() {
    }

    static List<String> outboxViolations(Document document) {
        List<String> violations = new ArrayList<>();
        String status = text(document.get("status"));
        if (status == null || !CURRENT_STATUSES.contains(status)) {
            violations.add("STATUS_UNSUPPORTED");
        }
        if (!validRevision(document.get("projection_revision"))) {
            violations.add("PROJECTION_REVISION_MISSING_OR_INVALID");
        }
        if (!OutboxAlertProjectionPolicy.validBoundedAttempts(document.get("attempts"))) {
            violations.add("ATTEMPTS_MISSING_OR_INVALID");
        }
        validateLeaseState(document, status, violations);
        if (document.containsKey("resolution_reason")) {
            violations.add("RETIRED_RESOLUTION_REASON_PRESENT");
        }
        validatePublicationProvenance(document, status, violations);
        validateResolutionState(document, status, violations);
        return violations;
    }

    static List<String> alertViolations(Document alert) {
        List<String> violations = new ArrayList<>();
        if (!hasText(alert.get("decisionOutboxEventId"))) {
            violations.add("ALERT_EVENT_ID_MISSING_OR_INVALID");
        }
        if (!validRevision(alert.get("decisionOutboxProjectionRevision"))) {
            violations.add("ALERT_PROJECTION_REVISION_MISSING_OR_INVALID");
        }
        if (!OutboxAlertProjectionPolicy.validBoundedAttempts(alert.get("decisionOutboxAttempts"))) {
            violations.add("ALERT_PROJECTION_SEMANTIC_MISMATCH_decisionOutboxAttempts");
        }
        String status = text(alert.get("decisionOutboxStatus"));
        if (status == null || !ALERT_PROJECTION_STATUSES.contains(status)) {
            violations.add("ALERT_PROJECTION_STATUS_MISSING_OR_INVALID");
        }
        String provenance = text(alert.get("decisionOutboxPublicationConfirmationProvenance"));
        if ("PUBLISHED".equals(status)
                && (provenance == null || !PUBLICATION_PROVENANCE.contains(provenance))) {
            violations.add("ALERT_PUBLISHED_PROVENANCE_MISSING_OR_INVALID");
        }
        return violations;
    }

    static List<String> relationshipViolations(Document source, Document alert) {
        List<String> violations = new ArrayList<>();
        if (!sameText(source.get("_id"), alert.get("decisionOutboxEventId"))) {
            violations.add("ALERT_EVENT_ID_DOES_NOT_MATCH_SOURCE");
        }
        long sourceRevision = revision(source.get("projection_revision"));
        long alertRevision = revision(alert.get("decisionOutboxProjectionRevision"));
        if (sourceRevision >= 0 && alertRevision > sourceRevision) {
            violations.add("ALERT_PROJECTION_NEWER_THAN_SOURCE");
        } else if (sourceRevision >= 0 && alertRevision >= 0 && alertRevision < sourceRevision
                && !Boolean.TRUE.equals(source.get("projection_mismatch"))
                && !validInstant(source.get("projection_reconcile_after"))) {
            violations.add("STALE_ALERT_PROJECTION_NOT_SCHEDULED");
        }
        if (!sameText(source.get("resource_id"), alert.get("_id"))) {
            violations.add("ALERT_RESOURCE_ID_DOES_NOT_MATCH_SOURCE");
        }
        violations.addAll(OutboxAlertProjectionPolicy.persistedProjectionViolations(source, alert));
        return violations;
    }

    private static void validateLeaseState(Document document, String status, List<String> violations) {
        boolean activeClaim = TransactionalOutboxStatus.PROCESSING.name().equals(status)
                || TransactionalOutboxStatus.PUBLISH_ATTEMPTED.name().equals(status);
        if (activeClaim) {
            if (!hasText(document.get("lease_owner"))) {
                violations.add("ACTIVE_CLAIM_LEASE_OWNER_MISSING_OR_INVALID");
            }
            if (!hasText(document.get("lease_claim_token"))) {
                violations.add("ACTIVE_CLAIM_TOKEN_MISSING_OR_INVALID");
            }
            if (!validInstant(document.get("lease_expires_at"))) {
                violations.add("ACTIVE_CLAIM_EXPIRY_MISSING_OR_INVALID");
            }
            return;
        }
        if (document.get("lease_owner") != null
                || document.get("lease_claim_token") != null
                || document.get("lease_expires_at") != null) {
            violations.add("LEASE_STATE_PRESENT_WITHOUT_ACTIVE_CLAIM");
        }
    }

    private static void validatePublicationProvenance(
            Document document,
            String status,
            List<String> violations
    ) {
        String provenance = text(document.get("publication_confirmation_provenance"));
        if (TransactionalOutboxStatus.PUBLISHED.name().equals(status)) {
            if (!validInstant(document.get("published_at"))) {
                violations.add("PUBLISHED_AT_MISSING_OR_INVALID");
            }
            if (provenance == null || !PUBLICATION_PROVENANCE.contains(provenance)) {
                violations.add("PUBLISHED_PROVENANCE_MISSING_OR_INVALID");
            }
            TransactionalOutboxRecordDocument record = OutboxAlertProjectionPolicy.persistedRecord(document);
            if (OutboxPublicationConfirmationProvenance.BROKER_ACKNOWLEDGED.name().equals(provenance)
                    && record != null
                    && !OutboxAlertProjectionPolicy.brokerPublicationHasNoManualMetadata(record)) {
                violations.add("BROKER_PROVENANCE_HAS_MANUAL_METADATA");
            }
            if (OutboxPublicationConfirmationProvenance.MANUAL_SINGLE_CONTROL_ATTESTED.name().equals(provenance)
                    && (record == null
                    || !OutboxAlertProjectionPolicy.validSingleControlPublicationEvidence(record))) {
                violations.add("MANUAL_SINGLE_PROVENANCE_CONTROL_MISMATCH");
            }
            if (OutboxPublicationConfirmationProvenance.MANUAL_DUAL_CONTROL_ATTESTED.name().equals(provenance)
                    && (record == null
                    || !OutboxAlertProjectionPolicy.validDualControlPublicationEvidence(record))) {
                violations.add("MANUAL_DUAL_PROVENANCE_CONTROL_MISMATCH");
            }
        } else {
            if (provenance != null) {
                violations.add("NON_PUBLISHED_PROVENANCE_PRESENT");
            }
            if (document.containsKey("published_at")) {
                violations.add("NON_PUBLISHED_AT_PRESENT");
            }
        }
    }

    private static void validateResolutionState(Document document, String status, List<String> violations) {
        Object pendingValue = document.get("resolution_pending");
        if (pendingValue != null && !(pendingValue instanceof Boolean)) {
            violations.add("RESOLUTION_PENDING_INVALID");
            return;
        }
        boolean pending = Boolean.TRUE.equals(pendingValue);
        String controlMode = text(document.get("resolution_control_mode"));
        if (controlMode != null && !RESOLUTION_CONTROL_MODES.contains(controlMode)) {
            violations.add("RESOLUTION_CONTROL_MODE_UNSUPPORTED");
            return;
        }
        if (pending) {
            if (!TransactionalOutboxStatus.PUBLISH_CONFIRMATION_UNKNOWN.name().equals(status)) {
                violations.add("PENDING_RESOLUTION_STATUS_INVALID");
            }
            if (!"DUAL_CONTROL_REQUESTED".equals(controlMode)) {
                violations.add("PENDING_RESOLUTION_CONTROL_MODE_INVALID");
            }
            requireRequestFields(document, violations);
            if (hasAny(document, APPROVAL_FIELDS)) {
                violations.add("PENDING_RESOLUTION_HAS_APPROVAL_METADATA");
            }
            TransactionalOutboxRecordDocument record = OutboxAlertProjectionPolicy.persistedRecord(document);
            if (record != null && !OutboxAlertProjectionPolicy.validPendingDualControlEvidence(record)) {
                violations.add("PENDING_RESOLUTION_EVIDENCE_INVALID");
            }
            return;
        }
        if (controlMode == null) {
            if (hasAny(document, REQUEST_FIELDS) || hasAny(document, APPROVAL_FIELDS)) {
                violations.add("RESOLUTION_METADATA_WITHOUT_CONTROL_MODE");
            }
            if (TransactionalOutboxStatus.RECOVERY_REQUIRED.name().equals(status)) {
                violations.add("RECOVERY_REQUIRED_PROVENANCE_MISSING");
            }
            return;
        }
        if ("DUAL_CONTROL_REQUESTED".equals(controlMode)) {
            violations.add("DUAL_CONTROL_REQUEST_NOT_PENDING");
            return;
        }
        if ("DUAL_CONTROL_APPROVED".equals(controlMode)) {
            requireRequestFields(document, violations);
            requireFields(document, APPROVAL_FIELDS, "DUAL_CONTROL_APPROVAL_INCOMPLETE", violations);
            String expectedOutcome = expectedOutcome(status);
            TransactionalOutboxRecordDocument record = OutboxAlertProjectionPolicy.persistedRecord(document);
            if (expectedOutcome == null
                    || record == null
                    || !OutboxAlertProjectionPolicy.validDualControlEvidence(record, expectedOutcome)) {
                violations.add("DUAL_CONTROL_SEMANTICS_INVALID");
            }
            return;
        }
        requireFields(document, PRIMARY_EVIDENCE_FIELDS, "SINGLE_CONTROL_EVIDENCE_INCOMPLETE", violations);
        requireFields(
                document,
                List.of("resolution_approval_reason", "resolution_approved_by", "resolution_approved_at"),
                "SINGLE_CONTROL_APPROVAL_INCOMPLETE",
                violations
        );
        String expectedOutcome = expectedOutcome(status);
        TransactionalOutboxRecordDocument record = OutboxAlertProjectionPolicy.persistedRecord(document);
        if (expectedOutcome == null) {
            violations.add("SINGLE_CONTROL_STATUS_INVALID");
        } else if (record == null
                || !OutboxAlertProjectionPolicy.validSingleControlEvidence(record, expectedOutcome)) {
            violations.add("SINGLE_CONTROL_SEMANTICS_INVALID");
        }
    }

    private static String expectedOutcome(String status) {
        if (TransactionalOutboxStatus.PUBLISHED.name().equals(status)) {
            return "PUBLISHED";
        }
        if (TransactionalOutboxStatus.RECOVERY_REQUIRED.name().equals(status)) {
            return "RECOVERY_REQUIRED";
        }
        return null;
    }

    private static void requireRequestFields(Document document, List<String> violations) {
        requireFields(document, REQUEST_FIELDS, "PENDING_INTENT_INCOMPLETE", violations);
        String outcome = text(document.get("resolution_proposed_outcome"));
        if (outcome != null && !RESOLUTION_OUTCOMES.contains(outcome)) {
            violations.add("RESOLUTION_OUTCOME_UNSUPPORTED");
        }
    }

    private static void requireFields(
            Document document,
            List<String> fields,
            String category,
            List<String> violations
    ) {
        for (String field : fields) {
            Object value = document.get(field);
            boolean valid = field.endsWith("_at") ? validInstant(value) : hasText(value);
            if (!valid) {
                violations.add(category);
                return;
            }
        }
    }

    private static boolean hasAny(Document document, List<String> fields) {
        return fields.stream().anyMatch(document::containsKey);
    }

    private static boolean hasText(Object value) {
        return value instanceof String string && !string.isBlank();
    }

    private static boolean validInstant(Object value) {
        return value instanceof Instant || value instanceof Date;
    }

    private static boolean validRevision(Object value) {
        return revision(value) >= 0;
    }

    private static long revision(Object value) {
        return value instanceof Integer integer && integer >= 0
                ? integer.longValue()
                : value instanceof Long longValue && longValue >= 0 ? longValue : -1L;
    }

    private static boolean sameText(Object left, Object right) {
        String leftValue = text(left);
        return leftValue != null && leftValue.equals(text(right));
    }

    private static String text(Object value) {
        return value instanceof String string && !string.isBlank() ? string : null;
    }

    private static Set<String> enumNames(Enum<?>[] values) {
        Set<String> names = new HashSet<>();
        for (Enum<?> value : values) {
            names.add(value.name());
        }
        return Set.copyOf(names);
    }
}
