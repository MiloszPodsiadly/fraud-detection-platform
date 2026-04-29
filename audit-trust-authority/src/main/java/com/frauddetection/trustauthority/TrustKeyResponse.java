package com.frauddetection.trustauthority;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

public record TrustKeyResponse(
        @JsonProperty("key_id")
        String keyId,

        @JsonProperty("algorithm")
        String algorithm,

        @JsonProperty("public_key")
        String publicKey,

        @JsonProperty("key_fingerprint_sha256")
        String keyFingerprintSha256,

        @JsonProperty("valid_from")
        Instant validFrom,

        @JsonProperty("valid_until")
        Instant validUntil,

        @JsonProperty("status")
        String status
) {
}
