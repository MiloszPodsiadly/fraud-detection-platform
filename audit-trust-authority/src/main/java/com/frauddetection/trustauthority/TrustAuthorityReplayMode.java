package com.frauddetection.trustauthority;

enum TrustAuthorityReplayMode {
    LOCAL,
    DISTRIBUTED_HINT;

    static TrustAuthorityReplayMode from(String value) {
        if (value == null || value.isBlank()) {
            return LOCAL;
        }
        return TrustAuthorityReplayMode.valueOf(value.trim().replace('-', '_').toUpperCase());
    }
}
