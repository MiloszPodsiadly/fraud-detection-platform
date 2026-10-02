package com.frauddetection.alert.outbox;

import com.frauddetection.alert.regulated.RegulatedMutationIntentHasher;
import com.mongodb.client.AggregateIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Aggregates;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Projections;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

@Component
public class TransactionalOutboxPersistedContractPreflight {

    static final String COLLECTION = "transactional_outbox_records";
    static final String ALERT_COLLECTION = "alerts";
    private static final String JOINED_ALERT = "_contract_alert_projection";
    private static final String JOINED_OUTBOX = "_contract_authoritative_outbox";
    private static final String STATUS_FIELD = "status";
    private static final Duration DEFAULT_STARTUP_VALIDATION_BUDGET = Duration.ofSeconds(30);
    private static final Set<String> CURRENT_STATUSES = enumNames(TransactionalOutboxStatus.values());
    private static final Set<String> TERMINAL_STATUSES = Set.of(
            TransactionalOutboxStatus.PUBLISHED.name(),
            TransactionalOutboxStatus.FAILED_TERMINAL.name(),
            TransactionalOutboxStatus.RECOVERY_REQUIRED.name()
    );
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
    private static final List<String> OUTBOX_INSPECTION_FIELDS = List.of(
            "_id", STATUS_FIELD, "resource_type", "resource_id", "attempts", "projection_revision",
            "projection_mismatch", "projection_reconcile_after", "publication_confirmation_provenance",
            "published_at", "last_error", "lease_owner", "lease_claim_token", "lease_expires_at",
            "resolution_reason", "resolution_pending", "resolution_control_mode", "resolution_request_id",
            "resolution_proposed_outcome", "resolution_requested_by", "resolution_requested_at",
            "resolution_request_reason", "resolution_approval_reason", "resolution_evidence_type",
            "resolution_evidence_reference", "resolution_evidence_verified_at", "resolution_evidence_verified_by",
            "resolution_evidence_fingerprint", "resolution_approval_evidence_type",
            "resolution_approval_evidence_reference", "resolution_approval_evidence_verified_at",
            "resolution_approval_evidence_verified_by", "resolution_approval_evidence_fingerprint",
            "resolution_approved_by", "resolution_approved_at", JOINED_ALERT
    );
    private static final List<String> ALERT_INSPECTION_FIELDS = List.of(
            "_id", "decisionOutboxEvent", "decisionOutboxEventId", "decisionOutboxProjectionRevision",
            "decisionOutboxStatus", "decisionOutboxAttempts", "decisionOutboxPublishedAt",
            "decisionOutboxPublicationConfirmationProvenance", "decisionOutboxLastError",
            "decisionOutboxFailureReason", "decisionOutboxLeaseOwner", "decisionOutboxLeaseExpiresAt",
            "decisionOutboxResolutionPending", "decisionOutboxResolutionRequestId",
            "decisionOutboxResolutionProposedOutcome", "decisionOutboxResolutionRequestedAt",
            "decisionOutboxResolutionRequestedBy", "decisionOutboxResolutionRequestReason",
            "decisionOutboxResolutionApprovalReason", "decisionOutboxResolutionEvidenceType",
            "decisionOutboxResolutionEvidenceReference", "decisionOutboxResolutionEvidenceVerifiedAt",
            "decisionOutboxResolutionEvidenceVerifiedBy", "decisionOutboxResolutionEvidenceFingerprint",
            "decisionOutboxResolutionApprovalEvidenceType", "decisionOutboxResolutionApprovalEvidenceReference",
            "decisionOutboxResolutionApprovalEvidenceVerifiedAt",
            "decisionOutboxResolutionApprovalEvidenceVerifiedBy",
            "decisionOutboxResolutionApprovalEvidenceFingerprint", "decisionOutboxResolutionApprovedAt",
            "decisionOutboxResolutionApprovedBy", JOINED_OUTBOX
    );

    private final MongoTemplate mongoTemplate;
    private final Duration startupValidationBudget;

    @Autowired
    public TransactionalOutboxPersistedContractPreflight(
            MongoTemplate mongoTemplate,
            @Value("${app.outbox.preflight.startup-validation-budget:PT30S}") Duration startupValidationBudget
    ) {
        this.mongoTemplate = mongoTemplate;
        if (startupValidationBudget == null || startupValidationBudget.isZero() || startupValidationBudget.isNegative()) {
            throw new IllegalArgumentException("Outbox preflight startup validation budget must be positive.");
        }
        this.startupValidationBudget = startupValidationBudget;
    }

