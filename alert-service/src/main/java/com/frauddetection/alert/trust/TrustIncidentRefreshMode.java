package com.frauddetection.alert.trust;

import java.util.Locale;

public enum TrustIncidentRefreshMode {
    ATOMIC,
    PARTIAL;

    public static TrustIncidentRefreshMode parse(String value) {
        if (value == null || value.isBlank()) {
            return ATOMIC;
        }
        return TrustIncidentRefreshMode.valueOf(value.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
    }
}
