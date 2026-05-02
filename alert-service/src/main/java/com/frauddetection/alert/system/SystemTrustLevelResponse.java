package com.frauddetection.alert.system;

import com.fasterxml.jackson.annotation.JsonProperty;

public record SystemTrustLevelResponse(
        @JsonProperty("guarantee_level")
        String guaranteeLevel,

        @JsonProperty("publication_enabled")
        boolean publicationEnabled,

        @JsonProperty("publication_required")
        boolean publicationRequired,

        @JsonProperty("fail_closed")
        boolean failClosed,

        @JsonProperty("external_anchor_strength")
        String externalAnchorStrength,

        @JsonProperty("coverage_status")
        String coverageStatus,

        @JsonProperty("witness_status")
        String witnessStatus,

        @JsonProperty("signature_policy")
        String signaturePolicy,

        @JsonProperty("required_publication_failures")
        int requiredPublicationFailures,

        @JsonProperty("local_status_unverified")
        int localStatusUnverified,

        @JsonProperty("missing_ranges")
        int missingRanges,

        @JsonProperty("post_commit_audit_degraded")
        long postCommitAuditDegraded,

        @JsonProperty("unresolved_degradation_count")
        long unresolvedDegradationCount,

        @JsonProperty("pending_degradation_resolution_count")
        long pendingDegradationResolutionCount,

        @JsonProperty("post_commit_audit_degraded_resolved")
        long postCommitAuditDegradedResolved,

        @JsonProperty("outbox_failed_terminal_count")
        long outboxFailedTerminalCount,

        @JsonProperty("outbox_pending_count")
        long outboxPendingCount,

        @JsonProperty("outbox_processing_count")
        long outboxProcessingCount,

        @JsonProperty("outbox_publish_attempted_count")
        long outboxPublishAttemptedCount,

        @JsonProperty("outbox_confirmation_unknown_count")
        long outboxConfirmationUnknownCount,

        @JsonProperty("outbox_projection_mismatch_count")
        long outboxProjectionMismatchCount,

        @JsonProperty("terminal_outbox_failure_count")
        long terminalOutboxFailureCount,

        @JsonProperty("outbox_publish_confirmation_unknown_count")
        long outboxPublishConfirmationUnknownCount,

        @JsonProperty("unknown_outbox_confirmation_count")
        long unknownOutboxConfirmationCount,

        @JsonProperty("pending_outbox_resolution_count")
        long pendingOutboxResolutionCount,

        @JsonProperty("outbox_oldest_pending_age_seconds")
        Long outboxOldestPendingAgeSeconds,

        @JsonProperty("outbox_oldest_ambiguous_age_seconds")
        Long outboxOldestAmbiguousAgeSeconds,

        @JsonProperty("regulated_mutation_recovery_required_count")
        long regulatedMutationRecoveryRequiredCount,

        @JsonProperty("stale_processing_lease_count")
        long staleProcessingLeaseCount,

        @JsonProperty("committed_degraded_count")
        long committedDegradedCount,

        @JsonProperty("evidence_confirmation_pending_count")
        long evidenceConfirmationPendingCount,

        @JsonProperty("evidence_confirmation_failed_count")
        long evidenceConfirmationFailedCount,

        @JsonProperty("repeated_recovery_failure_count")
        long repeatedRecoveryFailureCount,

        @JsonProperty("oldest_recovery_required_age_seconds")
        Long oldestRecoveryRequiredAgeSeconds,

        @JsonProperty("reason_code")
        String reasonCode,

        @JsonProperty("transaction_mode")
        String transactionMode,

        @JsonProperty("transaction_capability_status")
        String transactionCapabilityStatus,

        @JsonProperty("outbox_delivery_mode")
        String outboxDeliveryMode,

        @JsonProperty("evidence_confirmation_mode")
        String evidenceConfirmationMode
) {
}
