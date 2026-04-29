package com.frauddetection.alert.audit.external;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

public record AuditTrustAuthorityKey(
        @JsonProperty("key_id")
        String keyId,

        @JsonProperty("algorithm")
        String algorithm,

        @JsonProperty("public_key")
        String publicKey,

        @JsonProperty("valid_from")
        Instant validFrom,

        @JsonProperty("valid_until")
        Instant validUntil,

        @JsonProperty("status")
        String status
) {
}
