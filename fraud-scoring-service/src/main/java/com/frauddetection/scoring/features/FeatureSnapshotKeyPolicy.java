package com.frauddetection.scoring.features;

import com.frauddetection.common.events.features.FraudFeatureContract;

import java.util.Map;
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
    private static final Map<String, FeatureSnapshotScalarType> SCALAR_CONSUMABLE_TYPES = Map.ofEntries(
            Map.entry(FraudFeatureContract.DEVICE_NOVELTY, FeatureSnapshotScalarType.BOOLEAN),
            Map.entry(FraudFeatureContract.COUNTRY_MISMATCH, FeatureSnapshotScalarType.BOOLEAN),
            Map.entry(FraudFeatureContract.PROXY_OR_VPN_DETECTED, FeatureSnapshotScalarType.BOOLEAN),
            Map.entry(FraudFeatureContract.RAPID_TRANSFER_BURST, FeatureSnapshotScalarType.BOOLEAN),
            Map.entry(FraudFeatureContract.RAPID_TRANSFER_FRAUD_CASE_CANDIDATE, FeatureSnapshotScalarType.BOOLEAN),
            Map.entry(FraudFeatureContract.RECENT_TRANSACTION_COUNT, FeatureSnapshotScalarType.INTEGER),
            Map.entry(FraudFeatureContract.RECENT_TRANSACTION_COUNT_WINDOW, FeatureSnapshotScalarType.LONG),
            Map.entry(FraudFeatureContract.RECENT_AMOUNT_SUM_WINDOW, FeatureSnapshotScalarType.LONG),
            Map.entry(FraudFeatureContract.RAPID_TRANSFER_WINDOW, FeatureSnapshotScalarType.LONG),
            Map.entry(FraudFeatureContract.MERCHANT_FREQUENCY_7D, FeatureSnapshotScalarType.INTEGER),
            Map.entry(FraudFeatureContract.HIGH_RISK_FLAG_COUNT, FeatureSnapshotScalarType.INTEGER),
            Map.entry(FraudFeatureContract.RAPID_TRANSFER_COUNT, FeatureSnapshotScalarType.INTEGER),
            Map.entry(FraudFeatureContract.RECENT_AMOUNT_SUM, FeatureSnapshotScalarType.DOUBLE),
            Map.entry(FraudFeatureContract.TRANSACTION_VELOCITY_PER_MINUTE, FeatureSnapshotScalarType.DOUBLE),
            Map.entry(FraudFeatureContract.TRANSACTION_VELOCITY_PER_HOUR, FeatureSnapshotScalarType.DOUBLE),
            Map.entry(FraudFeatureContract.TRANSACTION_VELOCITY_PER_DAY, FeatureSnapshotScalarType.DOUBLE),
            Map.entry(FraudFeatureContract.RECENT_AMOUNT_AVERAGE, FeatureSnapshotScalarType.DOUBLE),
            Map.entry(FraudFeatureContract.RECENT_AMOUNT_STD_DEV, FeatureSnapshotScalarType.DOUBLE),
            Map.entry(FraudFeatureContract.AMOUNT_DEVIATION_FROM_USER_MEAN, FeatureSnapshotScalarType.DOUBLE),
            Map.entry(FraudFeatureContract.MERCHANT_ENTROPY, FeatureSnapshotScalarType.DOUBLE),
            Map.entry(FraudFeatureContract.COUNTRY_ENTROPY, FeatureSnapshotScalarType.DOUBLE),
            Map.entry(FraudFeatureContract.RECENT_AMOUNT_SUM_PLN, FeatureSnapshotScalarType.DECIMAL),
            Map.entry(FraudFeatureContract.CURRENT_TRANSACTION_AMOUNT_PLN, FeatureSnapshotScalarType.DECIMAL),
            Map.entry(FraudFeatureContract.RAPID_TRANSFER_THRESHOLD_PLN, FeatureSnapshotScalarType.DECIMAL),
            Map.entry(FraudFeatureContract.RAPID_TRANSFER_TOTAL_PLN, FeatureSnapshotScalarType.DECIMAL),
            Map.entry(FraudFeatureContract.CUSTOMER_SEGMENT, FeatureSnapshotScalarType.STRING),
            Map.entry(FraudFeatureContract.MERCHANT_CATEGORY, FeatureSnapshotScalarType.STRING),
            Map.entry(FraudFeatureContract.CURRENCY, FeatureSnapshotScalarType.STRING)
    );

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
        return Optional.ofNullable(SCALAR_CONSUMABLE_TYPES.get(key));
    }
}
