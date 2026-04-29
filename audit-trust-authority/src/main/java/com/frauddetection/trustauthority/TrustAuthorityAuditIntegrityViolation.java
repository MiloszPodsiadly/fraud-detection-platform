package com.frauddetection.trustauthority;

import com.fasterxml.jackson.annotation.JsonProperty;

public record TrustAuthorityAuditIntegrityViolation(
        @JsonProperty("reason_code")
        String reasonCode,

        @JsonProperty("chain_position")
        Long chainPosition,

        @JsonProperty("message")
        String message
) {
}
