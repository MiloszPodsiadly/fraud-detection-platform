package com.frauddetection.scoring.service;

import tools.jackson.databind.ObjectMapper;
import com.frauddetection.common.events.contract.TransactionEnrichedEvent;
import com.frauddetection.common.events.contract.TransactionScoredEvent;
import com.frauddetection.common.events.engine.FraudEngineResult;
import com.frauddetection.common.events.engine.FraudEngineStatus;
import com.frauddetection.common.events.engine.FraudEngineType;
import com.frauddetection.common.events.enums.RiskLevel;
import com.frauddetection.common.events.intelligence.EngineIntelligenceAgreementStatus;
import com.frauddetection.common.events.intelligence.EngineIntelligenceComparison;
import com.frauddetection.common.events.intelligence.EngineIntelligenceComparisonType;
import com.frauddetection.common.events.intelligence.EngineIntelligenceEngineResult;
import com.frauddetection.common.events.intelligence.EngineIntelligenceRiskMismatchStatus;
import com.frauddetection.common.events.intelligence.EngineIntelligenceScoreBucket;
import com.frauddetection.common.events.intelligence.EngineIntelligenceScoreDeltaBucket;
import com.frauddetection.common.events.intelligence.EngineIntelligenceSummary;
import com.frauddetection.common.events.intelligence.MlModelIdentity;
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
import com.frauddetection.scoring.engine.ml.PythonMlSignalEngine;
import com.frauddetection.scoring.engine.rules.RuleBasedSignalEngine;
import com.frauddetection.scoring.features.FeatureSnapshotReaderFactory;
import com.frauddetection.scoring.orchestration.FraudScoringOrchestrationResult;
import com.frauddetection.scoring.orchestration.FraudScoringOrchestrator;
import com.frauddetection.scoring.orchestration.FraudSignalEngineRegistry;
import com.frauddetection.scoring.orchestration.aggregation.EngineIntelligenceDiagnosticEnrichmentPipeline;
import com.frauddetection.scoring.orchestration.aggregation.EngineIntelligenceEmissionService;
import com.frauddetection.scoring.orchestration.aggregation.EngineIntelligenceEmissionResult;
import com.frauddetection.scoring.orchestration.aggregation.EngineIntelligenceEnrichmentResult;
import com.frauddetection.scoring.orchestration.aggregation.FraudEngineAggregationResult;
import com.frauddetection.scoring.orchestration.aggregation.FraudEngineAggregationPolicy;
import com.frauddetection.scoring.orchestration.aggregation.FraudEngineAggregationService;
import com.frauddetection.scoring.orchestration.aggregation.NormalizedFraudEngineResult;
import com.frauddetection.scoring.orchestration.aggregation.OrchestratedEngineIntelligenceDiagnosticEnrichmentPipeline;
import com.frauddetection.scoring.orchestration.aggregation.NoOpEngineIntelligenceEmissionMetrics;
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
                provider(pipeline),
                new NoOpEngineIntelligenceEmissionMetrics()
        );
        when(baseline.score(request)).thenReturn(baselineResult);
        var service = new TransactionFraudScoringService(
                baseline,
                new TransactionScoredEventMapper(),
                publisher,
                new ScoringProperties(0.75d, 0.90d, ScoringMode.RULE_BASED),
                new ScoringMetrics(new SimpleMeterRegistry()),
                emissionService,
                new AnalystRecommendationService()
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
                List.of("LOW_MODEL_RISK"),
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
                List.of(
                        new EngineIntelligenceEngineResult(
                                "rules.primary",
                                FraudEngineType.RULES,
                                FraudEngineStatus.AVAILABLE,
                                RiskLevel.HIGH,
                                EngineIntelligenceScoreBucket.HIGH,
                                List.of("HIGH_VELOCITY")
                        ),
                        new EngineIntelligenceEngineResult(
                                "ml.python.primary",
                                FraudEngineType.ML_MODEL,
                                FraudEngineStatus.AVAILABLE,
                                RiskLevel.LOW,
                                EngineIntelligenceScoreBucket.LOW,
                                List.of("LOW_MODEL_RISK"),
                                new MlModelIdentity(
                                        "python-logistic-fraud-model",
                                        "model-X",
                                        "feature-contract-v2"
                                )
                        )
                ),
                new EngineIntelligenceComparison(
                        EngineIntelligenceComparisonType.RULES_VS_ML,
                        List.of("rules.primary", "ml.python.primary"),
                        EngineIntelligenceAgreementStatus.DISAGREEMENT,
                        EngineIntelligenceRiskMismatchStatus.MATERIAL_RISK_MISMATCH,
                        EngineIntelligenceScoreDeltaBucket.LARGE
                ),
                List.of(),
                List.of()
        );
    }

    static EngineIntelligenceSummary timeoutDiagnosticSummary() {
        return new EngineIntelligenceSummary(
                EngineIntelligenceSummary.CONTRACT_VERSION,
                Instant.parse("2026-05-31T10:00:01Z"),
                List.of(
                        new EngineIntelligenceEngineResult(
                                "rules.primary",
                                FraudEngineType.RULES,
                                FraudEngineStatus.AVAILABLE,
                                RiskLevel.LOW,
                                EngineIntelligenceScoreBucket.LOW,
                                List.of("LOW_MODEL_RISK")
                        ),
                        new EngineIntelligenceEngineResult(
                                "ml.python.primary",
                                FraudEngineType.ML_MODEL,
                                FraudEngineStatus.TIMEOUT,
                                null,
                                EngineIntelligenceScoreBucket.UNAVAILABLE,
                                List.of("ML_MODEL_TIMEOUT")
                        )
                ),
                new EngineIntelligenceComparison(
                        EngineIntelligenceComparisonType.RULES_VS_ML,
                        List.of("rules.primary", "ml.python.primary"),
                        EngineIntelligenceAgreementStatus.PARTIAL,
                        EngineIntelligenceRiskMismatchStatus.NOT_COMPARABLE,
                        EngineIntelligenceScoreDeltaBucket.UNAVAILABLE
                ),
                List.of(),
                List.of()
        );
    }

    static TransactionScoredEvent scoreWithRealMlMissingSourceTimestamp() {
        TransactionEnrichedEvent input = TransactionFixtures.enrichedTransaction().build();
        FraudScoringRequest request = FraudScoringRequest.from(input);
        FraudScoreResult baselineResult = baselineLowResult();
        FraudScoringEngine baseline = mock(FraudScoringEngine.class);
        TransactionScoredEventPublisher publisher = mock(TransactionScoredEventPublisher.class);
        MlFraudScoringEngine mlSource = mock(MlFraudScoringEngine.class);
        when(baseline.score(request)).thenReturn(baselineResult);
        when(mlSource.score(any())).thenReturn(new FraudScoreResult(
                0.8765d,
                RiskLevel.HIGH,
                "ML",
                "python-logistic-fraud-model",
                "2026-05-30.v1",
                "2026-05-30.feature-contract.v1",
                null,
                List.of("MODEL_HIGH_RISK"),
                Map.of(),
                Map.of(),
                Map.of("modelAvailable", true),
                true
        ));
        var rulesEngine = new RuleBasedSignalEngine(
                new FeatureSnapshotReaderFactory(),
                new RuleBasedFraudScoringEngine(new ScoringProperties(0.75d, 0.90d, ScoringMode.RULE_BASED))
        );
        var mlEngine = new PythonMlSignalEngine(mlSource);
        try (var orchestrator = new FraudScoringOrchestrator(
                new FraudSignalEngineRegistry(List.of(rulesEngine, mlEngine))
        )) {
            var pipeline = new OrchestratedEngineIntelligenceDiagnosticEnrichmentPipeline(
                    new ScoringContextFactory(),
                    new ScoringProperties(0.75d, 0.90d, ScoringMode.RULE_BASED),
                    orchestrator,
                    new FraudEngineAggregationService(FraudEngineAggregationPolicy.defaultInternalPolicy()),
                    new PublicEngineIntelligenceMapper(),
                    Clock.systemUTC()
            );
            EngineIntelligenceEnrichmentResult enrichment = pipeline.enrich(request).orElseThrow();
            EngineIntelligenceEmissionService emissionService = mock(EngineIntelligenceEmissionService.class);
            when(emissionService.emitIfEnabled(request)).thenReturn(
                    EngineIntelligenceEmissionResult.emitted(enrichment)
            );
            var service = new TransactionFraudScoringService(
                    baseline,
                    new TransactionScoredEventMapper(),
                    publisher,
                    new ScoringProperties(0.75d, 0.90d, ScoringMode.RULE_BASED),
                    new ScoringMetrics(new SimpleMeterRegistry()),
                    emissionService,
                    new AnalystRecommendationService()
            );

            service.score(input);
        }
        ArgumentCaptor<TransactionScoredEvent> captor = ArgumentCaptor.forClass(TransactionScoredEvent.class);
        verify(publisher).publish(captor.capture());
        return captor.getValue();
    }

    static String json(TransactionScoredEvent event) {
        try {
            return tools.jackson.databind.json.JsonMapper.builder().findAndAddModules().build().writeValueAsString(event);
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
            EngineIntelligenceEngineResult publicMl = summary.engines().stream()
                    .filter(engine -> "ml.python.primary".equals(engine.engineId()))
                    .findFirst()
                    .orElseThrow();
            FraudEngineResult sourceMl = sourceMl(publicMl, summary.generatedAt());
            when(orchestrator.evaluate(any())).thenReturn(orchestration);
            when(orchestration.engineResults()).thenReturn(List.of(sourceMl));
            when(aggregation.aggregate(orchestration)).thenReturn(aggregationResult);
            List<NormalizedFraudEngineResult> normalizedMl = publicMl.status() == FraudEngineStatus.AVAILABLE
                    ? List.of(normalizedMl(publicMl, scoreFor(publicMl.scoreBucket())))
                    : List.of();
            when(aggregationResult.normalizedEngineResults()).thenReturn(
                    normalizedMl
            );
            when(mapper.map(aggregationResult)).thenReturn(summary);
        }

        private FraudEngineResult sourceMl(EngineIntelligenceEngineResult publicMl, Instant generatedAt) {
            FraudEngineResult source = mock(FraudEngineResult.class);
            when(source.engineId()).thenReturn("ml.python.primary");
            when(source.engineType()).thenReturn(FraudEngineType.ML_MODEL);
            when(source.status()).thenReturn(publicMl.status());
            when(source.riskLevel()).thenReturn(publicMl.riskLevel());
            when(source.score()).thenReturn(scoreFor(publicMl.scoreBucket()));
            when(source.statusReason()).thenReturn(
                    publicMl.status() == FraudEngineStatus.AVAILABLE ? null : publicMl.reasonCodes().getFirst()
            );
            when(source.sourceInferenceTimestamp()).thenReturn(generatedAt);
            if (publicMl.modelIdentity() != null) {
                when(source.modelName()).thenReturn(publicMl.modelIdentity().modelName());
                when(source.modelVersion()).thenReturn(publicMl.modelIdentity().modelVersion());
                when(source.featureContractVersion()).thenReturn(publicMl.modelIdentity().featureContractVersion());
            }
            return source;
        }

        private NormalizedFraudEngineResult normalizedMl(
                EngineIntelligenceEngineResult publicMl,
                Double score
        ) {
            NormalizedFraudEngineResult normalized = mock(NormalizedFraudEngineResult.class);
            when(normalized.engineId()).thenReturn("ml.python.primary");
            when(normalized.engineType()).thenReturn(FraudEngineType.ML_MODEL);
            when(normalized.status()).thenReturn(publicMl.status());
            when(normalized.score()).thenReturn(score);
            when(normalized.riskLevel()).thenReturn(publicMl.riskLevel());
            when(normalized.modelIdentity()).thenReturn(publicMl.modelIdentity());
            return normalized;
        }

        private Double scoreFor(EngineIntelligenceScoreBucket bucket) {
            return switch (bucket) {
                case LOW -> 0.12d;
                case MEDIUM -> 0.45d;
                case HIGH -> 0.70d;
                case VERY_HIGH -> 0.90d;
                case NONE, UNAVAILABLE -> null;
            };
        }

        TransactionScoredEvent scoreAndCapture() {
            service.score(input);
            ArgumentCaptor<TransactionScoredEvent> captor = ArgumentCaptor.forClass(TransactionScoredEvent.class);
            verify(publisher).publish(captor.capture());
            return captor.getValue();
        }
    }
}
