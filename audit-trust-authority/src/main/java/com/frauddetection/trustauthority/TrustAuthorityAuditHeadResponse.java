package com.frauddetection.trustauthority;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

public record TrustAuthorityAuditHeadResponse(
        @JsonProperty("chain_position")
        Long chainPosition,

        @JsonProperty("event_hash")
        String eventHash,

        @JsonProperty("timestamp")
        Instant timestamp
) {
    static TrustAuthorityAuditHeadResponse empty() {
        return new TrustAuthorityAuditHeadResponse(null, null, null);
    }

    static TrustAuthorityAuditHeadResponse from(TrustAuthorityAuditEvent event) {
        if (event == null) {
            return empty();
        }
        return new TrustAuthorityAuditHeadResponse(event.chainPosition(), event.eventHash(), event.occurredAt());
    }
}
