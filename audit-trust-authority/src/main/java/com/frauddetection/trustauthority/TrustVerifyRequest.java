package com.frauddetection.trustauthority;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

public record TrustVerifyRequest(
        @JsonProperty("purpose")
        @NotBlank
        String purpose,

        @JsonProperty("payload_hash")
        @NotBlank
        String payloadHash,

        @JsonProperty("partition_key")
        @NotBlank
        String partitionKey,

        @JsonProperty("chain_position")
        long chainPosition,

        @JsonProperty("anchor_id")
        @NotBlank
        String anchorId,

        @JsonProperty("signature")
        @NotBlank
        String signature,

        @JsonProperty("key_id")
        @NotBlank
        String keyId
) {
}
