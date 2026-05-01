package com.frauddetection.alert.regulated;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

public record RegulatedMutationCommandInspectionResponse(
        @JsonProperty("idempotency_key_hash")
        String idempotencyKeyHash,
        @JsonProperty("idempotency_key_masked")
        String idempotencyKeyMasked,
        String action,
        @JsonProperty("resource_type")
        String resourceType,
        @JsonProperty("resource_id")
        String resourceId,
        String state,
        @JsonProperty("execution_status")
        String executionStatus,
        @JsonProperty("lease_owner")
        String leaseOwner,
        @JsonProperty("lease_expires_at")
        Instant leaseExpiresAt,
        @JsonProperty("response_snapshot_present")
        boolean responseSnapshotPresent,
        @JsonProperty("attempted_audit_id")
        String attemptedAuditId,
        @JsonProperty("success_audit_id")
        String successAuditId,
        @JsonProperty("failed_audit_id")
        String failedAuditId,
        @JsonProperty("degradation_reason")
        String degradationReason,
        @JsonProperty("last_error")
        String lastError,
        @JsonProperty("updated_at")
        Instant updatedAt
) {
    static RegulatedMutationCommandInspectionResponse from(RegulatedMutationCommandDocument command) {
        return new RegulatedMutationCommandInspectionResponse(
                idempotencyKeyHash(command),
                mask(command.getIdempotencyKey()),
                command.getAction(),
                command.getResourceType(),
                command.getResourceId(),
                command.getState() == null ? null : command.getState().name(),
                command.getExecutionStatus() == null ? null : command.getExecutionStatus().name(),
                command.getLeaseOwner(),
                command.getLeaseExpiresAt(),
                command.getResponseSnapshot() != null,
                command.getAttemptedAuditId(),
                command.getSuccessAuditId(),
                command.getFailedAuditId(),
                command.getDegradationReason(),
                command.getLastError(),
                command.getUpdatedAt()
        );
    }

    private static String idempotencyKeyHash(RegulatedMutationCommandDocument command) {
        if (command.getIdempotencyKeyHash() != null && !command.getIdempotencyKeyHash().isBlank()) {
            return command.getIdempotencyKeyHash();
        }
        return command.getIdempotencyKey() == null ? null : RegulatedMutationIntentHasher.hash(command.getIdempotencyKey());
    }

    private static String mask(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return null;
        }
        String normalized = idempotencyKey.trim();
        if (normalized.length() <= 10) {
            return "..." + normalized.substring(Math.max(0, normalized.length() - 4));
        }
        return normalized.substring(0, 6) + "..." + normalized.substring(normalized.length() - 4);
    }
}
