package com.frauddetection.alert.audit.external;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ExternalWitnessCapabilities(
        @JsonProperty("witness_type")
        String witnessType,

        @JsonProperty("witness_id")
        String witnessId,

        @JsonProperty("independence_level")
        String independenceLevel,

        @JsonProperty("immutability_level")
        ExternalImmutabilityLevel immutabilityLevel,

        @JsonProperty("overwrite_protection")
        boolean overwriteProtection,

        @JsonProperty("delete_protection")
        boolean deleteProtection,

        @JsonProperty("read_after_write")
        boolean readAfterWrite,

        @JsonProperty("stable_reference")
        boolean stableReference,

        @JsonProperty("timestamp_type")
        ExternalWitnessTimestampType timestampType,

        @JsonProperty("timestamp_trust_level")
        String timestampTrustLevel,

        @JsonProperty("supports_versioning")
        boolean supportsVersioning,

        @JsonProperty("supports_retention")
        boolean supportsRetention,

        @JsonProperty("supports_signed_receipts")
        boolean supportsSignedReceipts,

        @JsonProperty("durability_guarantee")
        ExternalDurabilityGuarantee durabilityGuarantee
) {
    static ExternalWitnessCapabilities disabled() {
        return new ExternalWitnessCapabilities(
                "DISABLED",
                "disabled",
                ExternalWitnessIndependenceLevel.NONE.name(),
                ExternalImmutabilityLevel.NONE,
                false,
                false,
                false,
                false,
                ExternalWitnessTimestampType.APP_OBSERVED,
                ExternalTimestampTrustLevel.WEAK.name(),
                false,
                false,
                false,
                ExternalDurabilityGuarantee.NONE
        );
    }

    static ExternalWitnessCapabilities localFile() {
        return new ExternalWitnessCapabilities(
                "LOCAL_FILE",
                "local-file-dev",
                ExternalWitnessIndependenceLevel.LOCAL_DEV_ONLY.name(),
                ExternalImmutabilityLevel.NONE,
                false,
                false,
                false,
                false,
                ExternalWitnessTimestampType.APP_OBSERVED,
                ExternalTimestampTrustLevel.WEAK.name(),
                false,
                false,
                false,
                ExternalDurabilityGuarantee.NONE
        );
    }

    static ExternalWitnessCapabilities objectStore(ExternalImmutabilityLevel immutabilityLevel) {
        return objectStore(
                "object-store",
                ExternalWitnessIndependenceLevel.SAME_BOUNDARY.name(),
                immutabilityLevel,
                false,
                false,
                true,
                true,
                ExternalWitnessTimestampType.APP_OBSERVED,
                ExternalTimestampTrustLevel.WEAK.name(),
                false,
                false,
                false,
                ExternalDurabilityGuarantee.NONE
        );
    }

    static ExternalWitnessCapabilities objectStore(
            String witnessId,
            String independenceLevel,
            ExternalImmutabilityLevel immutabilityLevel,
            boolean overwriteProtection,
            boolean deleteProtection,
            boolean readAfterWrite,
            boolean stableReference,
            ExternalWitnessTimestampType timestampType,
            String timestampTrustLevel,
            boolean supportsVersioning,
            boolean supportsRetention,
            boolean supportsSignedReceipts,
            ExternalDurabilityGuarantee durabilityGuarantee
    ) {
        return new ExternalWitnessCapabilities(
                "OBJECT_STORE",
                hasText(witnessId) ? witnessId.trim() : "object-store",
                hasText(independenceLevel) ? independenceLevel.trim() : ExternalWitnessIndependenceLevel.SAME_BOUNDARY.name(),
                immutabilityLevel == null ? ExternalImmutabilityLevel.NONE : immutabilityLevel,
                overwriteProtection,
                deleteProtection,
                readAfterWrite,
                stableReference,
                timestampType == null ? ExternalWitnessTimestampType.APP_OBSERVED : timestampType,
                hasText(timestampTrustLevel) ? timestampTrustLevel.trim() : ExternalTimestampTrustLevel.WEAK.name(),
                supportsVersioning,
                supportsRetention,
                supportsSignedReceipts,
                durabilityGuarantee == null ? ExternalDurabilityGuarantee.NONE : durabilityGuarantee
        );
    }

    boolean supportsReadAfterWrite() {
        return readAfterWrite;
    }

    boolean supportsStableReference() {
        return stableReference;
    }

    boolean supportsWriteOnce() {
        return overwriteProtection;
    }

    boolean supportsDeleteDenialOrRetention() {
        return deleteProtection || supportsRetention;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
