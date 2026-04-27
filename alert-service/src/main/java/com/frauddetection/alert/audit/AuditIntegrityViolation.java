package com.frauddetection.alert.audit;

import com.fasterxml.jackson.annotation.JsonProperty;

public record AuditIntegrityViolation(
        @JsonProperty("violation_type")
        String violationType,

        @JsonProperty("position")
        int position,

        @JsonProperty("reason_code")
        String reasonCode
) {
}
