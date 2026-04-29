package com.frauddetection.alert.audit;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.frauddetection.alert.audit.external.ExternalAnchorReference;
import com.frauddetection.alert.audit.external.ExternalImmutabilityLevel;

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

        @JsonProperty("external_immutability_level")
        ExternalImmutabilityLevel externalImmutabilityLevel,

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

        @JsonProperty("signer_mode")
        String signerMode,

        @JsonProperty("attestation_signature_strength")
        String attestationSignatureStrength,

        @JsonProperty("external_trust_dependency")
        String externalTrustDependency,

        @JsonProperty("trust_decision_trace")
        TrustDecisionTrace trustDecisionTrace,

        @JsonProperty("source_service")
        String sourceService,

        @JsonProperty("limit")
        int limit,

        @JsonProperty("limitations")
        List<String> limitations
) {
    public AuditTrustAttestationResponse(
            String status,
            AuditTrustLevel trustLevel,
            String internalIntegrityStatus,
            String externalIntegrityStatus,
            String externalAnchorStatus,
            AnchorCoverage anchorCoverage,
            Long latestChainPosition,
            String latestEventHash,
            ExternalAnchorReference latestExternalAnchorReference,
            String attestationFingerprint,
            String attestationSignature,
            String signingKeyId,
            String signerMode,
            String attestationSignatureStrength,
            String externalTrustDependency,
            String sourceService,
            int limit,
            List<String> limitations
    ) {
        this(status, trustLevel, internalIntegrityStatus, externalIntegrityStatus, externalAnchorStatus,
                ExternalImmutabilityLevel.NONE, anchorCoverage, latestChainPosition, latestEventHash,
                latestExternalAnchorReference, attestationFingerprint, attestationSignature, signingKeyId,
                signerMode, attestationSignatureStrength, externalTrustDependency, null, sourceService, limit, limitations);
    }

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

    public record TrustDecisionTrace(
            @JsonProperty("identity_verified")
            boolean identityVerified,

            @JsonProperty("signature_verified")
            boolean signatureVerified,

            @JsonProperty("chain_verified")
            String chainVerified,

            @JsonProperty("policy_applied")
            String policyApplied,

            @JsonProperty("final_status")
            String finalStatus
    ) {
    }

}
