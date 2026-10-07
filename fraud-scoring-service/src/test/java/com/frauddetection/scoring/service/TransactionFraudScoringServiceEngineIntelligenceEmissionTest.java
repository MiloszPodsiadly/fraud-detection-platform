package com.frauddetection.scoring.service;

import com.frauddetection.common.events.contract.TransactionScoredEvent;
import com.frauddetection.common.testsupport.fixture.TransactionFixtures;
import com.frauddetection.scoring.config.ScoringMode;
import com.frauddetection.scoring.config.ScoringProperties;
import com.frauddetection.scoring.domain.FraudScoringRequest;
import com.frauddetection.scoring.mapper.TransactionScoredEventMapper;
import com.frauddetection.scoring.messaging.TransactionScoredEventPublisher;
import com.frauddetection.scoring.observability.ScoringMetrics;
import com.frauddetection.scoring.orchestration.aggregation.EngineIntelligenceEmissionService;
import com.frauddetection.scoring.orchestration.aggregation.EngineIntelligenceEmissionOmissionReason;
import com.frauddetection.scoring.orchestration.aggregation.EngineIntelligenceEmissionResult;
import com.frauddetection.scoring.orchestration.aggregation.EngineIntelligenceEnrichmentResult;
import com.frauddetection.common.events.intelligence.MlPredictionEvidenceOmissionReason;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static com.frauddetection.scoring.service.TransactionFraudScoringServiceEngineIntelligenceTestSupport.analystRecommendationService;
import static com.frauddetection.scoring.service.TransactionFraudScoringServiceEngineIntelligenceTestSupport.availableMlSummary;
import static com.frauddetection.scoring.service.TransactionFraudScoringServiceEngineIntelligenceTestSupport.degradedMlSummary;
import static com.frauddetection.scoring.service.TransactionFraudScoringServiceEngineIntelligenceTestSupport.harness;
import static com.frauddetection.scoring.service.TransactionFraudScoringServiceEngineIntelligenceTestSupport.harnessWithEnrichment;
import static com.frauddetection.scoring.service.TransactionFraudScoringServiceEngineIntelligenceTestSupport.json;
import static com.frauddetection.scoring.service.TransactionFraudScoringServiceEngineIntelligenceTestSupport.mlPredictionEvidence;
import static com.frauddetection.scoring.service.TransactionFraudScoringServiceEngineIntelligenceTestSupport.scoreResult;
import static com.frauddetection.scoring.service.TransactionFraudScoringServiceEngineIntelligenceTestSupport.summary;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TransactionFraudScoringServiceEngineIntelligenceEmissionTest {

    @Test
    void defaultConfigPublishesEventWithoutEngineIntelligence() throws Exception {
        TransactionScoredEvent event = harness(Optional.empty()).scoreAndCapture();
        assertThat(event.engineIntelligence()).isNull();
        assertThat(event.mlPredictionEvidence()).isNull();
        assertThat(event.analystRecommendation().status().name()).isEqualTo("ABSENT");
        assertThat(json(event)).doesNotContain("\"engineIntelligence\"", "\"mlPredictionEvidence\"");
    }

    @Test
    void explicitFalsePublishesEventWithoutEngineIntelligence() throws Exception {
        TransactionScoredEvent event = harness(Optional.empty()).scoreAndCapture();
        assertThat(event.engineIntelligence()).isNull();
        assertThat(event.mlPredictionEvidence()).isNull();
        assertThat(event.analystRecommendation().status().name()).isEqualTo("ABSENT");
        assertThat(json(event)).doesNotContain("\"engineIntelligence\"", "\"mlPredictionEvidence\"");
    }

    @Test
    void explicitTruePublishesEventWithBoundedEngineIntelligence() throws Exception {
        TransactionScoredEvent event = harness(Optional.of(summary())).scoreAndCapture();
        assertThat(event.engineIntelligence()).isEqualTo(summary());
        assertThat(json(event)).contains("\"engineIntelligence\"");
    }

    @Test
    void enabledAvailableMlEmissionPublishesExactEvidenceFromSameEnrichment() throws Exception {
        var summary = availableMlSummary();
        var evidence = mlPredictionEvidence();
        TransactionScoredEvent event = harnessWithEnrichment(
                EngineIntelligenceEnrichmentResult.withEvidence(summary, evidence)
        ).scoreAndCapture();

        assertThat(event.engineIntelligence()).isEqualTo(summary);
        assertThat(event.mlPredictionEvidence()).isEqualTo(evidence);
        assertThat(json(event)).contains("\"mlPredictionEvidence\"", "\"mlScore\":0.8123");
    }

    @Test
    void missingMlSourceTimestampOmitsEvidenceWithoutChangingBaselineScore() {
        TransactionScoredEvent event = harnessWithEnrichment(
                EngineIntelligenceEnrichmentResult.withoutEvidence(
                        degradedMlSummary(),
                        MlPredictionEvidenceOmissionReason.SOURCE_TIMESTAMP_MISSING
                )
        ).scoreAndCapture();

        assertThat(event.fraudScore()).isEqualTo(scoreResult().fraudScore());
        assertThat(event.riskLevel()).isEqualTo(scoreResult().riskLevel());
        assertThat(event.mlPredictionEvidence()).isNull();
        assertThat(json(event)).doesNotContain("\"mlPredictionEvidence\"");
    }

    @Test
    void enabledEmissionSummaryIsPassedToMapperAndPublisher() throws Exception {
        var input = TransactionFixtures.enrichedTransaction().build();
        var request = FraudScoringRequest.from(input);
        var scoreResult = scoreResult();
        var summary = summary();
        var recommendation = analystRecommendationService().recommend(scoreResult, Optional.of(summary));
        var scoredEvent = new TransactionScoredEventMapper().toEvent(
                request,
                scoreResult,
                Optional.of(summary),
                MlPredictionEvidenceOmissionReason.PREDICTION_NOT_ACCEPTED,
                recommendation
        );
        FraudScoringEngine scoringEngine = mock(FraudScoringEngine.class);
        EngineIntelligenceEmissionService emissionService = mock(EngineIntelligenceEmissionService.class);
        TransactionScoredEventMapper mapper = mock(TransactionScoredEventMapper.class);
        TransactionScoredEventPublisher publisher = mock(TransactionScoredEventPublisher.class);
        ScoringMetrics metrics = mock(ScoringMetrics.class);
        when(scoringEngine.score(request)).thenReturn(scoreResult);
        when(emissionService.emitIfEnabled(request)).thenReturn(EngineIntelligenceEmissionResult.emitted(
                EngineIntelligenceEnrichmentResult.withoutEvidence(
                        summary,
                        MlPredictionEvidenceOmissionReason.PREDICTION_NOT_ACCEPTED
                )
        ));
        when(mapper.toEvent(
                request,
                scoreResult,
                Optional.of(summary),
                Optional.empty(),
                Optional.of(MlPredictionEvidenceOmissionReason.PREDICTION_NOT_ACCEPTED),
                recommendation
        )).thenReturn(scoredEvent);
        var service = new TransactionFraudScoringService(
                scoringEngine,
                mapper,
                publisher,
                new ScoringProperties(0.75d, 0.90d, ScoringMode.RULE_BASED),
                metrics,
                emissionService,
                analystRecommendationService()
        );

        service.score(input);

        verify(scoringEngine).score(request);
        verify(emissionService).emitIfEnabled(request);
        verify(mapper).toEvent(
                request,
                scoreResult,
                Optional.of(summary),
                Optional.empty(),
                Optional.of(MlPredictionEvidenceOmissionReason.PREDICTION_NOT_ACCEPTED),
                recommendation
        );
        verify(publisher).publish(scoredEvent);
        assertThat(scoredEvent.engineIntelligence()).isEqualTo(summary);
        assertThat(json(scoredEvent))
                .contains("\"engineIntelligence\"", "\"contractVersion\":1")
                .doesNotContain(
                        "FraudEngineAggregationResult",
                        "NormalizedFraudEngineResult",
                        "rawScore",
                        "raw evidence title",
                        "raw evidence description",
                        "raw contribution value",
                        "featureVector",
                        "endpoint",
                        "token",
                        "secret",
                        "stackTrace",
                        "finalDecision",
                        "recommendedAction",
                        "\"approve\"",
                        "\"decline\"",
                        "\"block\""
                );
    }

    @Test
    void enabledEmissionFailurePublishesBaseEvent() throws Exception {
        TransactionScoredEvent event = harness(true, Optional.empty()).scoreAndCapture();
        assertThat(event.engineIntelligence()).isNull();
        assertThat(event.mlPredictionEvidence()).isNull();
        assertThat(event.mlPredictionEvidenceOmissionReason())
                .isEqualTo(MlPredictionEvidenceOmissionReason.DIAGNOSTIC_ENRICHMENT_UNAVAILABLE);
        assertThat(event.analystRecommendation().status().name()).isEqualTo("UNAVAILABLE");
        assertThat(json(event)).doesNotContain("\"engineIntelligence\"", "\"mlPredictionEvidence\"", "raw-secret");
    }

    @Test
    void pipelineUnavailablePublishesDiagnosticUnavailableAndUnavailableRecommendation() {
        TransactionScoredEvent event = eventForOmission(EngineIntelligenceEmissionOmissionReason.PIPELINE_UNAVAILABLE);

        assertThat(event.mlPredictionEvidenceOmissionReason())
                .isEqualTo(MlPredictionEvidenceOmissionReason.DIAGNOSTIC_ENRICHMENT_UNAVAILABLE);
        assertThat(event.analystRecommendation().status().name()).isEqualTo("UNAVAILABLE");
    }

    @Test
    void emptyPipelineResultPublishesIntegrityFailureAndUnavailableRecommendation() {
        TransactionScoredEvent event = eventForOmission(EngineIntelligenceEmissionOmissionReason.EMPTY_RESULT);

        assertThat(event.mlPredictionEvidenceOmissionReason())
                .isEqualTo(MlPredictionEvidenceOmissionReason.EVIDENCE_SOURCE_INTEGRITY_FAILURE);
        assertThat(event.analystRecommendation().status().name()).isEqualTo("UNAVAILABLE");
    }

    @Test
    void enabledEmissionDoesNotChangeBaseScoringFields() {
        TransactionScoredEvent disabled = harness(Optional.empty()).scoreAndCapture();
        TransactionScoredEvent enabled = harness(Optional.of(summary())).scoreAndCapture();

        assertThat(enabled)
                .usingRecursiveComparison()
                .ignoringFields(
                        "eventId",
                        "createdAt",
                        "engineIntelligence",
                        "mlPredictionEvidenceOmissionReason",
                        "analystRecommendation"
                )
                .isEqualTo(disabled);
        assertThat(enabled.mlPredictionEvidenceOmissionReason())
                .isEqualTo(MlPredictionEvidenceOmissionReason.ML_ENGINE_UNAVAILABLE);
        assertThat(disabled.mlPredictionEvidenceOmissionReason())
                .isEqualTo(MlPredictionEvidenceOmissionReason.DIAGNOSTIC_EMISSION_DISABLED);
    }

    private TransactionScoredEvent eventForOmission(EngineIntelligenceEmissionOmissionReason reason) {
        EngineIntelligenceEmissionService emissionService = mock(EngineIntelligenceEmissionService.class);
        when(emissionService.emitIfEnabled(any())).thenReturn(EngineIntelligenceEmissionResult.omitted(reason));
        return harness(emissionService).scoreAndCapture();
    }
}
