package com.frauddetection.scoring.features;

import com.frauddetection.common.events.features.FraudFeatureContract;

import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class FeatureSnapshotKeyPolicy {

    private static final int MAX_KEY_LENGTH = 128;
    private static final Pattern CANONICAL_CAMEL_CASE_KEY = Pattern.compile("[a-z][A-Za-z0-9]*");
    private static final Set<String> FORBIDDEN_MARKERS = Set.of(
            "raw", "payload", "request", "response", "header", "authorization", "auth", "token",
            "secret", "password", "stack", "exception", "debug", "ssn", "iban", "pan",
            "cardnumber", "accountnumber", "email", "phone", "host", "endpoint", "url",
            "useragent", "fingerprint"
    );
    private static final Set<String> ALLOWED_CONTRACT_KEYS = Stream.concat(
                    FraudFeatureContract.JAVA_ENRICHED_FEATURE_NAMES.stream(),
                    FraudFeatureContract.ML_FEATURE_NAMES.stream()
            )
            .collect(Collectors.toUnmodifiableSet());
    private FeatureSnapshotKeyPolicy() {
    }

    public static String requireAllowedFeatureKey(String key) {
        if (!isAllowedFeatureKey(key)) {
            throw new IllegalArgumentException(
                    "featureSnapshot key must be registered, canonical, and safe for policy evaluation"
            );
        }
        return key;
    }

    /**
     * Validates registered key safety only. This does not mean the key is scalar-consumable by
     * adapters; adapter consumption must use {@link #expectedTypeFor(String)} or
     * {@link FeatureSnapshotReader}.
     */
    public static boolean isAllowedFeatureKey(String key) {
        if (key == null || key.isBlank() || key.length() > MAX_KEY_LENGTH) {
            return false;
        }
        if (!CANONICAL_CAMEL_CASE_KEY.matcher(key).matches()) {
            return false;
        }
        String normalized = key.toLowerCase();
        if (FORBIDDEN_MARKERS.stream().anyMatch(normalized::contains)) {
            return false;
        }
        return ALLOWED_CONTRACT_KEYS.contains(key);
    }

    /**
     * Adapter-consumption gate for scalar reads. {@link Optional#empty()} means a registered key
     * may exist in the internal snapshot, but is not v1 scalar-consumable.
     */
    public static Optional<FeatureSnapshotScalarType> expectedTypeFor(String key) {
        if (!isAllowedFeatureKey(key)) {
            return Optional.empty();
        }
        return FraudFeatureContract.expectedScalarTypeFor(key).map(FeatureSnapshotScalarType::valueOf);
    }
}
