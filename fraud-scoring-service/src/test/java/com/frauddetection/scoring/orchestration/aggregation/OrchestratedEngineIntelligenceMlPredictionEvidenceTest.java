package com.frauddetection.scoring.orchestration.aggregation;

import com.frauddetection.common.events.engine.FraudEngineConfidence;
import com.frauddetection.common.events.engine.FraudEngineResult;
import com.frauddetection.common.events.engine.FraudEngineStatus;
import com.frauddetection.common.events.engine.FraudEngineType;
import com.frauddetection.common.events.enums.RiskLevel;
import com.frauddetection.scoring.orchestration.FraudScoringOrchestrationResult;
import com.frauddetection.scoring.orchestration.FraudScoringOrchestrationStatus;
import com.frauddetection.scoring.orchestration.FraudScoringOrchestrator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;

import static com.frauddetection.scoring.orchestration.aggregation.EngineIntelligenceEmissionTestSupport.pipeline;
import static com.frauddetection.scoring.orchestration.aggregation.EngineIntelligenceEmissionTestSupport.request;
import static com.frauddetection.scoring.orchestration.aggregation.EngineIntelligenceEmissionTestSupport.service;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrchestratedEngineIntelligenceMlPredictionEvidenceTest {

    @Test
    void availableMlEvidenceComesFromSameOrchestrationResultWithExactScoreIdentityAndTimestamp() {
        FraudScoringOrchestrator orchestrator = mock(FraudScoringOrchestrator.class);
        FraudScoringOrchestrationResult orchestration = AggregationTestSupport.orchestration(
                AggregationTestSupport.available("rules.primary", 0.1111d, RiskLevel.LOW, "HIGH_VELOCITY"),
                AggregationTestSupport.available("ml.python.primary", 0.8765d, RiskLevel.HIGH, "MODEL_HIGH_RISK")
        );
        when(orchestrator.evaluate(any())).thenReturn(orchestration);

        EngineIntelligenceEnrichmentResult enrichment = service(true, pipeline(
                orchestrator,
                new FraudEngineAggregationService(FraudEngineAggregationPolicy.defaultInternalPolicy()),
                new PublicEngineIntelligenceMapper()
        )).emitIfEnabled(request()).orElseThrow();

        var evidence = enrichment.mlPredictionEvidence().orElseThrow();
        assertThat(evidence.mlScore()).isEqualTo(0.8765d).isNotEqualTo(0.1111d);
        assertThat(evidence.mlRiskLevel()).isEqualTo(RiskLevel.HIGH);
        assertThat(evidence.modelName()).isEqualTo("python-logistic-fraud-model");
        assertThat(evidence.modelVersion()).isEqualTo("2026-05-30.v1");
        assertThat(evidence.featureContractVersion()).isEqualTo("2026-05-30.feature-contract.v1");
        assertThat(evidence.sourceExecutionTimestamp())
                .isEqualTo(AggregationTestSupport.SOURCE_INFERENCE_AT)
                .isNotEqualTo(AggregationTestSupport.GENERATED_AT);
        assertThat(enrichment.engineIntelligenceSummary()).isPresent();
        assertThat(enrichment.mlPredictionEvidenceOmissionReason()).isEmpty();
        verify(orchestrator, times(1)).evaluate(any());
    }

    @ParameterizedTest
    @EnumSource(value = FraudEngineStatus.class, names = "AVAILABLE", mode = EnumSource.Mode.EXCLUDE)
    void nonAvailableMlKeepsPublicSummaryWithoutManufacturingPredictionEvidence(FraudEngineStatus status) {
        FraudScoringOrchestrator orchestrator = mock(FraudScoringOrchestrator.class);
        when(orchestrator.evaluate(any())).thenReturn(AggregationTestSupport.orchestration(
                AggregationTestSupport.available("rules.primary", 0.2d, RiskLevel.LOW, "HIGH_VELOCITY"),
                AggregationTestSupport.unavailable(
                        "ml.python.primary",
                        status,
                        status == FraudEngineStatus.TIMEOUT
                                ? "ORCHESTRATOR_ENGINE_TIMEOUT"
                                : "ML_MODEL_UNAVAILABLE"
                )
        ));

        EngineIntelligenceEnrichmentResult enrichment = service(true, pipeline(
                orchestrator,
                new FraudEngineAggregationService(FraudEngineAggregationPolicy.defaultInternalPolicy()),
                new PublicEngineIntelligenceMapper()
        )).emitIfEnabled(request()).orElseThrow();

        assertThat(enrichment.engineIntelligenceSummary()).isPresent();
        assertThat(enrichment.mlPredictionEvidence()).isEmpty();
        MlPredictionEvidenceOmissionReason expectedReason = switch (status) {
            case UNAVAILABLE, TIMEOUT, SKIPPED -> MlPredictionEvidenceOmissionReason.ML_ENGINE_UNAVAILABLE;
            case DEGRADED, FALLBACK_USED -> MlPredictionEvidenceOmissionReason.PREDICTION_NOT_ACCEPTED;
            case AVAILABLE -> throw new IllegalArgumentException("AVAILABLE is excluded by the parameter source");
        };
        assertThat(enrichment.mlPredictionEvidenceOmissionReason()).contains(expectedReason);
    }

    @Test
    void availableMlWithoutSourceTimestampHasExplicitOmissionAndNoFabricatedEvidence() {
        FraudScoringOrchestrator orchestrator = mock(FraudScoringOrchestrator.class);
        FraudEngineResult sourceWithoutTimestamp = new FraudEngineResult(
                "ml.python.primary",
                FraudEngineType.ML_MODEL,
                "python",
                FraudEngineStatus.AVAILABLE,
                0.8765d,
                RiskLevel.HIGH,
                FraudEngineConfidence.MEDIUM,
                List.of("MODEL_HIGH_RISK"),
                List.of(),
                List.of(),
                2L,
                "python-logistic-fraud-model",
                "2026-05-30.v1",
                "2026-05-30.feature-contract.v1",
                null,
                AggregationTestSupport.GENERATED_AT,
                null
        );
        when(orchestrator.evaluate(any())).thenReturn(AggregationTestSupport.orchestration(
                AggregationTestSupport.available("rules.primary", 0.1111d, RiskLevel.LOW, "HIGH_VELOCITY"),
                sourceWithoutTimestamp
        ));

        EngineIntelligenceEnrichmentResult enrichment = service(true, pipeline(
                orchestrator,
                new FraudEngineAggregationService(FraudEngineAggregationPolicy.defaultInternalPolicy()),
                new PublicEngineIntelligenceMapper()
        )).emitIfEnabled(request()).orElseThrow();

        assertThat(enrichment.engineIntelligenceSummary()).isPresent();
        assertThat(enrichment.mlPredictionEvidence()).isEmpty();
        assertThat(enrichment.mlPredictionEvidenceOmissionReason()).contains(
                MlPredictionEvidenceOmissionReason.SOURCE_TIMESTAMP_MISSING
        );
        verify(orchestrator, times(1)).evaluate(any());
    }

    @ParameterizedTest
    @CsvSource({
            "ML_SCORE_OUT_OF_RANGE, INVALID_SCORE",
            "ML_MODEL_METADATA_MISSING, IDENTITY_VALIDATION_FAILURE"
    })
    void rejectedMlResultRetainsSpecificEvidenceOmissionReason(
            String statusReason,
            MlPredictionEvidenceOmissionReason expectedReason
    ) {
        FraudScoringOrchestrator orchestrator = mock(FraudScoringOrchestrator.class);
        when(orchestrator.evaluate(any())).thenReturn(AggregationTestSupport.orchestration(
                AggregationTestSupport.available("rules.primary", 0.1111d, RiskLevel.LOW, "HIGH_VELOCITY"),
                AggregationTestSupport.unavailable("ml.python.primary", FraudEngineStatus.DEGRADED, statusReason)
        ));

        EngineIntelligenceEnrichmentResult enrichment = service(true, pipeline(
                orchestrator,
                new FraudEngineAggregationService(FraudEngineAggregationPolicy.defaultInternalPolicy()),
                new PublicEngineIntelligenceMapper()
        )).emitIfEnabled(request()).orElseThrow();

        assertThat(enrichment.mlPredictionEvidence()).isEmpty();
        assertThat(enrichment.mlPredictionEvidenceOmissionReason()).contains(expectedReason);
    }

    @Test
    void mlResultExcludedByAggregationLimitOmitsEnrichmentRatherThanProducingEvidence() {
        FraudScoringOrchestrator orchestrator = mock(FraudScoringOrchestrator.class);
        when(orchestrator.evaluate(any())).thenReturn(AggregationTestSupport.orchestration(
                AggregationTestSupport.available("rules.primary", 0.2d, RiskLevel.LOW, "HIGH_VELOCITY"),
                AggregationTestSupport.available("ml.python.primary", 0.8d, RiskLevel.HIGH, "MODEL_HIGH_RISK")
        ));
        FraudEngineAggregationPolicy rulesOnlyPolicy = new FraudEngineAggregationPolicy(
                1, 5, 5, 5, 5, 20, 128, 120, 256
        );

        assertThat(service(true, pipeline(
                orchestrator,
                new FraudEngineAggregationService(rulesOnlyPolicy),
                new PublicEngineIntelligenceMapper()
        )).emitIfEnabled(request())).isEmpty();
    }

    @Test
    void missingMlLineageFailsOptionalEnrichmentWithoutPromotingInvalidEvidence() {
        FraudEngineResult invalidMlResult = mock(FraudEngineResult.class);
        when(invalidMlResult.engineId()).thenReturn("ml.python.primary");
        when(invalidMlResult.engineType()).thenReturn(FraudEngineType.ML_MODEL);
        when(invalidMlResult.status()).thenReturn(FraudEngineStatus.AVAILABLE);
        when(invalidMlResult.score()).thenReturn(0.8d);
        when(invalidMlResult.riskLevel()).thenReturn(RiskLevel.HIGH);
        when(invalidMlResult.confidence()).thenReturn(FraudEngineConfidence.MEDIUM);
        when(invalidMlResult.reasonCodes()).thenReturn(List.of("MODEL_HIGH_RISK"));
        when(invalidMlResult.contributions()).thenReturn(List.of());
        when(invalidMlResult.evidence()).thenReturn(List.of());
        when(invalidMlResult.latencyMs()).thenReturn(1L);
        FraudScoringOrchestrationResult orchestration = new FraudScoringOrchestrationResult(
                FraudScoringOrchestrationStatus.COMPLETE,
                List.of(invalidMlResult),
                List.of(),
                AggregationTestSupport.GENERATED_AT
        );
        FraudScoringOrchestrator orchestrator = mock(FraudScoringOrchestrator.class);
        when(orchestrator.evaluate(any())).thenReturn(orchestration);

        assertThat(service(true, pipeline(
                orchestrator,
                new FraudEngineAggregationService(FraudEngineAggregationPolicy.defaultInternalPolicy()),
                new PublicEngineIntelligenceMapper()
        )).emitIfEnabled(request())).isEmpty();
    }
}
