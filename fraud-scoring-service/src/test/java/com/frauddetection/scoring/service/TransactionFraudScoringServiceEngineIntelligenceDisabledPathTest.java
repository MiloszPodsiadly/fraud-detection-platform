package com.frauddetection.scoring.service;

import org.junit.jupiter.api.Test;

import static com.frauddetection.scoring.service.TransactionFraudScoringServiceEngineIntelligenceJoinedTestSupport.harness;
import static com.frauddetection.scoring.service.TransactionFraudScoringServiceEngineIntelligenceJoinedTestSupport.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

class TransactionFraudScoringServiceEngineIntelligenceDisabledPathTest {

    @Test
    void defaultDisabledPublishesOldShapeEventWithoutDiagnosticWork() {
        var harness = harness(false);

        var event = harness.scoreAndCapture();

        assertThat(event.engineIntelligence()).isNull();
        assertThat(json(event)).doesNotContain("\"engineIntelligence\"");
        verifyNoInteractions(harness.orchestrator(), harness.aggregation(), harness.mapper());
    }

    @Test
    void disabledPathDoesNotChangeBaseFields() {
        var harness = harness(false);

        var event = harness.scoreAndCapture();

        assertThat(event.fraudScore()).isEqualTo(harness.baselineResult().fraudScore());
        assertThat(event.riskLevel()).isEqualTo(harness.baselineResult().riskLevel());
        assertThat(event.alertRecommended()).isEqualTo(harness.baselineResult().alertRecommended());
        assertThat(event.reasonCodes()).isEqualTo(harness.baselineResult().reasonCodes());
        assertThat(event.scoringEvidence()).isEqualTo(harness.baselineResult().scoringEvidence());
        assertThat(event.scoreDetails()).isEqualTo(harness.baselineResult().scoreDetails());
    }
}
