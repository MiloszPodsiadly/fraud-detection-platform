package com.frauddetection.scoring.features;

import com.frauddetection.common.events.features.FraudFeatureContract;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

class FeatureSnapshotKeyPolicyTest {

    @Test
    void acceptsRegisteredCanonicalCamelCaseFeatureKeys() {
        Stream.concat(
                FraudFeatureContract.JAVA_ENRICHED_FEATURE_NAMES.stream(),
                FraudFeatureContract.ML_FEATURE_NAMES.stream()
        ).forEach(key -> assertThat(FeatureSnapshotKeyPolicy.isAllowedFeatureKey(key))
                .as("registered feature key %s", key)
                .isTrue());

        assertThat(FeatureSnapshotKeyPolicy.requireAllowedFeatureKey(FraudFeatureContract.DEVICE_NOVELTY))
                .isEqualTo("deviceNovelty");
    }

    @Test
    void rejectsUnregisteredOrNonCanonicalKeysInsteadOfCreatingASecondNamespace() {
        assertThat(FeatureSnapshotKeyPolicy.isAllowedFeatureKey("device.isnew")).isFalse();
        assertThat(FeatureSnapshotKeyPolicy.isAllowedFeatureKey("unknownSafeFeature")).isFalse();
        assertThat(FeatureSnapshotKeyPolicy.isAllowedFeatureKey("DeviceNovelty")).isFalse();
    }

    @Test
    void rejectsMalformedAndOversizedKeys() {
        assertThat(FeatureSnapshotKeyPolicy.isAllowedFeatureKey(null)).isFalse();
        assertThat(FeatureSnapshotKeyPolicy.isAllowedFeatureKey("")).isFalse();
        assertThat(FeatureSnapshotKeyPolicy.isAllowedFeatureKey("   ")).isFalse();
        assertThat(FeatureSnapshotKeyPolicy.isAllowedFeatureKey("device Novelty")).isFalse();
        assertThat(FeatureSnapshotKeyPolicy.isAllowedFeatureKey("device\nNovelty")).isFalse();
        assertThat(FeatureSnapshotKeyPolicy.isAllowedFeatureKey("a".repeat(129))).isFalse();
    }

    @Test
    void rejectsRawSensitiveAndDebugKeysWithPolicyContext() {
        for (String key : new String[]{"rawPayload", "authToken", "customerEmail", "debugException", "cardNumber"}) {
            assertThat(FeatureSnapshotKeyPolicy.isAllowedFeatureKey(key)).isFalse();
            assertThatThrownBy(() -> FeatureSnapshotKeyPolicy.requireAllowedFeatureKey(key))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("featureSnapshot key")
                    .hasMessageContaining("policy evaluation");
        }
    }

    @Test
    void requireAllowedFeatureKeyDoesNotExposeRejectedRawKey() {
        String rawKey = "authorizationBearerToken_very_sensitive";

        Throwable thrown = catchThrowable(() -> FeatureSnapshotKeyPolicy.requireAllowedFeatureKey(rawKey));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class);
        assertThat(thrown.getMessage())
                .contains("featureSnapshot key")
                .doesNotContain(rawKey, "authorization", "Bearer", "bearer", "Token", "token", "sensitive");
    }

    @Test
    void requireAllowedFeatureKeyDoesNotExposeOversizedRejectedKey() {
        String rawKey = "raw" + "x".repeat(500);

        Throwable thrown = catchThrowable(() -> FeatureSnapshotKeyPolicy.requireAllowedFeatureKey(rawKey));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class);
        assertThat(thrown.getMessage())
                .doesNotContain(rawKey)
                .hasSizeLessThan(160);
    }

    @Test
    void exposesExpectedTypesForScalarConsumableKeys() {
        assertThat(FeatureSnapshotKeyPolicy.expectedTypeFor(FraudFeatureContract.DEVICE_NOVELTY))
                .contains(FeatureSnapshotScalarType.BOOLEAN);
        assertThat(FeatureSnapshotKeyPolicy.expectedTypeFor(FraudFeatureContract.RECENT_TRANSACTION_COUNT))
                .contains(FeatureSnapshotScalarType.INTEGER);
        assertThat(FeatureSnapshotKeyPolicy.expectedTypeFor(FraudFeatureContract.RECENT_AMOUNT_SUM_WINDOW))
                .contains(FeatureSnapshotScalarType.LONG);
        assertThat(FeatureSnapshotKeyPolicy.expectedTypeFor(FraudFeatureContract.TRANSACTION_VELOCITY_PER_MINUTE))
                .contains(FeatureSnapshotScalarType.DOUBLE);
        assertThat(FeatureSnapshotKeyPolicy.expectedTypeFor(FraudFeatureContract.CURRENCY))
                .contains(FeatureSnapshotScalarType.STRING);
        assertThat(FeatureSnapshotKeyPolicy.expectedTypeFor(FraudFeatureContract.RAPID_TRANSFER_TOTAL_PLN))
                .contains(FeatureSnapshotScalarType.DECIMAL);
    }

    @Test
    void excludesNonScalarOrIdentifierBearingKeysFromAdapterConsumption() {
        assertThat(FeatureSnapshotKeyPolicy.expectedTypeFor(FraudFeatureContract.RAPID_TRANSFER_TRANSACTION_IDS))
                .isEqualTo(Optional.empty());
        assertThat(FeatureSnapshotKeyPolicy.expectedTypeFor(FraudFeatureContract.FEATURE_FLAGS))
                .isEqualTo(Optional.empty());
    }

    @Test
    void allowedKeyDoesNotMeanScalarConsumable() {
        assertThat(FeatureSnapshotKeyPolicy.isAllowedFeatureKey(FraudFeatureContract.RAPID_TRANSFER_TRANSACTION_IDS))
                .isTrue();
        assertThat(FeatureSnapshotKeyPolicy.expectedTypeFor(FraudFeatureContract.RAPID_TRANSFER_TRANSACTION_IDS))
                .isEmpty();
        assertThat(FeatureSnapshotKeyPolicy.isAllowedFeatureKey(FraudFeatureContract.FEATURE_FLAGS))
                .isTrue();
        assertThat(FeatureSnapshotKeyPolicy.expectedTypeFor(FraudFeatureContract.FEATURE_FLAGS))
                .isEmpty();
    }

    @Test
    void scalarConsumptionMustUseExpectedTypePolicy() {
        assertThat(FeatureSnapshotKeyPolicy.expectedTypeFor(FraudFeatureContract.CURRENCY))
                .contains(FeatureSnapshotScalarType.STRING);
        assertThat(FeatureSnapshotKeyPolicy.expectedTypeFor(FraudFeatureContract.FEATURE_FLAGS))
                .isEmpty();
    }
}
