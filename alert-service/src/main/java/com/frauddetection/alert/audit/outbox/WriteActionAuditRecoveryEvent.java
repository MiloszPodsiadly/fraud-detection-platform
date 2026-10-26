package com.frauddetection.alert.audit.outbox;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.IndexDirection;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/** Immutable operator receipt. It intentionally creates no second audit-outbox obligation. */
@Document(collection = "write_action_audit_recovery_events")
public record WriteActionAuditRecoveryEvent(
        @Id String operationId,
        String outboxId,
        String governanceEventKind,
        String governanceEventId,
        String commitmentSha256,
        String auditRequestId,
        String actorId,
        String reason,
        String action,
        String result,
        int attemptCount,
        int recoveryOrdinal,
        @Indexed(name = "write_action_audit_recovery_occurred_at_idx", direction = IndexDirection.DESCENDING)
        Instant occurredAt,
        String referencedEvidenceStatus,
        String receiptSha256) { }
