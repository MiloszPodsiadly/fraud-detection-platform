package com.frauddetection.trustauthority;

enum TrustAuthorityAuditIntegrityMode {
    FULL_CHAIN,
    WINDOW;

    static TrustAuthorityAuditIntegrityMode from(String value) {
        if (value == null || value.isBlank()) {
            return WINDOW;
        }
        return switch (value.trim().toUpperCase()) {
            case "FULL_CHAIN" -> FULL_CHAIN;
            case "WINDOW" -> WINDOW;
            default -> throw new TrustAuthorityRequestException(
                    org.springframework.http.HttpStatus.BAD_REQUEST,
                    "Unsupported trust authority audit integrity mode."
            );
        };
    }
}
