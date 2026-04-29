package com.frauddetection.trustauthority;

enum TrustAuthorityCapabilityLevel {
    INTERNAL_CRYPTOGRAPHIC_TRUST,
    EXTERNAL_ANCHORED_TRUST,
    WORM_COMPLIANCE;

    static TrustAuthorityCapabilityLevel from(String value) {
        if (value == null || value.isBlank()) {
            return INTERNAL_CRYPTOGRAPHIC_TRUST;
        }
        String normalized = value.trim().replace('-', '_').toUpperCase();
        return TrustAuthorityCapabilityLevel.valueOf(normalized);
    }
}
