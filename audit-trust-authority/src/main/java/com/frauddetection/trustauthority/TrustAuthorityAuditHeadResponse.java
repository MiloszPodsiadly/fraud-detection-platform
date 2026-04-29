package com.frauddetection.trustauthority;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

public record TrustAuthorityAuditHeadResponse(
        @JsonProperty("status")
        String status,

        @JsonProperty("source")
        String source,

        @JsonProperty("proof_type")
        String proofType,

        @JsonProperty("integrity_hint")
        String integrityHint,

        @JsonProperty("capability_level")
        TrustAuthorityCapabilityLevel capabilityLevel,

        @JsonProperty("chain_position")
        Long chainPosition,

        @JsonProperty("event_hash")
        String eventHash,

        @JsonProperty("occurred_at")
        Instant occurredAt
) {
    static TrustAuthorityAuditHeadResponse empty() {
        return new TrustAuthorityAuditHeadResponse(
                "EMPTY",
                "trust-authority-audit",
                "LOCAL_HASH_CHAIN_HEAD",
                "LOCAL_CHAIN_ONLY",
                TrustAuthorityCapabilityLevel.INTERNAL_CRYPTOGRAPHIC_TRUST,
                null,
                null,
                null
        );
    }

    static TrustAuthorityAuditHeadResponse from(TrustAuthorityAuditEvent event) {
        if (event == null) {
            return empty();
        }
        return new TrustAuthorityAuditHeadResponse(
                "AVAILABLE",
                "trust-authority-audit",
                "LOCAL_HASH_CHAIN_HEAD",
                "LOCAL_CHAIN_ONLY",
                TrustAuthorityCapabilityLevel.INTERNAL_CRYPTOGRAPHIC_TRUST,
                event.chainPosition(),
                event.eventHash(),
                event.occurredAt()
        );
    }
}
