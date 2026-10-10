package com.frauddetection.trustauthority.api;

import com.fasterxml.jackson.annotation.JsonProperty;

public record TrustVerifyResponse(
        @JsonProperty("status")
        String status,

        @JsonProperty("reason_code")
        String reasonCode
) {
}
