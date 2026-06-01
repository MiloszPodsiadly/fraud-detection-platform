package com.frauddetection.scoring.service;

import com.frauddetection.common.events.contract.TransactionScoredEvent;
import com.frauddetection.common.testsupport.fixture.TransactionFixtures;
import com.frauddetection.scoring.config.EngineIntelligenceEmissionProperties;
import com.frauddetection.scoring.config.ScoringMode;
import com.frauddetection.scoring.config.ScoringProperties;
import com.frauddetection.scoring.domain.FraudScoringRequest;
import com.frauddetection.scoring.mapper.TransactionScoredEventMapper;
import com.frauddetection.scoring.messaging.TransactionScoredEventPublisher;
import com.frauddetection.scoring.observability.ScoringMetrics;
import com.frauddetection.scoring.orchestration.aggregation.EngineIntelligenceDiagnosticEnrichmentPipeline;
import com.frauddetection.scoring.orchestration.aggregation.EngineIntelligenceEmissionService;
import com.frauddetection.scoring.orchestration.aggregation.NoOpEngineIntelligenceEmissionMetrics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static com.frauddetection.scoring.service.TransactionFraudScoringServiceEngineIntelligenceTestSupport.harness;
import static com.frauddetection.scoring.service.TransactionFraudScoringServiceEngineIntelligenceTestSupport.json;
import static com.frauddetection.scoring.service.TransactionFraudScoringServiceEngineIntelligenceTestSupport.scoreResult;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
    void emissionServiceExceptionPublishesBaseEventWithoutEngineIntelligence() throws Exception {
        var input = TransactionFixtures.enrichedTransaction().build();
        var request = FraudScoringRequest.from(input);
        var scoreResult = scoreResult();
        var baseEvent = new TransactionScoredEventMapper().toEvent(request, scoreResult, Optional.empty());
        FraudScoringEngine scoringEngine = mock(FraudScoringEngine.class);
        EngineIntelligenceEmissionService emissionService = mock(EngineIntelligenceEmissionService.class);
        TransactionScoredEventMapper mapper = mock(TransactionScoredEventMapper.class);
        TransactionScoredEventPublisher publisher = mock(TransactionScoredEventPublisher.class);
        ScoringMetrics metrics = mock(ScoringMetrics.class);
        when(scoringEngine.score(request)).thenReturn(scoreResult);
        when(emissionService.emitIfEnabled(request)).thenThrow(new IllegalStateException("raw-secret"));
        when(mapper.toEvent(request, scoreResult, Optional.empty())).thenReturn(baseEvent);
        var service = new TransactionFraudScoringService(
                scoringEngine,
                mapper,
                publisher,
                new ScoringProperties(0.75d, 0.90d, ScoringMode.RULE_BASED),
                metrics,
                emissionService
        );

        service.score(input);

        verify(mapper).toEvent(request, scoreResult, Optional.empty());
        verify(publisher).publish(baseEvent);
        verify(metrics).recordScoringRequest(
                eq(ScoringMode.RULE_BASED),
                eq(scoreResult.riskLevel()),
                eq(false),
                eq(true),
                anyLong()
        );
        assertThat(baseEvent.engineIntelligence()).isNull();
        assertThat(baseEvent.fraudScore()).isEqualTo(scoreResult.fraudScore());
        assertThat(baseEvent.riskLevel()).isEqualTo(scoreResult.riskLevel());
        assertThat(baseEvent.alertRecommended()).isEqualTo(scoreResult.alertRecommended());
        assertThat(baseEvent.reasonCodes()).isEqualTo(scoreResult.reasonCodes());
        assertThat(baseEvent.scoringEvidence()).isEqualTo(scoreResult.scoringEvidence());
        assertThat(baseEvent.scoreDetails()).isEqualTo(scoreResult.scoreDetails());
        assertThat(json(baseEvent)).doesNotContain("\"engineIntelligence\"", "raw-secret");
    }

    @Test
    void producerBoundaryFallbackLogIsBounded() throws Exception {
        assertThat(Files.readString(moduleRoot().resolve(
                "src/main/java/com/frauddetection/scoring/service/TransactionFraudScoringService.java"
        )))
                .contains("log.warn(\"Engine intelligence enrichment omitted.\")")
                .doesNotContain(
                        "exception.getMessage()",
                        "log.warn(\"Engine intelligence enrichment omitted.\", exception)"
                );
    }

    @Test
    void baselineScoringExceptionIsNotSwallowed() {
        var harness = harness(Optional.empty());
        when(harness.scoringEngine().score(harness.request())).thenThrow(new IllegalStateException("baseline-failure"));

        assertThatThrownBy(() -> harness.service().score(harness.input()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("baseline-failure");
        verify(harness.publisher(), never()).publish(any());
        verify(harness.emissionService(), never()).emitIfEnabled(any());
    }

    private TransactionFraudScoringServiceEngineIntelligenceTestSupport.Harness throwingEnrichmentHarness() {
        EngineIntelligenceDiagnosticEnrichmentPipeline pipeline =
                mock(EngineIntelligenceDiagnosticEnrichmentPipeline.class);
        when(pipeline.enrich(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new IllegalStateException("raw-secret-must-not-leak"));
        return TransactionFraudScoringServiceEngineIntelligenceTestSupport.harness(
                new EngineIntelligenceEmissionService(
                        new EngineIntelligenceEmissionProperties(true),
                        provider(pipeline),
                        new NoOpEngineIntelligenceEmissionMetrics()
                )
        );
    }

    @SuppressWarnings("unchecked")
    private <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }

    private Path moduleRoot() {
        Path current = Path.of(".").toAbsolutePath().normalize();
        return Files.exists(current.resolve("src/main"))
                ? current
                : current.resolve("fraud-scoring-service");
    }
}
