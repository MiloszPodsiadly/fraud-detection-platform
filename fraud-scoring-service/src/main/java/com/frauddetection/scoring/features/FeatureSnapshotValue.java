package com.frauddetection.scoring.features;

import java.util.Objects;

public record FeatureSnapshotValue<T>(
        String key,
        FeatureSnapshotValueStatus status,
        T value,
        String actualType
) {
    public static final String NOT_ALLOWED_REDACTED_KEY = "[not-allowed]";

    public FeatureSnapshotValue {
        Objects.requireNonNull(key, "key is required");
        Objects.requireNonNull(status, "status is required");
        if (status == FeatureSnapshotValueStatus.PRESENT && value == null) {
            throw new IllegalArgumentException("PRESENT feature value is required");
        }
        if (status != FeatureSnapshotValueStatus.PRESENT && value != null) {
            throw new IllegalArgumentException("non-PRESENT feature value must be null");
        }
        if (status == FeatureSnapshotValueStatus.INVALID_TYPE && actualType == null) {
            throw new IllegalArgumentException("INVALID_TYPE actualType is required");
        }
        if (status != FeatureSnapshotValueStatus.INVALID_TYPE && actualType != null) {
            throw new IllegalArgumentException("actualType is allowed only for INVALID_TYPE");
        }
    }

    public static <T> FeatureSnapshotValue<T> present(String key, T value) {
        return new FeatureSnapshotValue<>(key, FeatureSnapshotValueStatus.PRESENT, value, null);
    }

    public static <T> FeatureSnapshotValue<T> missing(String key) {
        return new FeatureSnapshotValue<>(key, FeatureSnapshotValueStatus.MISSING, null, null);
    }

    public static <T> FeatureSnapshotValue<T> invalidType(String key, Object actualValue) {
        String actualType = actualValue == null ? "null" : actualValue.getClass().getSimpleName();
        return new FeatureSnapshotValue<>(key, FeatureSnapshotValueStatus.INVALID_TYPE, null, actualType);
    }

    public static <T> FeatureSnapshotValue<T> notAllowed(String key) {
        return new FeatureSnapshotValue<>(NOT_ALLOWED_REDACTED_KEY, FeatureSnapshotValueStatus.NOT_ALLOWED, null, null);
    }
}
