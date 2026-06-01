package com.frauddetection.scoring.service;

import org.junit.jupiter.api.Test;

import static com.frauddetection.scoring.service.TransactionFraudScoringServiceEngineIntelligenceJoinedTestSupport.harness;
import static com.frauddetection.scoring.service.TransactionFraudScoringServiceEngineIntelligenceJoinedTestSupport.highDiagnosticSummary;
import static com.frauddetection.scoring.service.TransactionFraudScoringServiceEngineIntelligenceJoinedTestSupport.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TransactionFraudScoringServiceEngineIntelligenceJoinedPathTest {

    @Test
    void disabledFlagJoinedPathPublishesOldShapeAndDoesNotInvokeDiagnosticPipeline() {
        var harness = harness(false);

        var event = harness.scoreAndCapture();

        assertThat(event.engineIntelligence()).isNull();
        assertThat(json(event)).doesNotContain("\"engineIntelligence\"");
        verifyNoInteractions(harness.orchestrator(), harness.aggregation(), harness.mapper());
    }

    @Test
    void enabledFlagJoinedPathPublishesBoundedEngineIntelligence() {
        var harness = harness(true);
        var summary = highDiagnosticSummary();
        harness.stubSuccessfulEnrichment(summary);

        var event = harness.scoreAndCapture();

        assertThat(event.engineIntelligence()).isEqualTo(summary);
        assertThat(json(event)).contains("\"engineIntelligence\"");
        verify(harness.orchestrator()).evaluate(any());
        verify(harness.aggregation()).aggregate(any());
        verify(harness.mapper()).map(any());
    }

    @Test
    void enabledFlagJoinedPathFailurePublishesOldShape() {
        var harness = harness(true);
        when(harness.orchestrator().evaluate(any())).thenThrow(new IllegalStateException("raw-secret"));

        var event = harness.scoreAndCapture();

        assertThat(event.engineIntelligence()).isNull();
        assertThat(event.fraudScore()).isEqualTo(harness.baselineResult().fraudScore());
        assertThat(event.riskLevel()).isEqualTo(harness.baselineResult().riskLevel());
        assertThat(json(event)).doesNotContain("\"engineIntelligence\"", "raw-secret");
    }

    @Test
    void enabledDiagnosticHighDoesNotChangeBaselineLowOrCreateFinalDecisioning() {
        var harness = harness(true);
        harness.stubSuccessfulEnrichment(highDiagnosticSummary());

        var event = harness.scoreAndCapture();
        String json = json(event);

        assertThat(event.riskLevel()).isEqualTo(harness.baselineResult().riskLevel());
        assertThat(event.fraudScore()).isEqualTo(harness.baselineResult().fraudScore());
        assertThat(event.alertRecommended()).isEqualTo(harness.baselineResult().alertRecommended());
        assertThat(event.reasonCodes()).isEqualTo(harness.baselineResult().reasonCodes());
        assertThat(json).doesNotContain("finalDecision", "recommendedAction", "\"approve\"", "\"decline\"", "\"block\"");
    }
}
