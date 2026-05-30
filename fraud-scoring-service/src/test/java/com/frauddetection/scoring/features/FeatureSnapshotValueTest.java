package com.frauddetection.scoring.features;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FeatureSnapshotValueTest {

    @Test
    void createsPresentValue() {
        FeatureSnapshotValue<Boolean> value = FeatureSnapshotValue.present("deviceNovelty", true);

        assertThat(value.status()).isEqualTo(FeatureSnapshotValueStatus.PRESENT);
        assertThat(value.value()).isTrue();
        assertThat(value.actualType()).isNull();
    }

    @Test
    void rejectsNullPresentValue() {
        assertThatThrownBy(() -> FeatureSnapshotValue.present("deviceNovelty", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PRESENT");
    }

    @Test
    void createsNonPresentValuesWithoutValue() {
        assertThat(FeatureSnapshotValue.missing("deviceNovelty").value()).isNull();
        assertThat(FeatureSnapshotValue.notAllowed("authToken").value()).isNull();
        assertThat(FeatureSnapshotValue.invalidType("deviceNovelty", "true").value()).isNull();
    }

    @Test
    void notAllowedFactoryRedactsRawKey() {
        FeatureSnapshotValue<String> value = FeatureSnapshotValue.notAllowed("rawPayload.secret.token");

        assertThat(value.status()).isEqualTo(FeatureSnapshotValueStatus.NOT_ALLOWED);
        assertThat(value.key()).isEqualTo(FeatureSnapshotValue.NOT_ALLOWED_REDACTED_KEY);
        assertThat(value.key()).doesNotContain("rawPayload", "secret", "token");
        assertThat(value.value()).isNull();
        assertThat(value.actualType()).isNull();
    }

    @Test
    void recordsOnlySafeActualTypeForInvalidValue() {
        SensitiveValue actualValue = new SensitiveValue();

        FeatureSnapshotValue<Boolean> value = FeatureSnapshotValue.invalidType("deviceNovelty", actualValue);

        assertThat(value.status()).isEqualTo(FeatureSnapshotValueStatus.INVALID_TYPE);
        assertThat(value.actualType()).isEqualTo("SensitiveValue").doesNotContain("raw-secret");
    }

    @Test
    void rejectsInvalidRecordCombinationsAndMissingRequiredFields() {
        assertThatThrownBy(() -> FeatureSnapshotValue.missing(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new FeatureSnapshotValue<>("deviceNovelty", null, null, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new FeatureSnapshotValue<>(
                "deviceNovelty",
                FeatureSnapshotValueStatus.MISSING,
                true,
                null
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FeatureSnapshotValue<>(
                "deviceNovelty",
                FeatureSnapshotValueStatus.INVALID_TYPE,
                null,
                null
        )).isInstanceOf(IllegalArgumentException.class);
    }

    private static final class SensitiveValue {
        @Override
        public String toString() {
            return "raw-secret";
        }
    }
}
