package com.frauddetection.trustauthority;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

public record TrustSignResponse(
        @JsonProperty("signature")
        String signature,

        @JsonProperty("key_id")
        String keyId,

        @JsonProperty("algorithm")
        String algorithm,

        @JsonProperty("signed_at")
        Instant signedAt,

        @JsonProperty("authority")
        String authority
) {
}
