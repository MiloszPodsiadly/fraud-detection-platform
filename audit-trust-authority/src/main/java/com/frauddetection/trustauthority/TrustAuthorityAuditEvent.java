package com.frauddetection.trustauthority;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

public record TrustAuthorityAuditEvent(
        @JsonProperty("event_id")
        String eventId,

        @JsonProperty("action")
        String action,

        @JsonProperty("caller_identity")
        String callerIdentity,

        @JsonProperty("caller_service")
        String callerService,

        @JsonProperty("purpose")
        String purpose,

        @JsonProperty("payload_hash")
        String payloadHash,

        @JsonProperty("key_id")
        String keyId,

        @JsonProperty("result")
        String result,

        @JsonProperty("reason_code")
        String reasonCode,

        @JsonProperty("occurred_at")
        Instant occurredAt
) {
}
