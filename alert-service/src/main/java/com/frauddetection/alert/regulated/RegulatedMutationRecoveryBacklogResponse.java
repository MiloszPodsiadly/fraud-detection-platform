package com.frauddetection.alert.regulated;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

public record RegulatedMutationRecoveryBacklogResponse(
        @JsonProperty("total_recovery_required")
        long totalRecoveryRequired,

        @JsonProperty("total_in_progress_expired")
        long totalInProgressExpired,

        @JsonProperty("oldest_recovery_required_age")
        Long oldestRecoveryRequiredAgeSeconds,

        @JsonProperty("recovery_failed_terminal_count")
        long recoveryFailedTerminalCount,

        @JsonProperty("repeated_recovery_failures")
        long repeatedRecoveryFailures,

        @JsonProperty("by_state")
        Map<String, Long> byState,

        @JsonProperty("by_action")
        Map<String, Long> byAction
) {
}
