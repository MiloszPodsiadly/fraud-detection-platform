package com.frauddetection.alert.regulated;

import com.fasterxml.jackson.annotation.JsonProperty;

public record RegulatedMutationRecoveryRunResponse(
        long recovered,

        @JsonProperty("still_pending")
        long stillPending,

        @JsonProperty("recovery_required")
        long recoveryRequired,

        long failed,

        long scanned
) {
}
