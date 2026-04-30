package com.frauddetection.alert.system;

import com.fasterxml.jackson.annotation.JsonProperty;

public record SystemTrustLevelResponse(
        @JsonProperty("guarantee_level")
        String guaranteeLevel,

        @JsonProperty("publication_enabled")
        boolean publicationEnabled,

        @JsonProperty("publication_required")
        boolean publicationRequired,

        @JsonProperty("fail_closed")
        boolean failClosed,

        @JsonProperty("external_anchor_strength")
        String externalAnchorStrength,

        @JsonProperty("coverage_status")
        String coverageStatus,

        @JsonProperty("witness_status")
        String witnessStatus,

        @JsonProperty("signature_policy")
        String signaturePolicy,

        @JsonProperty("required_publication_failures")
        int requiredPublicationFailures,

        @JsonProperty("local_status_unverified")
        int localStatusUnverified,

        @JsonProperty("missing_ranges")
        int missingRanges,

        @JsonProperty("post_commit_audit_degraded")
        long postCommitAuditDegraded,

        @JsonProperty("reason_code")
        String reasonCode
) {
}
