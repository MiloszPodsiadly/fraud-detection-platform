package com.frauddetection.scoring.service;

import com.frauddetection.common.events.contract.TransactionScoredEvent;
import com.frauddetection.scoring.orchestration.aggregation.EngineIntelligenceEmissionService;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static com.frauddetection.scoring.service.TransactionFraudScoringServiceEngineIntelligenceTestSupport.harness;
import static com.frauddetection.scoring.service.TransactionFraudScoringServiceEngineIntelligenceTestSupport.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TransactionFraudScoringServiceEngineIntelligenceFailureIsolationTest {

    @Test
    void enrichmentExceptionDoesNotBlockEventPublishing() {
        var harness = throwingEnrichmentHarness();
        harness.service().score(harness.input());
        verify(harness.publisher()).publish(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void enrichmentExceptionPublishesEventWithoutEngineIntelligence() {
        assertThat(throwingEnrichmentHarness().scoreAndCapture().engineIntelligence()).isNull();
    }

    @Test
    void enrichmentExceptionDoesNotChangeFraudScore() {
        assertThat(throwingEnrichmentHarness().scoreAndCapture().fraudScore()).isEqualTo(0.91d);
    }

    @Test
    void enrichmentExceptionDoesNotChangeRiskLevel() {
        assertThat(throwingEnrichmentHarness().scoreAndCapture().riskLevel())
                .isEqualTo(TransactionFraudScoringServiceEngineIntelligenceTestSupport.scoreResult().riskLevel());
    }

    @Test
    void enrichmentExceptionDoesNotChangeAlertRecommended() {
        assertThat(throwingEnrichmentHarness().scoreAndCapture().alertRecommended()).isTrue();
    }

    @Test
    void enrichmentExceptionDoesNotLeakRawExceptionIntoSerializedEvent() throws Exception {
        TransactionScoredEvent event = throwingEnrichmentHarness().scoreAndCapture();
        assertThat(json(event)).doesNotContain("raw-secret-must-not-leak", "\"engineIntelligence\"");
    }

    @Test
    void baselineScoringExceptionStillFailsNormally() {
        var harness = harness(Optional.empty());
        when(harness.scoringEngine().score(harness.request())).thenThrow(new IllegalStateException("baseline-failure"));
        assertThatThrownBy(() -> harness.service().score(harness.input()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("baseline-failure");
    }

    private TransactionFraudScoringServiceEngineIntelligenceTestSupport.Harness throwingEnrichmentHarness() {
        var harness = harness(Optional.empty());
        doThrow(new IllegalStateException("raw-secret-must-not-leak"))
                .when(harness.emissionService()).emitIfEnabled(harness.request());
        return harness;
    }
}