    TransactionalOutboxPersistedContractPreflight(MongoTemplate mongoTemplate) {
        this(mongoTemplate, DEFAULT_STARTUP_VALIDATION_BUDGET);
    }

    public Report inspect(int sampleLimit) {
        long deadlineNanos = System.nanoTime() + startupValidationBudget.toNanos();
        Inspection inspection = new Inspection(sampleLimit, () -> requireBudgetRemaining(deadlineNanos));
        MongoCollection<Document> outbox = mongoTemplate.getCollection(COLLECTION);
        inspectAll(outbox.aggregate(List.of(
                        Aggregates.lookup(ALERT_COLLECTION, "resource_id", "_id", JOINED_ALERT),
                        Aggregates.project(Projections.include(OUTBOX_INSPECTION_FIELDS))
                )), inspection::inspectOutbox, deadlineNanos);

        MongoCollection<Document> alerts = mongoTemplate.getCollection(ALERT_COLLECTION);
        Bson hasOutboxProjection = Filters.or(
                Filters.exists("decisionOutboxEvent", true),
                Filters.exists("decisionOutboxEventId", true),
                Filters.exists("decisionOutboxProjectionRevision", true),
                Filters.exists("decisionOutboxStatus", true)
        );
        inspectAll(alerts.aggregate(List.of(
                        Aggregates.match(hasOutboxProjection),
                        Aggregates.lookup(COLLECTION, "decisionOutboxEventId", "_id", JOINED_OUTBOX),
                        Aggregates.project(Projections.include(ALERT_INSPECTION_FIELDS))
                )), inspection::inspectOrphanAlert, deadlineNanos);
        return inspection.report();
    }

    private void inspectAll(
            AggregateIterable<Document> aggregation,
            Consumer<Document> inspector,
            long deadlineNanos
    ) {
        aggregation.allowDiskUse(true)
                .maxTime(remainingBudgetMillis(deadlineNanos), TimeUnit.MILLISECONDS)
                .forEach(inspector);
        requireBudgetRemaining(deadlineNanos);
    }

    private long remainingBudgetMillis(long deadlineNanos) {
        long remainingNanos = requireBudgetRemaining(deadlineNanos);
        long millis = TimeUnit.NANOSECONDS.toMillis(remainingNanos);
        return Math.max(1L, millis);
    }

    private long requireBudgetRemaining(long deadlineNanos) {
        if (Thread.currentThread().isInterrupted()) {
            throw new IllegalStateException("Transactional outbox persisted-contract startup validation interrupted.");
        }
        long remainingNanos = deadlineNanos - System.nanoTime();
        if (remainingNanos <= 0L) {
            throw new IllegalStateException(
                    "Transactional outbox persisted-contract startup validation budget exceeded: "
                            + startupValidationBudget
            );
        }
        return remainingNanos;
    }

    Report inspectRawDocuments(List<Document> outboxRecords, List<Document> alertRecords, int sampleLimit) {
        Map<String, Document> alertsById = indexById(alertRecords);
        Map<String, Document> outboxById = indexById(outboxRecords);
        Inspection inspection = new Inspection(sampleLimit);
        for (Document source : outboxRecords) {
            Document joined = new Document(source);
            Document alert = alertsById.get(safeString(source.get("resource_id")));
            joined.put(JOINED_ALERT, alert == null ? List.of() : List.of(alert));
            inspection.inspectOutbox(joined);
        }
        for (Document projection : alertRecords) {
            if (!hasAnyOutboxProjectionField(projection)) {
                continue;
            }
            Document joined = new Document(projection);
            Document source = outboxById.get(safeString(projection.get("decisionOutboxEventId")));
            joined.put(JOINED_OUTBOX, source == null ? List.of() : List.of(source));
            inspection.inspectOrphanAlert(joined);
        }
        return inspection.report();
    }

    private static Map<String, Document> indexById(List<Document> documents) {
        Map<String, Document> byId = new HashMap<>();
        for (Document document : documents) {
            String id = safeString(document.get("_id"));
            if (id != null) {
                byId.put(id, document);
            }
        }
        return byId;
    }

