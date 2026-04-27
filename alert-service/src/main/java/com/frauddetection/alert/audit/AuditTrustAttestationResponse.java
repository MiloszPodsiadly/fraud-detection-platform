package com.frauddetection.alert.audit;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record AuditTrustAttestationResponse(
        @JsonProperty("status")
        String status,

        @JsonProperty("trust_level")
        AuditTrustLevel trustLevel,

        @JsonProperty("internal_integrity_status")
        String internalIntegrityStatus,

        @JsonProperty("external_integrity_status")
        String externalIntegrityStatus,

        @JsonProperty("external_anchor_status")
        String externalAnchorStatus,

        @JsonProperty("anchor_coverage")
        AnchorCoverage anchorCoverage,

        @JsonProperty("latest_chain_position")
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Long latestChainPosition,

        @JsonProperty("latest_event_hash")
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String latestEventHash,

        @JsonProperty("latest_external_anchor_reference")
        @JsonInclude(JsonInclude.Include.NON_NULL)
        ExternalAnchorReference latestExternalAnchorReference,

        @JsonProperty("attestation_fingerprint")
        String attestationFingerprint,

        @JsonProperty("attestation_signature")
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String attestationSignature,

        @JsonProperty("signing_key_id")
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String signingKeyId,

        @JsonProperty("signing_mode")
        String signingMode,

        @JsonProperty("source_service")
        String sourceService,

        @JsonProperty("limit")
        int limit,

        @JsonProperty("limitations")
        List<String> limitations
) {
    public record AnchorCoverage(
            @JsonProperty("total_anchors_checked")
            int totalAnchorsChecked,

            @JsonProperty("external_anchors_matched")
            int externalAnchorsMatched,

            @JsonProperty("external_anchors_missing")
            int externalAnchorsMissing,

            @JsonProperty("coverage_ratio")
            double coverageRatio
    ) {
        public static AnchorCoverage empty() {
            return new AnchorCoverage(0, 0, 0, 1.0d);
        }
    }

    public record ExternalAnchorReference(
            @JsonProperty("external_anchor_id")
            String externalAnchorId,

            @JsonProperty("chain_position")
            long chainPosition,

            @JsonProperty("sink_type")
            String sinkType,

            @JsonProperty("publication_status")
            String publicationStatus
    ) {
    }
}
