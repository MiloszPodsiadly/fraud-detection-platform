package com.frauddetection.common.events.intelligence;

import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EngineIntelligenceWarningSummaryTest {

    @Test
    void rejectsNegativeCount() {
        assertThatThrownBy(() -> new EngineIntelligenceWarningSummary(
                EngineIntelligenceWarningCode.EVIDENCE_UNSAFE_DROPPED,
                -1
        )).hasMessage("ENGINE_INTELLIGENCE_WARNING_COUNT_NEGATIVE");
    }

    @Test
    void acceptsZeroOrPositiveCount() {
        assertThat(new EngineIntelligenceWarningSummary(
                EngineIntelligenceWarningCode.EVIDENCE_UNSAFE_DROPPED,
                0
        ).count()).isZero();
    }

    @Test
    void codeIsBounded() {
        assertThat(EngineIntelligenceWarningCode.values()).hasSize(13);
    }

    @Test
    void warningSummaryDoesNotExposeRawDetails() {
        assertThat(Arrays.stream(EngineIntelligenceWarningSummary.class.getRecordComponents()).map(RecordComponent::getName))
                .containsExactly("code", "count");
    }

    @Test
    void warningSummarySerializesAsCodeAndCountOnly() throws Exception {
        assertThat(EngineIntelligenceTestSupport.objectMapper().writeValueAsString(EngineIntelligenceTestSupport.warning()))
                .isEqualTo("{\"code\":\"EVIDENCE_UNSAFE_DROPPED\",\"count\":1}");
    }
}
