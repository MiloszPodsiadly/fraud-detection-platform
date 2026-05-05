package com.frauddetection.alert.regulated;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

public record RegulatedMutationCommandInspectionResponse(
        @JsonProperty("idempotency_key_hash")
        String idempotencyKeyHash,
        @JsonProperty("idempotency_key_masked")
        String idempotencyKeyMasked,
        String action,
        @JsonProperty("mutation_model_version")
        String mutationModelVersion,
        @JsonProperty("resource_type")
        String resourceType,
        @JsonProperty("resource_id")
        String resourceId,
        String state,
        @JsonProperty("execution_status")
        String executionStatus,
        @JsonProperty("lease_owner_present")
        boolean leaseOwnerPresent,
        @JsonProperty("lease_owner_hash")
        String leaseOwnerHash,
        @JsonProperty("lease_expires_at")
        Instant leaseExpiresAt,
        @JsonProperty("lease_renewal_count")
        int leaseRenewalCount,
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
        @JsonProperty("last_error_code")
        String lastErrorCode,
        @JsonProperty("updated_at")
        Instant updatedAt
) {
    public RegulatedMutationCommandInspectionResponse(
            String idempotencyKeyHash,
            String idempotencyKeyMasked,
            String action,
            String resourceType,
            String resourceId,
            String state,
            String executionStatus,
            String leaseOwner,
            Instant leaseExpiresAt,
            boolean responseSnapshotPresent,
            String attemptedAuditId,
            String successAuditId,
            String failedAuditId,
            String degradationReason,
            String lastError,
            Instant updatedAt
    ) {
        this(
                idempotencyKeyHash,
                idempotencyKeyMasked,
                action,
                RegulatedMutationModelVersion.LEGACY_REGULATED_MUTATION.name(),
                resourceType,
                resourceId,
                state,
                executionStatus,
                leaseOwner != null && !leaseOwner.isBlank(),
                safeLeaseOwnerHash(leaseOwner),
                leaseExpiresAt,
                0,
                responseSnapshotPresent,
                attemptedAuditId,
                successAuditId,
                failedAuditId,
                degradationReason,
                safeErrorCode(lastError),
                updatedAt
        );
    }

    public RegulatedMutationCommandInspectionResponse(
            String idempotencyKeyHash,
            String idempotencyKeyMasked,
            String action,
            String resourceType,
            String resourceId,
            String state,
            String executionStatus,
            String leaseOwner,
            Instant leaseExpiresAt,
            int leaseRenewalCount,
            boolean responseSnapshotPresent,
            String attemptedAuditId,
            String successAuditId,
            String failedAuditId,
            String degradationReason,
            String lastError,
            Instant updatedAt
    ) {
        this(
                idempotencyKeyHash,
                idempotencyKeyMasked,
                action,
                RegulatedMutationModelVersion.LEGACY_REGULATED_MUTATION.name(),
                resourceType,
                resourceId,
                state,
                executionStatus,
                leaseOwner != null && !leaseOwner.isBlank(),
                safeLeaseOwnerHash(leaseOwner),
                leaseExpiresAt,
                leaseRenewalCount,
                responseSnapshotPresent,
                attemptedAuditId,
                successAuditId,
                failedAuditId,
                degradationReason,
                safeErrorCode(lastError),
                updatedAt
        );
    }

    public String lastError() {
        return lastErrorCode;
    }

    static RegulatedMutationCommandInspectionResponse from(RegulatedMutationCommandDocument command) {
        return new RegulatedMutationCommandInspectionResponse(
                idempotencyKeyHash(command),
                mask(command.getIdempotencyKey()),
                command.getAction(),
                command.mutationModelVersionOrLegacy().name(),
                command.getResourceType(),
                command.getResourceId(),
                command.getState() == null ? null : command.getState().name(),
                command.getExecutionStatus() == null ? null : command.getExecutionStatus().name(),
                command.getLeaseOwner() != null && !command.getLeaseOwner().isBlank(),
                safeLeaseOwnerHash(command.getLeaseOwner()),
                command.getLeaseExpiresAt(),
                command.leaseRenewalCountOrZero(),
                command.getResponseSnapshot() != null,
                command.getAttemptedAuditId(),
                command.getSuccessAuditId(),
                command.getFailedAuditId(),
                command.getDegradationReason(),
                safeErrorCode(command.getLastError()),
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

    private static String safeLeaseOwnerHash(String leaseOwner) {
        if (leaseOwner == null || leaseOwner.isBlank()) {
            return null;
        }
        return RegulatedMutationIntentHasher.hash("leaseOwner=" + leaseOwner.trim());
    }

    private static String safeErrorCode(String lastError) {
        if (lastError == null || lastError.isBlank()) {
            return null;
        }
        String normalized = lastError.trim();
        if (normalized.matches("^[A-Z0-9_]{1,80}$")) {
            return normalized;
        }
        return "UNSAFE_ERROR_REDACTED";
    }
}
