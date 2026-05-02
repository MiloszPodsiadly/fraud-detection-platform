package com.frauddetection.alert.outbox;

import com.fasterxml.jackson.annotation.JsonProperty;

public record OutboxRecoveryRunResponse(
        @JsonProperty("released_stale_processing")
        int releasedStaleProcessing,
        @JsonProperty("publish_attempted_marked_unknown")
        int publishAttemptedMarkedUnknown,
        @JsonProperty("projection_repaired")
        int projectionRepaired,
        @JsonProperty("publish_attempted")
        int publishAttempted
) {
}
