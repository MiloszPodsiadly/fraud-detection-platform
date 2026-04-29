package com.frauddetection.alert.audit.external;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.frauddetection.alert.audit.AuditIntegrityViolation;

import java.util.List;

public record ExternalAuditIntegrityResponse(
        @JsonProperty("status")
        String status,

        @JsonProperty("checked")
        int checked,

        @JsonProperty("limit")
        int limit,

        @JsonProperty("source_service")
        String sourceService,

        @JsonProperty("partition_key")
        String partitionKey,

        @JsonProperty("reason_code")
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String reasonCode,

        @JsonProperty("message")
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String message,

        @JsonProperty("local_anchor")
        @JsonInclude(JsonInclude.Include.NON_NULL)
        ExternalAuditAnchorSummary localAnchor,

        @JsonProperty("external_anchor")
        @JsonInclude(JsonInclude.Include.NON_NULL)
        ExternalAuditAnchorSummary externalAnchor,

        @JsonProperty("external_immutability_level")
        ExternalImmutabilityLevel externalImmutabilityLevel,

        @JsonProperty("signature_verification_status")
        String signatureVerificationStatus,

        @JsonProperty("signing_key_id")
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String signingKeyId,

        @JsonProperty("signing_algorithm")
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String signingAlgorithm,

        @JsonProperty("signing_authority")
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String signingAuthority,

        @JsonProperty("signature_reason_code")
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String signatureReasonCode,

        @JsonProperty("violations")
        List<AuditIntegrityViolation> violations
) {
    public ExternalAuditIntegrityResponse(
            String status,
            int checked,
            int limit,
            String sourceService,
            String partitionKey,
            String reasonCode,
            String message,
            ExternalAuditAnchorSummary localAnchor,
            ExternalAuditAnchorSummary externalAnchor,
            List<AuditIntegrityViolation> violations
    ) {
        this(status, checked, limit, sourceService, partitionKey, reasonCode, message, localAnchor, externalAnchor, ExternalImmutabilityLevel.NONE, "UNSIGNED", null, null, null, null, violations);
    }

    public ExternalAuditIntegrityResponse(
            String status,
            int checked,
            int limit,
            String sourceService,
            String partitionKey,
            String reasonCode,
            String message,
            ExternalAuditAnchorSummary localAnchor,
            ExternalAuditAnchorSummary externalAnchor,
            ExternalImmutabilityLevel externalImmutabilityLevel,
            List<AuditIntegrityViolation> violations
    ) {
        this(status, checked, limit, sourceService, partitionKey, reasonCode, message, localAnchor, externalAnchor, externalImmutabilityLevel, "UNSIGNED", null, null, null, null, violations);
    }

    static ExternalAuditIntegrityResponse unavailable(ExternalAuditIntegrityQuery query, String reasonCode, String message) {
        return new ExternalAuditIntegrityResponse(
                "UNAVAILABLE",
                0,
                query.limit(),
                query.sourceService(),
                query.partitionKey(),
                reasonCode,
                message,
                null,
                null,
                ExternalImmutabilityLevel.NONE,
                "UNAVAILABLE",
                null,
                null,
                null,
                reasonCode,
                List.of()
        );
    }
}
