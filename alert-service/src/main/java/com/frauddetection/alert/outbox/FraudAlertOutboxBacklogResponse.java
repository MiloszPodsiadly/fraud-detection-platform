package com.frauddetection.alert.outbox;

import com.fasterxml.jackson.annotation.JsonProperty;

public record FraudAlertOutboxBacklogResponse(
        @JsonProperty("pending_count") long pendingCount,
        @JsonProperty("processing_count") long processingCount,
        @JsonProperty("publish_attempted_count") long publishAttemptedCount,
        @JsonProperty("confirmation_unknown_count") long confirmationUnknownCount,
        @JsonProperty("failed_terminal_count") long failedTerminalCount,
        @JsonProperty("oldest_unresolved_age_seconds") Long oldestUnresolvedAgeSeconds
) {
}
