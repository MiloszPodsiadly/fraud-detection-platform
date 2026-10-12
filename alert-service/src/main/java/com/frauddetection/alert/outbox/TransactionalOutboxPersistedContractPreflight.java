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
import java.util.ArrayList;
import java.util.HashMap;
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
    private static final Set<String> TERMINAL_STATUSES = Set.of(
            TransactionalOutboxStatus.PUBLISHED.name(),
            TransactionalOutboxStatus.FAILED_TERMINAL.name(),
            TransactionalOutboxStatus.RECOVERY_REQUIRED.name()
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
    private static final List<String> ALERT_PROJECTION_MARKER_FIELDS = List.of(
            "decisionOutboxEvent", "decisionOutboxEventId", "decisionOutboxStatus",
            "decisionOutboxPublishedAt", "decisionOutboxPublicationConfirmationProvenance",
            "decisionOutboxLastError", "decisionOutboxFailureReason", "decisionOutboxLeaseOwner",
            "decisionOutboxLeaseExpiresAt", "decisionOutboxResolutionRequestId",
            "decisionOutboxResolutionProposedOutcome", "decisionOutboxResolutionRequestedAt",
            "decisionOutboxResolutionRequestedBy", "decisionOutboxResolutionRequestReason",
            "decisionOutboxResolutionApprovalReason", "decisionOutboxResolutionEvidenceType",
            "decisionOutboxResolutionEvidenceReference", "decisionOutboxResolutionEvidenceVerifiedAt",
            "decisionOutboxResolutionEvidenceVerifiedBy", "decisionOutboxResolutionEvidenceFingerprint",
            "decisionOutboxResolutionApprovalEvidenceType", "decisionOutboxResolutionApprovalEvidenceReference",
            "decisionOutboxResolutionApprovalEvidenceVerifiedAt",
            "decisionOutboxResolutionApprovalEvidenceVerifiedBy",
            "decisionOutboxResolutionApprovalEvidenceFingerprint", "decisionOutboxResolutionApprovedAt",
            "decisionOutboxResolutionApprovedBy"
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
        Bson hasOutboxProjection = hasOutboxProjectionFilter();
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

    private static boolean hasAnyOutboxProjectionField(Document document) {
        return ALERT_PROJECTION_MARKER_FIELDS.stream().anyMatch(document::containsKey)
                || hasNonDefaultValue(document, "decisionOutboxProjectionRevision", 0L)
                || hasNonDefaultValue(document, "decisionOutboxAttempts", 0)
                || hasNonDefaultValue(document, "decisionOutboxResolutionPending", false);
    }

    private static Bson hasOutboxProjectionFilter() {
        List<Bson> markers = new ArrayList<>();
        ALERT_PROJECTION_MARKER_FIELDS.forEach(field -> markers.add(Filters.exists(field, true)));
        markers.add(nonDefaultField("decisionOutboxProjectionRevision", 0L));
        markers.add(nonDefaultField("decisionOutboxAttempts", 0));
        markers.add(nonDefaultField("decisionOutboxResolutionPending", false));
        return Filters.or(markers);
    }

    private static Bson nonDefaultField(String field, Object defaultValue) {
        return Filters.and(Filters.exists(field, true), Filters.ne(field, defaultValue));
    }

    private static boolean hasNonDefaultValue(Document document, String field, Object defaultValue) {
        return document.containsKey(field) && !java.util.Objects.equals(document.get(field), defaultValue);
    }

    private static boolean hasText(Object value) {
        return value instanceof String string && !string.isBlank();
    }

    private static String safeString(Object value) {
        return value instanceof String string && !string.isBlank() ? string : null;
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
            List<String> violations = TransactionalOutboxPersistedDocumentValidator.outboxViolations(source);
            List<Document> linkedAlerts = documents(source.get(JOINED_ALERT));
            if (!"ALERT".equals(safeString(source.get("resource_type")))
                    || !hasText(source.get("resource_id"))) {
                violations.add("ALERT_RESOURCE_LINK_MISSING_OR_INVALID");
            } else if (linkedAlerts.isEmpty()) {
                violations.add("ALERT_PROJECTION_MISSING");
            } else {
                Document alert = linkedAlerts.getFirst();
                violations.addAll(TransactionalOutboxPersistedDocumentValidator.alertViolations(alert));
                violations.addAll(TransactionalOutboxPersistedDocumentValidator.relationshipViolations(source, alert));
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
            List<String> violations = TransactionalOutboxPersistedDocumentValidator.alertViolations(alert);
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
