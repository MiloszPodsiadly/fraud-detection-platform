package com.frauddetection.common.events.features;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VelocityFeatureContractTest {

    @Test
    void canonicalWindowIsAuthoritativeOneMinuteText() {
        assertThat(VelocityFeatureContract.CANONICAL_RECENT_TRANSACTION_COUNT_WINDOW)
                .isEqualTo(Duration.parse("PT1M"));
        assertThat(VelocityFeatureContract.CANONICAL_RECENT_TRANSACTION_COUNT_WINDOW_TEXT).isEqualTo("PT1M");
        assertThat(VelocityFeatureContract.isCanonicalWindowText("PT1M")).isTrue();
        assertThat(VelocityFeatureContract.isCanonicalWindowText("PT60S")).isFalse();
    }

    @Test
    void expectedRatePerMinuteUsesProducerRoundingRule() {
        assertThat(VelocityFeatureContract.expectedRatePerMinute(0)).isEqualTo(0.0d);
        assertThat(VelocityFeatureContract.expectedRatePerMinute(1)).isEqualTo(1.0d);
        assertThat(VelocityFeatureContract.expectedRatePerMinute(5)).isEqualTo(5.0d);
    }

    @Test
    void consistencyAllowsRepresentationNoiseButRejectsBusinessMismatch() {
        assertThat(VelocityFeatureContract.isRateConsistentWithCount(
                5,
                5.0d + VelocityFeatureContract.RATE_CONSISTENCY_TOLERANCE
        )).isTrue();
        assertThat(VelocityFeatureContract.isRateConsistentWithCount(
                5,
                5.0d + VelocityFeatureContract.RATE_CONSISTENCY_TOLERANCE + 0.00001d
        )).isFalse();
    }

    @Test
    void velocityFactsAreBounded() {
        assertThat(VelocityFeatureContract.isWithinBounds(0)).isTrue();
        assertThat(VelocityFeatureContract.isWithinBounds(-1)).isFalse();
        assertThat(VelocityFeatureContract.isWithinBounds(Double.NaN)).isFalse();
        assertThat(VelocityFeatureContract.isWithinBounds(Double.POSITIVE_INFINITY)).isFalse();
        assertThat(VelocityFeatureContract.isWithinBounds(BigDecimal.ZERO)).isTrue();
        assertThat(VelocityFeatureContract.isWithinBounds(new BigDecimal("-0.01"))).isFalse();
    }

    @Test
    void expectedRateRejectsOutOfBoundsCount() {
        assertThatThrownBy(() -> VelocityFeatureContract.expectedRatePerMinute(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
