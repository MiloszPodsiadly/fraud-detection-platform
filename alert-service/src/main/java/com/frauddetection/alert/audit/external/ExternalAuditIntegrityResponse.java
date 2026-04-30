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

        @JsonProperty("durability_guarantee")
        ExternalDurabilityGuarantee durabilityGuarantee,

        @JsonProperty("timestamp_trust_level")
        String timestampTrustLevel,

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

        @JsonProperty("conflicting_hashes")
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        List<String> conflictingHashes,

        @JsonProperty("witness_sources")
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        List<String> witnessSources,

        @JsonProperty("violations")
        List<AuditIntegrityViolation> violations,

        @JsonProperty("integrity_status")
        String integrityStatus,

        @JsonProperty("trust_level")
        String trustLevel,

        @JsonProperty("signature_policy")
        String signaturePolicy
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
            ExternalImmutabilityLevel externalImmutabilityLevel,
            ExternalDurabilityGuarantee durabilityGuarantee,
            String timestampTrustLevel,
            String signatureVerificationStatus,
            String signingKeyId,
            String signingAlgorithm,
            String signingAuthority,
            String signatureReasonCode,
            List<String> conflictingHashes,
            List<String> witnessSources,
            List<AuditIntegrityViolation> violations
    ) {
        this(
                status,
                checked,
                limit,
                sourceService,
                partitionKey,
                reasonCode,
                message,
                localAnchor,
                externalAnchor,
                externalImmutabilityLevel,
                durabilityGuarantee,
                timestampTrustLevel,
                signatureVerificationStatus,
                signingKeyId,
                signingAlgorithm,
                signingAuthority,
                signatureReasonCode,
                conflictingHashes,
                witnessSources,
                violations,
                status,
                trustLevel(externalAnchor, signatureVerificationStatus, signingKeyId),
                signaturePolicy(signatureVerificationStatus, reasonCode)
        );
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
            List<AuditIntegrityViolation> violations
    ) {
        this(status, checked, limit, sourceService, partitionKey, reasonCode, message, localAnchor, externalAnchor, ExternalImmutabilityLevel.NONE, ExternalDurabilityGuarantee.NONE, "APP_OBSERVED", "UNSIGNED", null, null, null, null, List.of(), List.of(), violations);
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
        this(status, checked, limit, sourceService, partitionKey, reasonCode, message, localAnchor, externalAnchor, externalImmutabilityLevel, ExternalDurabilityGuarantee.NONE, "APP_OBSERVED", "UNSIGNED", null, null, null, null, List.of(), List.of(), violations);
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
            String signatureVerificationStatus,
            String signingKeyId,
            String signingAlgorithm,
            String signingAuthority,
            String signatureReasonCode,
            List<AuditIntegrityViolation> violations
    ) {
        this(status, checked, limit, sourceService, partitionKey, reasonCode, message, localAnchor, externalAnchor,
                externalImmutabilityLevel, ExternalDurabilityGuarantee.NONE, "APP_OBSERVED", signatureVerificationStatus, signingKeyId, signingAlgorithm,
                signingAuthority, signatureReasonCode, List.of(), List.of(), violations);
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
                ExternalDurabilityGuarantee.NONE,
                "APP_OBSERVED",
                "UNAVAILABLE",
                null,
                null,
                null,
                reasonCode,
                List.of(),
                List.of(),
                List.of()
        );
    }

    private static String trustLevel(
            ExternalAuditAnchorSummary externalAnchor,
            String signatureVerificationStatus,
            String signingKeyId
    ) {
        if (externalAnchor == null) {
            return "LOCAL_ONLY";
        }
        if ("VALID".equals(signatureVerificationStatus)) {
            return "EXTERNAL_VERIFIED";
        }
        if (signingKeyId != null && !signingKeyId.isBlank()) {
            return "EXTERNAL_SIGNED";
        }
        return "EXTERNAL_UNSIGNED";
    }

    private static String signaturePolicy(String signatureVerificationStatus, String reasonCode) {
        if (reasonCode != null && reasonCode.contains("REQUIRED")) {
            return "REQUIRED_FOR_PUBLICATION";
        }
        if ("VALID".equals(signatureVerificationStatus)) {
            return "REQUIRED_FOR_TRUST";
        }
        return "OPTIONAL";
    }
}
