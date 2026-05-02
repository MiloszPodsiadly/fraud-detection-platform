package com.frauddetection.alert.outbox;

import com.fasterxml.jackson.annotation.JsonProperty;

public record OutboxBacklogResponse(
        @JsonProperty("pending_count")
        long pendingCount,
        @JsonProperty("processing_count")
        long processingCount,
        @JsonProperty("confirmation_unknown_count")
        long confirmationUnknownCount,
        @JsonProperty("failed_retryable_count")
        long failedRetryableCount,
        @JsonProperty("failed_terminal_count")
        long failedTerminalCount,
        @JsonProperty("recovery_required_count")
        long recoveryRequiredCount,
        @JsonProperty("oldest_pending_age_seconds")
        Long oldestPendingAgeSeconds
) {
}
