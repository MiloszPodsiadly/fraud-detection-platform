package com.frauddetection.common.events.intelligence;

import com.frauddetection.common.events.engine.FraudEngineStatus;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class EngineIntelligenceScoreBucketTest {

    @Test
    void noneIsNotReturnedForMissingScore() {
        assertThat(EngineIntelligenceScoreBucket.from(FraudEngineStatus.AVAILABLE, null))
                .isNotEqualTo(EngineIntelligenceScoreBucket.NONE);
    }

    @Test
    void noneIsNotReturnedForZeroScore() {
        assertThat(EngineIntelligenceScoreBucket.from(FraudEngineStatus.AVAILABLE, 0.0d))
                .isNotEqualTo(EngineIntelligenceScoreBucket.NONE);
    }

    @Test
    void zeroScoreMapsToLow() {
        assertThat(EngineIntelligenceScoreBucket.from(FraudEngineStatus.AVAILABLE, 0.0d))
                .isEqualTo(EngineIntelligenceScoreBucket.LOW);
    }

    @Test
    void missingScoreMapsToUnavailable() {
        assertThat(EngineIntelligenceScoreBucket.from(FraudEngineStatus.AVAILABLE, null))
                .isEqualTo(EngineIntelligenceScoreBucket.UNAVAILABLE);
    }

    @Test
    void timeoutMapsToUnavailable() {
        assertUnavailable(FraudEngineStatus.TIMEOUT);
    }

    @Test
    void unavailableMapsToUnavailable() {
        assertUnavailable(FraudEngineStatus.UNAVAILABLE);
    }

    @Test
    void degradedMapsToUnavailable() {
        assertUnavailable(FraudEngineStatus.DEGRADED);
    }

    @Test
    void publicDtoDoesNotExposeRawScoreField() {
        assertThat(Arrays.stream(EngineIntelligenceEngineResult.class.getRecordComponents()).map(RecordComponent::getName))
                .doesNotContain("score", "rawScore");
    }

    @Test
    void scoreBucketIsSerializedAsEnumString() throws Exception {
        assertThat(EngineIntelligenceTestSupport.objectMapper().writeValueAsString(EngineIntelligenceTestSupport.engine()))
                .contains("\"scoreBucket\":\"HIGH\"");
    }

    private void assertUnavailable(FraudEngineStatus status) {
        assertThat(EngineIntelligenceScoreBucket.from(status, 0.9d)).isEqualTo(EngineIntelligenceScoreBucket.UNAVAILABLE);
    }
}
