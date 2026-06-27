package com.frauddetection.alert.audit.outbox;

import com.frauddetection.alert.audit.AuditAction;
import com.frauddetection.alert.audit.AuditEventMetadataSummary;
import com.frauddetection.alert.audit.AuditOutcome;
import com.frauddetection.alert.audit.AuditResourceType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

@Service
public class WriteActionAuditOutboxService {

    static final String CONTRACT_VERSION = "write-action-audit-outbox-v1";
    static final int DEFAULT_MAX_ATTEMPTS = 5;
    static final String METADATA_UNSAFE = "WRITE_ACTION_AUDIT_OUTBOX_METADATA_UNSAFE";
    static final String IDEMPOTENCY_CONFLICT = "WRITE_ACTION_AUDIT_OUTBOX_IDEMPOTENCY_CONFLICT";

    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 180;
    private static final int MAX_RESOURCE_ID_LENGTH = 160;
    private static final int MAX_CORRELATION_ID_LENGTH = 120;
    private static final int MAX_ACTOR_LENGTH = 120;
    private static final List<String> UNSAFE_TERMS = List.of(
            "notes",
            "raw" + "customer" + "payload",
            "raw" + "transaction" + "payload",
            "raw" + "ml" + "request",
            "raw" + "ml" + "response",
            "raw" + "feature" + "vector",
            "raw" + "evidence",
            "token",
            "secret",
            "password",
            "stack" + "trace",
            "exception" + "message",
            "payment" + "authorization",
            "payment" + "decision",
            "final" + "decision",
            "ground" + "truth",
            "training" + "label"
    );

    private final WriteActionAuditOutboxRepository repository;
    private final Clock clock;

    @Autowired
    public WriteActionAuditOutboxService(WriteActionAuditOutboxRepository repository) {
        this(repository, Clock.systemUTC());
    }

    WriteActionAuditOutboxService(WriteActionAuditOutboxRepository repository, Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository is required");
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    public WriteActionAuditOutboxRecord createPendingAudit(
            String idempotencyKey,
            AuditAction action,
            AuditResourceType resourceType,
            String resourceId,
            String correlationId,
            String actor,
            AuditOutcome outcome,
            AuditEventMetadataSummary metadataSummary
    ) {
        String boundedIdempotencyKey = required(idempotencyKey, "idempotencyKey", MAX_IDEMPOTENCY_KEY_LENGTH);
        AuditAction requiredAction = Objects.requireNonNull(action, "action is required");
        AuditResourceType requiredResourceType = Objects.requireNonNull(resourceType, "resourceType is required");
        String boundedResourceId = optional(resourceId, MAX_RESOURCE_ID_LENGTH);
        String boundedCorrelationId = optional(correlationId, MAX_CORRELATION_ID_LENGTH);
        String boundedActor = optional(actor, MAX_ACTOR_LENGTH);
        AuditOutcome requiredOutcome = Objects.requireNonNull(outcome, "outcome is required");
        validateMetadata(metadataSummary);
        return repository.findByIdempotencyKey(boundedIdempotencyKey)
                .map(existing -> validateExistingIntent(
                        existing,
                        requiredAction,
                        requiredResourceType,
                        boundedResourceId,
                        boundedCorrelationId,
                        boundedActor,
                        requiredOutcome
                ))
                .orElseGet(() -> saveNew(
                        boundedIdempotencyKey,
                        requiredAction,
                        requiredResourceType,
                        boundedResourceId,
                        boundedCorrelationId,
                        boundedActor,
                        requiredOutcome,
                        metadataSummary
                ));
    }

    private WriteActionAuditOutboxRecord saveNew(
            String idempotencyKey,
            AuditAction action,
            AuditResourceType resourceType,
            String resourceId,
            String correlationId,
            String actor,
            AuditOutcome outcome,
            AuditEventMetadataSummary metadataSummary
    ) {
        WriteActionAuditOutboxRecord record = new WriteActionAuditOutboxRecord();
        record.setOutboxId("wao-" + UUID.randomUUID());
        record.setIdempotencyKey(idempotencyKey);
        record.setContractVersion(CONTRACT_VERSION);
        record.setAction(action);
        record.setResourceType(resourceType);
        record.setResourceId(resourceId);
        record.setCorrelationId(correlationId);
        record.setActor(actor);
        record.setOutcome(outcome);
        record.setMetadataSummary(metadataSummary);
        record.setStatus(WriteActionAuditOutboxStatus.PENDING);
        record.setAttemptCount(0);
        record.setMaxAttempts(DEFAULT_MAX_ATTEMPTS);
        record.setCreatedAt(clock.instant());
        try {
            return repository.save(record);
        } catch (DuplicateKeyException exception) {
            return repository.findByIdempotencyKey(idempotencyKey)
                    .map(existing -> validateExistingIntent(
                            existing,
                            action,
                            resourceType,
                            resourceId,
                            correlationId,
                            actor,
                            outcome
                    ))
                    .orElseThrow(() -> new WriteActionAuditOutboxException("WRITE_ACTION_AUDIT_OUTBOX_DUPLICATE_UNAVAILABLE", exception));
        }
    }

    private WriteActionAuditOutboxRecord validateExistingIntent(
            WriteActionAuditOutboxRecord existing,
            AuditAction action,
            AuditResourceType resourceType,
            String resourceId,
            String correlationId,
            String actor,
            AuditOutcome outcome
    ) {
        if (existing.getAction() != action
                || existing.getResourceType() != resourceType
                || !Objects.equals(existing.getResourceId(), resourceId)
                || !Objects.equals(existing.getCorrelationId(), correlationId)
                || !Objects.equals(existing.getActor(), actor)
                || existing.getOutcome() != outcome) {
            throw new WriteActionAuditOutboxException(IDEMPOTENCY_CONFLICT);
        }
        return existing;
    }

    private void validateMetadata(AuditEventMetadataSummary metadataSummary) {
        if (metadataSummary == null) {
            return;
        }
        List<String> values = List.of(
                string(metadataSummary.correlationId()),
                string(metadataSummary.requestId()),
                string(metadataSummary.sourceService()),
                string(metadataSummary.schemaVersion()),
                string(metadataSummary.failureCategory()),
                string(metadataSummary.failureReason()),
                string(metadataSummary.endpointAction()),
                string(metadataSummary.filtersSummary()),
                string(metadataSummary.exportStatus()),
                string(metadataSummary.reasonCode()),
                string(metadataSummary.externalAnchorStatus()),
                string(metadataSummary.exportFingerprint()),
                string(metadataSummary.trustLevel()),
                string(metadataSummary.internalIntegrityStatus()),
                string(metadataSummary.externalIntegrityStatus()),
                string(metadataSummary.attestationFingerprint())
        );
        for (String value : values) {
            rejectUnsafe(value);
        }
    }

    private void rejectUnsafe(String value) {
        String normalized = value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "");
        for (String term : UNSAFE_TERMS) {
            if (normalized.contains(term)) {
                throw new WriteActionAuditOutboxException(METADATA_UNSAFE);
            }
        }
    }

    private String required(String value, String field, int maxLength) {
        String bounded = optional(value, maxLength);
        if (bounded == null) {
            throw new IllegalArgumentException(field + " is required");
        }
        return bounded;
    }

    private String optional(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().replaceAll("[\\r\\n\\t]+", " ");
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, maxLength);
    }

    private String string(String value) {
        return value == null ? "" : value;
    }
}
