package com.frauddetection.alert.audit.external;

import com.fasterxml.jackson.annotation.JsonProperty;

record ExternalWitnessCapabilities(
        @JsonProperty("witness_type")
        String witnessType,

        @JsonProperty("immutability_level")
        ExternalImmutabilityLevel immutabilityLevel,

        @JsonProperty("timestamp_trust_level")
        String timestampTrustLevel,

        @JsonProperty("timestamp_type")
        ExternalWitnessTimestampType timestampType,

        @JsonProperty("independence_level")
        String independenceLevel,

        @JsonProperty("supports_read_after_write")
        boolean supportsReadAfterWrite,

        @JsonProperty("supports_stable_reference")
        boolean supportsStableReference,

        @JsonProperty("supports_write_once")
        boolean supportsWriteOnce,

        @JsonProperty("supports_delete_denial_or_retention")
        boolean supportsDeleteDenialOrRetention
) {
    static ExternalWitnessCapabilities disabled() {
        return new ExternalWitnessCapabilities(
                "disabled",
                ExternalImmutabilityLevel.NONE,
                "NONE",
                ExternalWitnessTimestampType.APP_OBSERVED,
                "NONE",
                false,
                false,
                false,
                false
        );
    }

    static ExternalWitnessCapabilities localFile() {
        return new ExternalWitnessCapabilities(
                "local-file",
                ExternalImmutabilityLevel.NONE,
                "APP_OBSERVED",
                ExternalWitnessTimestampType.APP_OBSERVED,
                "LOCAL_DEV_ONLY",
                false,
                false,
                false,
                false
        );
    }

    static ExternalWitnessCapabilities objectStore(ExternalImmutabilityLevel immutabilityLevel) {
        ExternalImmutabilityLevel normalized = immutabilityLevel == null ? ExternalImmutabilityLevel.NONE : immutabilityLevel;
        return new ExternalWitnessCapabilities(
                "object-store",
                normalized,
                normalized == ExternalImmutabilityLevel.ENFORCED ? "STORAGE_OBSERVED" : "APP_OBSERVED",
                normalized == ExternalImmutabilityLevel.ENFORCED
                        ? ExternalWitnessTimestampType.STORAGE_OBSERVED
                        : ExternalWitnessTimestampType.APP_OBSERVED,
                "EXTERNAL_OBJECT_STORE",
                true,
                true,
                true,
                normalized == ExternalImmutabilityLevel.ENFORCED
        );
    }
}
