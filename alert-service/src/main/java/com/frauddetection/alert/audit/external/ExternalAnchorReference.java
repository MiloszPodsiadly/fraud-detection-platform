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
        Instant verifiedAt,

        @JsonProperty("signature_status")
        String signatureStatus,

        @JsonProperty("signature")
        String signature,

        @JsonProperty("signing_key_id")
        String signingKeyId,

        @JsonProperty("signing_algorithm")
        String signingAlgorithm,

        @JsonProperty("signed_at")
        Instant signedAt,

        @JsonProperty("signing_authority")
        String signingAuthority,

        @JsonProperty("signed_payload_hash")
        String signedPayloadHash
) {
    public ExternalAnchorReference(
            String anchorId,
            String externalKey,
            String anchorHash,
            String externalHash,
            Instant verifiedAt
    ) {
        this(anchorId, externalKey, anchorHash, externalHash, verifiedAt, null, null, null, null, null, null, null);
    }

    ExternalAnchorReference withSignature(SignedAuditAnchorPayload signature) {
        if (signature == null) {
            return this;
        }
        return new ExternalAnchorReference(
                anchorId,
                externalKey,
                anchorHash,
                externalHash,
                verifiedAt,
                signature.signatureStatus(),
                signature.signature(),
                signature.keyId(),
                signature.signatureAlgorithm(),
                signature.signedAt(),
                signature.signingAuthority(),
                signature.signedPayloadHash()
        );
    }
}
