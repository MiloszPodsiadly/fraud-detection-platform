package com.frauddetection.scoring.orchestration.aggregation;

import com.frauddetection.common.events.enums.RiskLevel;
import com.frauddetection.common.events.intelligence.EngineIntelligenceSummary;
import com.frauddetection.scoring.domain.FraudScoreResult;
import com.frauddetection.scoring.domain.FraudScoringRequest;
import com.frauddetection.scoring.orchestration.FraudScoringOrchestrationResult;
import com.frauddetection.scoring.orchestration.FraudScoringOrchestrator;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static com.frauddetection.scoring.orchestration.aggregation.EngineIntelligenceEmissionTestSupport.pipeline;
import static com.frauddetection.scoring.orchestration.aggregation.EngineIntelligenceEmissionTestSupport.request;
import static com.frauddetection.scoring.orchestration.aggregation.EngineIntelligenceEmissionTestSupport.service;

class ProducerEngineIntelligenceRuntimeEnrichmentSourceTest {

    private final FraudScoringRequest request = request();
    private final FraudScoringOrchestrationResult orchestration = mock(FraudScoringOrchestrationResult.class);
    private final FraudEngineAggregationResult aggregation = mock(FraudEngineAggregationResult.class);
    private final EngineIntelligenceSummary summary = mock(EngineIntelligenceSummary.class);
    private final FraudScoringOrchestrator orchestrator = mock(FraudScoringOrchestrator.class);
    private final FraudEngineAggregationService aggregationService = mock(FraudEngineAggregationService.class);
    private final PublicEngineIntelligenceMapper mapper = mock(PublicEngineIntelligenceMapper.class);

    @Test
    void disabledFlagDoesNotInvokeOrchestrator() {
        assertThat(service(false).emitIfEnabled(request)).isEmpty();
        verifyNoInteractions(orchestrator);
    }

    @Test
    void disabledFlagDoesNotInvokeAggregation() {
        assertThat(service(false).emitIfEnabled(request)).isEmpty();
        verifyNoInteractions(aggregationService);
    }

    @Test
    void enabledFlagInvokesOrchestratorOnce() {
        stubSuccessfulEnrichment();
        service(true).emitIfEnabled(request);
        verify(orchestrator).evaluate(any());
    }

    @Test
    void enabledFlagInvokesAggregationOnce() {
        stubSuccessfulEnrichment();
        service(true).emitIfEnabled(request);
        verify(aggregationService).aggregate(orchestration);
    }

    @Test
    void enabledFlagMapsAggregationToPublicSummary() {
        stubSuccessfulEnrichment();
        assertThat(service(true).emitIfEnabled(request)).contains(summary);
        verify(mapper).map(aggregation);
    }

    @Test
    void enabledFlagDoesNotModifyBaselineScoreResult() {
        FraudScoreResult baseline = baselineScore();
        stubSuccessfulEnrichment();
        service(true).emitIfEnabled(request);
        assertThat(baseline).isEqualTo(baselineScore());
    }

    @Test
    void orchestratorFailureReturnsEmptyOptional() {
        when(orchestrator.evaluate(any())).thenThrow(new IllegalStateException("raw-secret"));
        assertThat(service(true).emitIfEnabled(request)).isEmpty();
        verify(aggregationService, never()).aggregate(any());
    }

    @Test
    void aggregationFailureReturnsEmptyOptional() {
        when(orchestrator.evaluate(any())).thenReturn(orchestration);
        when(aggregationService.aggregate(orchestration)).thenThrow(new IllegalStateException("raw-secret"));
        assertThat(service(true).emitIfEnabled(request)).isEmpty();
        verify(mapper, never()).map(any());
    }

    private void stubSuccessfulEnrichment() {
        when(orchestrator.evaluate(any())).thenReturn(orchestration);
        when(aggregationService.aggregate(orchestration)).thenReturn(aggregation);
        when(mapper.map(aggregation)).thenReturn(summary);
    }

    private EngineIntelligenceEmissionService service(boolean enabled) {
        return EngineIntelligenceEmissionTestSupport.service(
                enabled,
                pipeline(orchestrator, aggregationService, mapper)
        );
    }

    private FraudScoreResult baselineScore() {
        return new FraudScoreResult(
                0.91d,
                RiskLevel.CRITICAL,
                "RULE_BASED",
                "rule-based-engine",
                "v1",
                Instant.parse("2026-05-31T10:00:00Z"),
                List.of("HIGH_VELOCITY"),
                Map.of(),
                Map.of(),
                Map.of(),
                true
        );
    }
}
