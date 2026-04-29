package com.frauddetection.trustauthority;

enum TrustAuthorityIdentityMode {
    HMAC_LOCAL("hmac-local"),
    MTLS_READY("mtls-ready"),
    JWT_READY("jwt-ready"),
    MTLS_SERVICE_IDENTITY("mtls-service-identity"),
    JWT_SERVICE_IDENTITY("jwt-service-identity");

    private final String configValue;

    TrustAuthorityIdentityMode(String configValue) {
        this.configValue = configValue;
    }

    static TrustAuthorityIdentityMode from(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().replace('_', '-').toLowerCase();
        for (TrustAuthorityIdentityMode mode : values()) {
            if (mode.configValue.equals(normalized) || mode.name().replace('_', '-').toLowerCase().equals(normalized)) {
                return mode;
            }
        }
        throw new IllegalArgumentException("Unsupported trust authority identity mode.");
    }

    String configValue() {
        return configValue;
    }
}
