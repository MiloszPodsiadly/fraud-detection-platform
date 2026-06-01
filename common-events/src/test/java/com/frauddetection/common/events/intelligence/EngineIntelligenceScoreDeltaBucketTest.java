package com.frauddetection.common.events.intelligence;

import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class EngineIntelligenceScoreDeltaBucketTest {

    @Test
    void missingDeltaDoesNotBecomeNoneUnlessExplicitlyZeroComparableDelta() {
        assertThat(EngineIntelligenceScoreDeltaBucket.fromComparableDelta(null))
                .isEqualTo(EngineIntelligenceScoreDeltaBucket.UNAVAILABLE);
        assertThat(EngineIntelligenceScoreDeltaBucket.fromComparableDelta(0.0d))
                .isEqualTo(EngineIntelligenceScoreDeltaBucket.NONE);
    }

    @Test
    void unavailableDeltaSerializesAsUnavailable() throws Exception {
        assertThat(EngineIntelligenceTestSupport.objectMapper().writeValueAsString(new EngineIntelligenceComparison(
                EngineIntelligenceAgreementStatus.INSUFFICIENT_DATA,
                EngineIntelligenceRiskMismatchStatus.NOT_COMPARABLE,
                EngineIntelligenceScoreDeltaBucket.UNAVAILABLE
        ))).contains("\"scoreDeltaBucket\":\"UNAVAILABLE\"");
    }

    @Test
    void publicComparisonDoesNotExposeRawDelta() {
        assertThat(Arrays.stream(EngineIntelligenceComparison.class.getRecordComponents()).map(RecordComponent::getName))
                .doesNotContain("scoreDelta", "absoluteDelta", "rawDelta");
    }

    @Test
    void scoreDeltaBucketIsSerializedAsEnumString() throws Exception {
        assertThat(EngineIntelligenceTestSupport.objectMapper().writeValueAsString(EngineIntelligenceTestSupport.comparison()))
                .contains("\"scoreDeltaBucket\":\"SMALL\"");
    }
}
