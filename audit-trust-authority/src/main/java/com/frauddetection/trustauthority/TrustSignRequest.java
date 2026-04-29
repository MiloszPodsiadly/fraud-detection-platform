package com.frauddetection.trustauthority;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record TrustSignRequest(
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
        @Min(1)
        long chainPosition,

        @JsonProperty("anchor_id")
        @NotBlank
        String anchorId
) {
}
