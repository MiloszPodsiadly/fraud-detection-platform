package com.frauddetection.scoring.features;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;

public final class FeatureSnapshotReader {

    private final Map<String, Object> featureSnapshot;

    public FeatureSnapshotReader(Map<String, Object> featureSnapshot) {
        Objects.requireNonNull(featureSnapshot, "featureSnapshot is required");
        for (Map.Entry<String, Object> entry : featureSnapshot.entrySet()) {
            if (entry.getKey() == null) {
                throw new IllegalArgumentException("featureSnapshot must not contain null keys");
            }
            if (entry.getValue() == null) {
                throw new IllegalArgumentException("featureSnapshot must not contain null values");
            }
        }
        this.featureSnapshot = Map.copyOf(featureSnapshot);
    }

    public FeatureSnapshotValue<Boolean> booleanValue(String key) {
        return value(key, FeatureSnapshotScalarType.BOOLEAN, Boolean.class);
    }

    public FeatureSnapshotValue<Integer> integerValue(String key) {
        return value(key, FeatureSnapshotScalarType.INTEGER, Integer.class);
    }

    public FeatureSnapshotValue<Long> longValue(String key) {
        return value(key, FeatureSnapshotScalarType.LONG, Long.class);
    }

    public FeatureSnapshotValue<Double> doubleValue(String key) {
        return value(key, FeatureSnapshotScalarType.DOUBLE, Double.class);
    }

    public FeatureSnapshotValue<String> stringValue(String key) {
        return value(key, FeatureSnapshotScalarType.STRING, String.class);
    }

    public FeatureSnapshotValue<BigDecimal> decimalValue(String key) {
        return value(key, FeatureSnapshotScalarType.DECIMAL, BigDecimal.class);
    }

    private <T> FeatureSnapshotValue<T> value(String key, FeatureSnapshotScalarType requestedType, Class<T> expectedType) {
        Objects.requireNonNull(key, "key is required");
        if (!FeatureSnapshotKeyPolicy.isAllowedFeatureKey(key)) {
            return FeatureSnapshotValue.notAllowed(key);
        }
        FeatureSnapshotScalarType policyType = FeatureSnapshotKeyPolicy.expectedTypeFor(key).orElse(null);
        if (policyType == null) {
            return FeatureSnapshotValue.notAllowed(key);
        }
        if (policyType != requestedType) {
            return FeatureSnapshotValue.invalidType(key, requestedType);
        }
        if (!featureSnapshot.containsKey(key)) {
            return FeatureSnapshotValue.missing(key);
        }
        Object actualValue = featureSnapshot.get(key);
        if (actualValue.getClass() != expectedType) {
            return FeatureSnapshotValue.invalidType(key, actualValue);
        }
        return FeatureSnapshotValue.present(key, expectedType.cast(actualValue));
    }
}
