package com.frauddetection.alert.audit.external;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

public record ExternalAnchorReference(
        @JsonProperty("anchor_id")
        String anchorId,

        @JsonProperty("external_key")
        String externalKey,

        @JsonProperty("anchor_hash")
        String anchorHash,

        @JsonProperty("external_hash")
        String externalHash,

        @JsonProperty("verified_at")
        Instant verifiedAt
) {
}
