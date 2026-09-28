package com.frauddetection.alert.regulated;

import java.time.Instant;

public final class RegulatedMutationInspectionTestFixtures {

    private RegulatedMutationInspectionTestFixtures() {
    }

    public static RegulatedMutationCommandInspectionResponse currentInspection(
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
            String lastErrorCode,
            Instant updatedAt
    ) {
        return new RegulatedMutationCommandInspectionResponse(
                idempotencyKeyHash,
                idempotencyKeyMasked,
                action,
                RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1.name(),
                resourceType,
                resourceId != null && !resourceId.isBlank(),
                hash("resourceId=", resourceId),
                state,
                executionStatus,
                leaseOwner != null && !leaseOwner.isBlank(),
                hash("leaseOwner=", leaseOwner),
                leaseExpiresAt,
                leaseRenewalCount,
                responseSnapshotPresent,
                attemptedAuditId,
                successAuditId,
                failedAuditId,
                degradationReason,
                safeErrorCode(lastErrorCode),
                updatedAt
        );
    }

    public static RegulatedMutationCommandInspectionResponse currentInspection(
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
            String lastErrorCode,
            Instant updatedAt
    ) {
        return currentInspection(
                idempotencyKeyHash,
                idempotencyKeyMasked,
                action,
                resourceType,
                resourceId,
                state,
                executionStatus,
                leaseOwner,
                leaseExpiresAt,
                0,
                responseSnapshotPresent,
                attemptedAuditId,
                successAuditId,
                failedAuditId,
                degradationReason,
                lastErrorCode,
                updatedAt
        );
    }

    private static String hash(String prefix, String value) {
        return value == null || value.isBlank() ? null : RegulatedMutationIntentHasher.hash(prefix + value);
    }

    private static String safeErrorCode(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        return normalized.matches("^[A-Z0-9_]{1,80}$") ? normalized : "UNSAFE_ERROR_REDACTED";
    }
}
