package com.frauddetection.scoring.features;

import com.frauddetection.common.events.features.FraudFeatureContract;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FeatureSnapshotReaderTest {

    @Test
    void returnsPresentOnlyForExactScalarTypesAlreadyUsedByTheFeatureContract() {
        FeatureSnapshotReader reader = new FeatureSnapshotReader(Map.of(
                FraudFeatureContract.DEVICE_NOVELTY, true,
                FraudFeatureContract.RECENT_TRANSACTION_COUNT, 3,
                FraudFeatureContract.RECENT_AMOUNT_SUM_WINDOW, 60L,
                FraudFeatureContract.TRANSACTION_VELOCITY_PER_MINUTE, 2.5d,
                FraudFeatureContract.CURRENCY, "PLN",
                FraudFeatureContract.CUSTOMER_SEGMENT, "retail",
                FraudFeatureContract.MERCHANT_CATEGORY, "electronics",
                FraudFeatureContract.RAPID_TRANSFER_TOTAL_PLN, new BigDecimal("125.20")
        ));

        assertThat(reader.booleanValue(FraudFeatureContract.DEVICE_NOVELTY).value()).isTrue();
        assertThat(reader.integerValue(FraudFeatureContract.RECENT_TRANSACTION_COUNT).value()).isEqualTo(3);
        assertThat(reader.longValue(FraudFeatureContract.RECENT_AMOUNT_SUM_WINDOW).value()).isEqualTo(60L);
        assertThat(reader.doubleValue(FraudFeatureContract.TRANSACTION_VELOCITY_PER_MINUTE).value()).isEqualTo(2.5d);
        assertThat(reader.stringValue(FraudFeatureContract.CURRENCY).value()).isEqualTo("PLN");
        assertThat(reader.stringValue(FraudFeatureContract.CUSTOMER_SEGMENT).value()).isEqualTo("retail");
        assertThat(reader.stringValue(FraudFeatureContract.MERCHANT_CATEGORY).value()).isEqualTo("electronics");
        assertThat(reader.decimalValue(FraudFeatureContract.RAPID_TRANSFER_TOTAL_PLN).value())
                .isEqualByComparingTo("125.20");
    }

    @Test
    void returnsMissingWithoutInventingBooleanOrNumericDefaults() {
        FeatureSnapshotReader reader = new FeatureSnapshotReader(Map.of());

        FeatureSnapshotValue<Boolean> missingBoolean = reader.booleanValue(FraudFeatureContract.DEVICE_NOVELTY);
        FeatureSnapshotValue<Integer> missingNumber = reader.integerValue(FraudFeatureContract.RECENT_TRANSACTION_COUNT);

        assertThat(missingBoolean.status()).isEqualTo(FeatureSnapshotValueStatus.MISSING);
        assertThat(missingBoolean.value()).isNull();
        assertThat(missingNumber.status()).isEqualTo(FeatureSnapshotValueStatus.MISSING);
        assertThat(missingNumber.value()).isNull();
    }

    @Test
    void returnsInvalidTypeWithoutCoercingScalarOrNestedValues() {
        FeatureSnapshotReader reader = new FeatureSnapshotReader(Map.of(
                FraudFeatureContract.DEVICE_NOVELTY, "true",
                FraudFeatureContract.RECENT_TRANSACTION_COUNT, "3",
                FraudFeatureContract.RECENT_AMOUNT_SUM_WINDOW, 1
        ));

        assertThat(reader.booleanValue(FraudFeatureContract.DEVICE_NOVELTY).status())
                .isEqualTo(FeatureSnapshotValueStatus.INVALID_TYPE);
        assertThat(reader.integerValue(FraudFeatureContract.RECENT_TRANSACTION_COUNT).status())
                .isEqualTo(FeatureSnapshotValueStatus.INVALID_TYPE);
        assertThat(reader.longValue(FraudFeatureContract.RECENT_AMOUNT_SUM_WINDOW).status())
                .isEqualTo(FeatureSnapshotValueStatus.INVALID_TYPE);
    }

    @Test
    void returnsNotAllowedForForbiddenOrUnregisteredKeys() {
        FeatureSnapshotReader reader = new FeatureSnapshotReader(Map.of(
                "rawPayload", "sensitive",
                "device.isnew", true
        ));

        assertThat(reader.stringValue("rawPayload").status()).isEqualTo(FeatureSnapshotValueStatus.NOT_ALLOWED);
        assertThat(reader.booleanValue("device.isnew").status()).isEqualTo(FeatureSnapshotValueStatus.NOT_ALLOWED);
    }

    @Test
    void notAllowedDoesNotExposeRawForbiddenKey() {
        FeatureSnapshotReader reader = new FeatureSnapshotReader(Map.of());

        FeatureSnapshotValue<String> value = reader.stringValue("authorizationBearerToken_very_sensitive");

        assertThat(value.status()).isEqualTo(FeatureSnapshotValueStatus.NOT_ALLOWED);
        assertThat(value.key()).isEqualTo(FeatureSnapshotValue.NOT_ALLOWED_REDACTED_KEY);
        assertThat(value.key().toLowerCase()).doesNotContain("authorization", "token");
        assertThat(value.value()).isNull();
        assertThat(value.actualType()).isNull();
    }

    @Test
    void notAllowedDoesNotExposeOversizedKey() {
        FeatureSnapshotReader reader = new FeatureSnapshotReader(Map.of());
        String rawKey = "raw" + "x".repeat(500);

        FeatureSnapshotValue<String> value = reader.stringValue(rawKey);

        assertThat(value.status()).isEqualTo(FeatureSnapshotValueStatus.NOT_ALLOWED);
        assertThat(value.key()).doesNotContain(rawKey);
        assertThat(value.key()).hasSizeLessThan(32);
    }

    @Test
    void constructorAllowsDisallowedKeysButAccessReturnsRedactedNotAllowed() {
        FeatureSnapshotReader reader = new FeatureSnapshotReader(Map.of("rawPayload", "secret"));

        FeatureSnapshotValue<String> value = reader.stringValue("rawPayload");

        assertThat(value.status()).isEqualTo(FeatureSnapshotValueStatus.NOT_ALLOWED);
        assertThat(value.key()).isEqualTo(FeatureSnapshotValue.NOT_ALLOWED_REDACTED_KEY);
        assertThat(value.value()).isNull();
        assertThat(value.actualType()).isNull();
    }

    @Test
    void wrongAccessorForKnownKeyDoesNotReturnPresent() {
        FeatureSnapshotReader reader = new FeatureSnapshotReader(Map.of(
                FraudFeatureContract.DEVICE_NOVELTY, "true",
                FraudFeatureContract.CURRENCY, true,
                FraudFeatureContract.RECENT_TRANSACTION_COUNT, "3"
        ));

        assertThat(reader.stringValue(FraudFeatureContract.DEVICE_NOVELTY).status())
                .isEqualTo(FeatureSnapshotValueStatus.WRONG_ACCESSOR);
        assertThat(reader.stringValue(FraudFeatureContract.DEVICE_NOVELTY).actualType()).isNull();
        assertThat(reader.booleanValue(FraudFeatureContract.CURRENCY).status())
                .isEqualTo(FeatureSnapshotValueStatus.WRONG_ACCESSOR);
        assertThat(reader.stringValue(FraudFeatureContract.RECENT_TRANSACTION_COUNT).status())
                .isEqualTo(FeatureSnapshotValueStatus.WRONG_ACCESSOR);
    }

    @Test
    void nonScalarRegisteredKeysReturnNotAllowed() {
        FeatureSnapshotReader reader = new FeatureSnapshotReader(Map.of(
                FraudFeatureContract.RAPID_TRANSFER_TRANSACTION_IDS, List.of("tx-1"),
                FraudFeatureContract.FEATURE_FLAGS, List.of("DEVICE_NOVELTY")
        ));

        FeatureSnapshotValue<String> transactionIds = reader.stringValue(FraudFeatureContract.RAPID_TRANSFER_TRANSACTION_IDS);
        FeatureSnapshotValue<String> featureFlags = reader.stringValue(FraudFeatureContract.FEATURE_FLAGS);

        assertThat(transactionIds.status()).isEqualTo(FeatureSnapshotValueStatus.NOT_ALLOWED);
        assertThat(transactionIds.key()).isEqualTo(FeatureSnapshotValue.NOT_ALLOWED_REDACTED_KEY);
        assertThat(featureFlags.status()).isEqualTo(FeatureSnapshotValueStatus.NOT_ALLOWED);
        assertThat(featureFlags.key()).isEqualTo(FeatureSnapshotValue.NOT_ALLOWED_REDACTED_KEY);
    }

    @Test
    void registeredButNonConsumableKeysRemainAllowedButReadAsRedactedNotAllowed() {
        FeatureSnapshotReader reader = new FeatureSnapshotReader(Map.of(
                FraudFeatureContract.RAPID_TRANSFER_TRANSACTION_IDS, List.of("tx-1"),
                FraudFeatureContract.FEATURE_FLAGS, List.of("DEVICE_NOVELTY")
        ));

        assertThat(FeatureSnapshotKeyPolicy.isAllowedFeatureKey(FraudFeatureContract.RAPID_TRANSFER_TRANSACTION_IDS))
                .isTrue();
        assertThat(FeatureSnapshotKeyPolicy.expectedTypeFor(FraudFeatureContract.RAPID_TRANSFER_TRANSACTION_IDS))
                .isEmpty();
        assertThat(reader.stringValue(FraudFeatureContract.RAPID_TRANSFER_TRANSACTION_IDS).status())
                .isEqualTo(FeatureSnapshotValueStatus.NOT_ALLOWED);
        assertThat(reader.stringValue(FraudFeatureContract.RAPID_TRANSFER_TRANSACTION_IDS).key())
                .isEqualTo(FeatureSnapshotValue.NOT_ALLOWED_REDACTED_KEY);
    }

    @Test
    void stringCategoricalFeaturesCanBeReadInternallyButNeedSeparateEvidencePolicy() {
        FeatureSnapshotReader reader = new FeatureSnapshotReader(Map.of(
                FraudFeatureContract.CUSTOMER_SEGMENT, "retail",
                FraudFeatureContract.MERCHANT_CATEGORY, "electronics"
        ));

        assertThat(reader.stringValue(FraudFeatureContract.CUSTOMER_SEGMENT).status())
                .isEqualTo(FeatureSnapshotValueStatus.PRESENT);
        assertThat(reader.stringValue(FraudFeatureContract.MERCHANT_CATEGORY).status())
                .isEqualTo(FeatureSnapshotValueStatus.PRESENT);
        // Evidence/log/UI exposure of these values is governed by docs, not by reader success.
    }

    @Test
    void notAllowedNeverExposesForbiddenOrRegisteredNonConsumableKey() {
        FeatureSnapshotReader reader = new FeatureSnapshotReader(Map.of(
                "rawPayload", "secret",
                FraudFeatureContract.RAPID_TRANSFER_TRANSACTION_IDS, List.of("tx-1"),
                FraudFeatureContract.FEATURE_FLAGS, List.of("DEVICE_NOVELTY")
        ));

        assertThat(reader.stringValue("rawPayload").key())
                .isEqualTo(FeatureSnapshotValue.NOT_ALLOWED_REDACTED_KEY)
                .doesNotContain("rawPayload");
        assertThat(reader.stringValue(FraudFeatureContract.RAPID_TRANSFER_TRANSACTION_IDS).key())
                .isEqualTo(FeatureSnapshotValue.NOT_ALLOWED_REDACTED_KEY)
                .doesNotContain(FraudFeatureContract.RAPID_TRANSFER_TRANSACTION_IDS);
        assertThat(reader.stringValue(FraudFeatureContract.FEATURE_FLAGS).key())
                .isEqualTo(FeatureSnapshotValue.NOT_ALLOWED_REDACTED_KEY)
                .doesNotContain(FraudFeatureContract.FEATURE_FLAGS);
    }

    @Test
    void defensivelyCopiesTopLevelMapWithoutExposingOrDeepCopyingNestedValues() {
        List<String> nested = new ArrayList<>(List.of("DEVICE_NOVELTY"));
        Map<String, Object> source = new HashMap<>();
        source.put(FraudFeatureContract.DEVICE_NOVELTY, true);
        source.put(FraudFeatureContract.CURRENCY, nested);

        FeatureSnapshotReader reader = new FeatureSnapshotReader(source);
        source.put(FraudFeatureContract.DEVICE_NOVELTY, false);
        source.put(FraudFeatureContract.CURRENCY, "PLN");
        nested.add("COUNTRY_MISMATCH");

        assertThat(reader.booleanValue(FraudFeatureContract.DEVICE_NOVELTY).value()).isTrue();
        assertThat(reader.stringValue(FraudFeatureContract.CURRENCY).actualType()).isEqualTo("ArrayList");
        assertThat(FeatureSnapshotReader.class.getDeclaredMethods())
                .noneMatch(method -> Map.class.isAssignableFrom(method.getReturnType()));
    }

    @Test
    void rawMapStillNotExposed() {
        assertThat(FeatureSnapshotReader.class.getDeclaredMethods())
                .noneMatch(method -> Map.class.isAssignableFrom(method.getReturnType()));
    }

    @Test
    void rejectsNullSnapshotKeysAndValuesWithContextAlignedMessages() {
        Map<String, Object> nullKey = new HashMap<>();
        nullKey.put(null, true);
        Map<String, Object> nullValue = new HashMap<>();
        nullValue.put(FraudFeatureContract.DEVICE_NOVELTY, null);

        assertThatThrownBy(() -> new FeatureSnapshotReader(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("featureSnapshot is required");
        assertThatThrownBy(() -> new FeatureSnapshotReader(nullKey))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("null keys");
        assertThatThrownBy(() -> new FeatureSnapshotReader(nullValue))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("null values");
        assertThatThrownBy(() -> new FeatureSnapshotReader(Map.of()).booleanValue(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("key is required");
    }
}
