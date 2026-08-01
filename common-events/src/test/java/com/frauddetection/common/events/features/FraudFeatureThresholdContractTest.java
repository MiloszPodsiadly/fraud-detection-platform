package com.frauddetection.common.events.features;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FraudFeatureThresholdContractTest {

    @Test
    void velocityV1ObservationWindowIsAuthoritativeOneMinuteDuration() {
        assertThat(FraudFeatureThresholdContract.VELOCITY_V1_OBSERVATION_WINDOW)
                .isEqualTo(Duration.parse("PT1M"));
        assertThat(FraudFeatureThresholdContract.VELOCITY_V1_OBSERVATION_WINDOW.toString()).isEqualTo("PT1M");
    }

    @Test
    void rapidTransferPredicateUsesSharedCountAndAmountBoundaries() {
        assertThat(FraudFeatureThresholdContract.isRapidTransferPlnBurst(1, new BigDecimal("20000.00"))).isFalse();
        assertThat(FraudFeatureThresholdContract.isRapidTransferPlnBurst(2, new BigDecimal("19999.99"))).isFalse();
        assertThat(FraudFeatureThresholdContract.isRapidTransferPlnBurst(2, new BigDecimal("20000.00"))).isTrue();
        assertThat(FraudFeatureThresholdContract.isRapidTransferPlnBurst(3, new BigDecimal("20000.01"))).isTrue();
    }

    @Test
    void rapidTransferPredicateRejectsInvalidFactsBeforeClassification() {
        assertThatThrownBy(() -> FraudFeatureThresholdContract.isRapidTransferPlnBurst(-1, BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FraudFeatureThresholdContract.isRapidTransferPlnBurst(1, new BigDecimal("-0.01")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FraudFeatureThresholdContract.isRapidTransferPlnBurst(1, null))
                .isInstanceOf(NullPointerException.class);
    }
}
