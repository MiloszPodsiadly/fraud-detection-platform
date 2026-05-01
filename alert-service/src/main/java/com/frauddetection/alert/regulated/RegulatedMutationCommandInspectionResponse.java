package com.frauddetection.alert.regulated;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

public record RegulatedMutationCommandInspectionResponse(
        @JsonProperty("idempotency_key")
        String idempotencyKey,
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
                command.getIdempotencyKey(),
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
}
