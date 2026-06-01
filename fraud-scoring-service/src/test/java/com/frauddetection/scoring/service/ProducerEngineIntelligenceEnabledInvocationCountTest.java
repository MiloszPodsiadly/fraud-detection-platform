package com.frauddetection.scoring.service;

import org.junit.jupiter.api.Test;

import static com.frauddetection.scoring.service.TransactionFraudScoringServiceEngineIntelligenceJoinedTestSupport.harness;
import static com.frauddetection.scoring.service.TransactionFraudScoringServiceEngineIntelligenceJoinedTestSupport.highDiagnosticSummary;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class ProducerEngineIntelligenceEnabledInvocationCountTest {

    @Test
    void enabledFlagRunsBaselineAndDiagnosticPipelineOnce() {
        var harness = harness(true);
        harness.stubSuccessfulEnrichment(highDiagnosticSummary());

        harness.scoreAndCapture();

        verify(harness.baseline(), times(1)).score(harness.request());
        verify(harness.orchestrator(), times(1)).evaluate(any());
        verify(harness.aggregation(), times(1)).aggregate(any());
        verify(harness.mapper(), times(1)).map(any());
    }
}
