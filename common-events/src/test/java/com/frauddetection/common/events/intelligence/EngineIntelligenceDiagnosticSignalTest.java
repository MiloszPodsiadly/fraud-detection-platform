package com.frauddetection.common.events.intelligence;

import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class EngineIntelligenceDiagnosticSignalTest {

    @Test
    void diagnosticSignalUsesBoundedFieldsOnly() {
        assertThat(fields()).containsExactly(
                "engineId", "engineType", "engineStatus", "signalCategory", "riskLevel", "scoreBucket", "reasonCode"
        );
    }

    @Test
    void diagnosticSignalDoesNotExposeRawScore() {
        assertThat(fields()).doesNotContain("score", "rawScore");
    }

    @Test
    void diagnosticSignalDoesNotExposeEvidenceText() {
        assertThat(fields()).doesNotContain("evidence", "evidenceTitle", "evidenceDescription", "description");
    }

    @Test
    void diagnosticSignalDoesNotExposeContributionValue() {
        assertThat(fields()).doesNotContain("contribution", "contributionValue", "rawValue");
    }

    @Test
    void diagnosticSignalDoesNotContainRecommendedAction() {
        assertThat(fields()).doesNotContain("recommendedAction", "recommendation");
    }

    @Test
    void diagnosticSignalDoesNotContainFinalExplanation() {
        assertThat(fields()).doesNotContain("finalExplanation", "paymentDecisionRationale");
    }

    @Test
    void diagnosticSignalSerializesWithStableFieldNames() throws Exception {
        assertThat(EngineIntelligenceTestSupport.objectMapper().writeValueAsString(EngineIntelligenceTestSupport.signal()))
                .contains(
                        "\"engineId\":\"rules.primary\"",
                        "\"engineType\":\"RULES\"",
                        "\"engineStatus\":\"AVAILABLE\"",
                        "\"signalCategory\":\"FRAUD_SIGNAL\"",
                        "\"riskLevel\":\"HIGH\"",
                        "\"scoreBucket\":\"HIGH\"",
                        "\"reasonCode\":\"HIGH_VELOCITY\""
                );
    }

    private java.util.List<String> fields() {
        return Arrays.stream(EngineIntelligenceDiagnosticSignal.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();
    }
}