    private static List<String> outboxViolations(Document document) {
        List<String> violations = new ArrayList<>();
        String status = safeString(document.get(STATUS_FIELD));
        if (status == null || !CURRENT_STATUSES.contains(status)) {
            violations.add("STATUS_UNSUPPORTED");
        }
        if (!validRevision(document.get("projection_revision"))) {
            violations.add("PROJECTION_REVISION_MISSING_OR_INVALID");
        }
        if (!validAttempts(document.get("attempts"))) {
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
        String provenance = safeString(document.get("publication_confirmation_provenance"));
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
        String controlMode = safeString(document.get("resolution_control_mode"));
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
            String expectedOutcome = TransactionalOutboxStatus.PUBLISHED.name().equals(status)
                    ? "PUBLISHED"
                    : TransactionalOutboxStatus.RECOVERY_REQUIRED.name().equals(status)
                    ? "RECOVERY_REQUIRED"
                    : null;
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
        String expectedOutcome = TransactionalOutboxStatus.PUBLISHED.name().equals(status)
                ? "PUBLISHED"
                : TransactionalOutboxStatus.RECOVERY_REQUIRED.name().equals(status)
                ? "RECOVERY_REQUIRED"
                : null;
        TransactionalOutboxRecordDocument record = OutboxAlertProjectionPolicy.persistedRecord(document);
        if (expectedOutcome == null) {
            violations.add("SINGLE_CONTROL_STATUS_INVALID");
        } else if (record == null
                || !OutboxAlertProjectionPolicy.validSingleControlEvidence(record, expectedOutcome)) {
            violations.add("SINGLE_CONTROL_SEMANTICS_INVALID");
        }
    }

    private static void requireRequestFields(Document document, List<String> violations) {
        requireFields(document, REQUEST_FIELDS, "PENDING_INTENT_INCOMPLETE", violations);
        String outcome = safeString(document.get("resolution_proposed_outcome"));
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

    private static List<String> alertViolations(Document alert) {
        List<String> violations = new ArrayList<>();
        if (!hasText(alert.get("decisionOutboxEventId"))) {
            violations.add("ALERT_EVENT_ID_MISSING_OR_INVALID");
        }
        if (!validRevision(alert.get("decisionOutboxProjectionRevision"))) {
            violations.add("ALERT_PROJECTION_REVISION_MISSING_OR_INVALID");
        }
        String status = safeString(alert.get("decisionOutboxStatus"));
        if (status == null || !ALERT_PROJECTION_STATUSES.contains(status)) {
            violations.add("ALERT_PROJECTION_STATUS_MISSING_OR_INVALID");
        }
        String provenance = safeString(alert.get("decisionOutboxPublicationConfirmationProvenance"));
        if ("PUBLISHED".equals(status)
                && (provenance == null || !PUBLICATION_PROVENANCE.contains(provenance))) {
            violations.add("ALERT_PUBLISHED_PROVENANCE_MISSING_OR_INVALID");
        }
        return violations;
    }

    private static List<String> relationshipViolations(Document source, Document alert) {
        List<String> violations = new ArrayList<>();
        if (!safeEquals(source.get("_id"), alert.get("decisionOutboxEventId"))) {
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
        if (!safeEquals(source.get("resource_id"), alert.get("_id"))) {
            violations.add("ALERT_RESOURCE_ID_DOES_NOT_MATCH_SOURCE");
        }
        boolean reconciliationOutstanding = Boolean.TRUE.equals(source.get("projection_mismatch"))
                || validInstant(source.get("projection_reconcile_after"));
        if (sourceRevision >= 0 && sourceRevision == alertRevision && !reconciliationOutstanding) {
            violations.addAll(OutboxAlertProjectionPolicy.persistedProjectionViolations(source, alert));
        }
        return violations;
    }

    private static boolean hasAny(Document document, List<String> fields) {
        return fields.stream().anyMatch(document::containsKey);
    }

    private static boolean hasAnyOutboxProjectionField(Document document) {
        return document.containsKey("decisionOutboxEvent")
                || document.containsKey("decisionOutboxEventId")
                || document.containsKey("decisionOutboxProjectionRevision")
                || document.containsKey("decisionOutboxStatus");
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

    private static boolean validAttempts(Object value) {
        return value instanceof Integer integer && integer >= 0
                || value instanceof Long longValue && longValue >= 0 && longValue <= Integer.MAX_VALUE;
    }

    private static long revision(Object value) {
        return value instanceof Integer integer && integer >= 0
                ? integer.longValue()
                : value instanceof Long longValue && longValue >= 0 ? longValue : -1L;
    }

    private static boolean safeEquals(Object left, Object right) {
        String leftValue = safeString(left);
        return leftValue != null && leftValue.equals(safeString(right));
    }

    private static String safeString(Object value) {
        return value instanceof String string && !string.isBlank() ? string : null;
    }

    private static Set<String> enumNames(Enum<?>[] values) {
        Set<String> names = new HashSet<>();
        for (Enum<?> value : values) {
            names.add(value.name());
        }
        return Set.copyOf(names);
    }

    private static final class Inspection {
        private final int sampleLimit;
        private final Runnable budgetCheck;
        private final List<UnsupportedPersistedRecord> samples = new ArrayList<>();
        private long unsupportedUnfinished;
        private long unsupportedTerminal;
        private long unsupportedAlertProjection;

        private Inspection(int sampleLimit) {
            this(sampleLimit, () -> { });
        }

        private Inspection(int sampleLimit, Runnable budgetCheck) {
            this.sampleLimit = Math.max(0, Math.min(sampleLimit, 100));
            this.budgetCheck = budgetCheck;
        }

        private void inspectOutbox(Document source) {
            budgetCheck.run();
            List<String> violations = outboxViolations(source);
            List<Document> linkedAlerts = documents(source.get(JOINED_ALERT));
            if (!"ALERT".equals(safeString(source.get("resource_type")))
                    || !hasText(source.get("resource_id"))) {
                violations.add("ALERT_RESOURCE_LINK_MISSING_OR_INVALID");
            } else if (linkedAlerts.isEmpty()) {
                violations.add("ALERT_PROJECTION_MISSING");
            } else {
                Document alert = linkedAlerts.getFirst();
                violations.addAll(alertViolations(alert));
                violations.addAll(relationshipViolations(source, alert));
            }
            if (violations.isEmpty()) {
                return;
            }
            String status = safeString(source.get(STATUS_FIELD));
            if (status != null && TERMINAL_STATUSES.contains(status)) {
                unsupportedTerminal++;
            } else {
                unsupportedUnfinished++;
            }
            sample("OUTBOX", source.get("_id"), status, violations);
        }

        private void inspectOrphanAlert(Document alert) {
            budgetCheck.run();
            if (!documents(alert.get(JOINED_OUTBOX)).isEmpty()) {
                return;
            }
            List<String> violations = alertViolations(alert);
            violations.add("AUTHORITATIVE_OUTBOX_MISSING");
            unsupportedAlertProjection++;
            sample("ALERT_PROJECTION", alert.get("_id"), safeString(alert.get("decisionOutboxStatus")), violations);
        }

        private void sample(String source, Object id, String status, List<String> violations) {
            if (samples.size() >= sampleLimit) {
                return;
            }
            samples.add(new UnsupportedPersistedRecord(
                    source,
                    RegulatedMutationIntentHasher.hash(String.valueOf(id)),
                    status == null ? "UNKNOWN" : status,
                    List.copyOf(new LinkedHashSet<>(violations))
            ));
        }

        private Report report() {
            return new Report(
                    unsupportedUnfinished,
                    unsupportedTerminal,
                    unsupportedAlertProjection,
                    List.copyOf(samples)
            );
        }
    }

    private static List<Document> documents(Object value) {
        if (!(value instanceof List<?> values)) {
            return List.of();
        }
        return values.stream().filter(Document.class::isInstance).map(Document.class::cast).toList();
    }

    public record Report(
            long unsupportedUnfinishedCount,
            long unsupportedTerminalCount,
            long unsupportedAlertProjectionCount,
            List<UnsupportedPersistedRecord> samples
    ) {
        public boolean blocksStartup() {
            return unsupportedUnfinishedCount + unsupportedTerminalCount + unsupportedAlertProjectionCount > 0;
        }
    }

    public record UnsupportedPersistedRecord(
            String source,
            String recordIdHash,
            String status,
            List<String> violations
    ) {
    }
}
