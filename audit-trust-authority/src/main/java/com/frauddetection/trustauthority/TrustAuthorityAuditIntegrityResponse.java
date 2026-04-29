package com.frauddetection.trustauthority;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record TrustAuthorityAuditIntegrityResponse(
        @JsonProperty("status")
        String status,

        @JsonProperty("checked")
        int checked,

        @JsonProperty("mode")
        String mode,

        @JsonProperty("capability_level")
        TrustAuthorityCapabilityLevel capabilityLevel,

        @JsonProperty("tamper_detected")
        boolean tamperDetected,

        @JsonProperty("integrity_confidence")
        TrustAuthorityIntegrityConfidence integrityConfidence,

        @JsonProperty("latest_chain_position")
        Long latestChainPosition,

        @JsonProperty("latest_event_hash")
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String latestEventHash,

        @JsonProperty("window_start_chain_position")
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Long windowStartChainPosition,

        @JsonProperty("window_end_chain_position")
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Long windowEndChainPosition,

        @JsonProperty("boundary_previous_event_hash")
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String boundaryPreviousEventHash,

        @JsonProperty("reason_code")
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String reasonCode,

        @JsonProperty("violations")
        List<TrustAuthorityAuditIntegrityViolation> violations,

        @JsonProperty("trust_decision_trace")
        @JsonInclude(JsonInclude.Include.NON_NULL)
        TrustDecisionTrace trustDecisionTrace
) {
    static TrustAuthorityAuditIntegrityResponse unavailable(String reasonCode) {
        return unavailable(reasonCode, TrustAuthorityAuditIntegrityMode.WINDOW);
    }

    static TrustAuthorityAuditIntegrityResponse unavailable(String reasonCode, TrustAuthorityAuditIntegrityMode mode) {
        return new TrustAuthorityAuditIntegrityResponse(
                "UNAVAILABLE",
                0,
                mode.name(),
                TrustAuthorityCapabilityLevel.INTERNAL_CRYPTOGRAPHIC_TRUST,
                false,
                TrustAuthorityIntegrityConfidence.PARTIAL_BOUNDARY,
                null,
                null,
                null,
                null,
                null,
                reasonCode,
                List.of(),
                null
        );
    }

    TrustAuthorityAuditIntegrityResponse withDecisionTrace(TrustDecisionTrace trace) {
        return new TrustAuthorityAuditIntegrityResponse(
                status,
                checked,
                mode,
                capabilityLevel,
                tamperDetected,
                integrityConfidence,
                latestChainPosition,
                latestEventHash,
                windowStartChainPosition,
                windowEndChainPosition,
                boundaryPreviousEventHash,
                reasonCode,
                violations,
                trace
        );
    }
}
