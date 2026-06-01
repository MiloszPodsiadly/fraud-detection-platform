package com.frauddetection.common.events.intelligence;

import com.frauddetection.common.events.engine.FraudEngineStatus;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class EngineIntelligenceScoreBucketTest {

    @Test
    void missingScoreDoesNotBecomeLow() {
        assertThat(EngineIntelligenceScoreBucket.from(FraudEngineStatus.AVAILABLE, null))
                .isEqualTo(EngineIntelligenceScoreBucket.UNAVAILABLE)
                .isNotEqualTo(EngineIntelligenceScoreBucket.LOW);
    }

    @Test
    void missingScoreDoesNotBecomeZero() {
        assertThat(EngineIntelligenceScoreBucket.from(FraudEngineStatus.AVAILABLE, null))
                .isNotEqualTo(EngineIntelligenceScoreBucket.from(FraudEngineStatus.AVAILABLE, 0.0d));
    }

    @Test
    void timeoutScoreBucketIsUnavailable() {
        assertUnavailable(FraudEngineStatus.TIMEOUT);
    }

    @Test
    void unavailableScoreBucketIsUnavailable() {
        assertUnavailable(FraudEngineStatus.UNAVAILABLE);
    }

    @Test
    void degradedScoreBucketIsUnavailable() {
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
