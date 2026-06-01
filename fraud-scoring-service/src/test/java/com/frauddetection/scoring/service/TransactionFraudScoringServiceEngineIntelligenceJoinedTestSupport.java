package com.frauddetection.scoring.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.frauddetection.common.events.contract.TransactionEnrichedEvent;
import com.frauddetection.common.events.contract.TransactionScoredEvent;
import com.frauddetection.common.events.engine.FraudEngineStatus;
import com.frauddetection.common.events.engine.FraudEngineType;
import com.frauddetection.common.events.enums.RiskLevel;
import com.frauddetection.common.events.intelligence.EngineIntelligenceAgreementStatus;
import com.frauddetection.common.events.intelligence.EngineIntelligenceComparison;
import com.frauddetection.common.events.intelligence.EngineIntelligenceEngineResult;
import com.frauddetection.common.events.intelligence.EngineIntelligenceRiskMismatchStatus;
import com.frauddetection.common.events.intelligence.EngineIntelligenceScoreBucket;
import com.frauddetection.common.events.intelligence.EngineIntelligenceScoreDeltaBucket;
import com.frauddetection.common.events.intelligence.EngineIntelligenceSummary;
import com.frauddetection.common.testsupport.fixture.TransactionFixtures;
import com.frauddetection.scoring.config.EngineIntelligenceEmissionProperties;
import com.frauddetection.scoring.config.ScoringMode;
import com.frauddetection.scoring.config.ScoringProperties;
import com.frauddetection.scoring.context.ScoringContextFactory;
import com.frauddetection.scoring.domain.FraudScoreResult;
import com.frauddetection.scoring.domain.FraudScoringRequest;
import com.frauddetection.scoring.mapper.TransactionScoredEventMapper;
import com.frauddetection.scoring.messaging.TransactionScoredEventPublisher;
import com.frauddetection.scoring.observability.ScoringMetrics;
import com.frauddetection.scoring.orchestration.FraudScoringOrchestrationResult;
import com.frauddetection.scoring.orchestration.FraudScoringOrchestrator;
import com.frauddetection.scoring.orchestration.aggregation.EngineIntelligenceDiagnosticEnrichmentPipeline;
import com.frauddetection.scoring.orchestration.aggregation.EngineIntelligenceEmissionService;
import com.frauddetection.scoring.orchestration.aggregation.FraudEngineAggregationResult;
import com.frauddetection.scoring.orchestration.aggregation.FraudEngineAggregationService;
import com.frauddetection.scoring.orchestration.aggregation.OrchestratedEngineIntelligenceDiagnosticEnrichmentPipeline;
import com.frauddetection.scoring.orchestration.aggregation.PublicEngineIntelligenceMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

final class TransactionFraudScoringServiceEngineIntelligenceJoinedTestSupport {

    private TransactionFraudScoringServiceEngineIntelligenceJoinedTestSupport() {
    }

    static Harness harness(boolean enabled) {
        TransactionEnrichedEvent input = TransactionFixtures.enrichedTransaction().build();
        FraudScoringRequest request = FraudScoringRequest.from(input);
        FraudScoreResult baselineResult = baselineLowResult();
        FraudScoringEngine baseline = mock(FraudScoringEngine.class);
        TransactionScoredEventPublisher publisher = mock(TransactionScoredEventPublisher.class);
        FraudScoringOrchestrator orchestrator = mock(FraudScoringOrchestrator.class);
        FraudEngineAggregationService aggregation = mock(FraudEngineAggregationService.class);
        PublicEngineIntelligenceMapper mapper = mock(PublicEngineIntelligenceMapper.class);
        EngineIntelligenceDiagnosticEnrichmentPipeline pipeline =
                new OrchestratedEngineIntelligenceDiagnosticEnrichmentPipeline(
                        new ScoringContextFactory(),
                        new ScoringProperties(0.75d, 0.90d, ScoringMode.RULE_BASED),
                        orchestrator,
                        aggregation,
                        mapper,
                        Clock.systemUTC()
                );
        EngineIntelligenceEmissionService emissionService = new EngineIntelligenceEmissionService(
                new EngineIntelligenceEmissionProperties(enabled),
                provider(pipeline)
        );
        when(baseline.score(request)).thenReturn(baselineResult);
        var service = new TransactionFraudScoringService(
                baseline,
                new TransactionScoredEventMapper(),
                publisher,
                new ScoringProperties(0.75d, 0.90d, ScoringMode.RULE_BASED),
                new ScoringMetrics(new SimpleMeterRegistry()),
                emissionService
        );
        return new Harness(
                input,
                request,
                baselineResult,
                baseline,
                publisher,
                orchestrator,
                aggregation,
                mapper,
                service
        );
    }

    static FraudScoreResult baselineLowResult() {
        return new FraudScoreResult(
                0.12d,
                RiskLevel.LOW,
                "RULE_BASED",
                "rule-based-engine",
                "v1",
                Instant.parse("2026-05-31T10:00:00Z"),
                List.of(),
                Map.of("finalScore", 0.12d),
                Map.of("feature", "stable"),
                Map.of(),
                false
        );
    }

    static EngineIntelligenceSummary highDiagnosticSummary() {
        return new EngineIntelligenceSummary(
                EngineIntelligenceSummary.CONTRACT_VERSION,
                Instant.parse("2026-05-31T10:00:01Z"),
                List.of(new EngineIntelligenceEngineResult(
                        "rules.primary",
                        FraudEngineType.RULES,
                        FraudEngineStatus.AVAILABLE,
                        RiskLevel.HIGH,
                        EngineIntelligenceScoreBucket.HIGH,
                        List.of("HIGH_VELOCITY")
                )),
                new EngineIntelligenceComparison(
                        EngineIntelligenceAgreementStatus.DISAGREEMENT,
                        EngineIntelligenceRiskMismatchStatus.MATERIAL_RISK_MISMATCH,
                        EngineIntelligenceScoreDeltaBucket.LARGE
                ),
                List.of(),
                List.of()
        );
    }

    static String json(TransactionScoredEvent event) {
        try {
            return new ObjectMapper().findAndRegisterModules().writeValueAsString(event);
        } catch (Exception exception) {
            throw new IllegalStateException("TEST_JSON_SERIALIZATION_FAILED", exception);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }

    record Harness(
            TransactionEnrichedEvent input,
            FraudScoringRequest request,
            FraudScoreResult baselineResult,
            FraudScoringEngine baseline,
            TransactionScoredEventPublisher publisher,
            FraudScoringOrchestrator orchestrator,
            FraudEngineAggregationService aggregation,
            PublicEngineIntelligenceMapper mapper,
            TransactionFraudScoringService service
    ) {
        void stubSuccessfulEnrichment(EngineIntelligenceSummary summary) {
            FraudScoringOrchestrationResult orchestration = mock(FraudScoringOrchestrationResult.class);
            FraudEngineAggregationResult aggregationResult = mock(FraudEngineAggregationResult.class);
            when(orchestrator.evaluate(any())).thenReturn(orchestration);
            when(aggregation.aggregate(orchestration)).thenReturn(aggregationResult);
            when(mapper.map(aggregationResult)).thenReturn(summary);
        }

        TransactionScoredEvent scoreAndCapture() {
            service.score(input);
            ArgumentCaptor<TransactionScoredEvent> captor = ArgumentCaptor.forClass(TransactionScoredEvent.class);
            verify(publisher).publish(captor.capture());
            return captor.getValue();
        }
    }
}
