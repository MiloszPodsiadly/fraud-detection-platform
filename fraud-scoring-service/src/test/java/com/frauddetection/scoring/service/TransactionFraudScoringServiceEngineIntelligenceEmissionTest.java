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
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static com.frauddetection.scoring.service.TransactionFraudScoringServiceEngineIntelligenceTestSupport.harness;
import static com.frauddetection.scoring.service.TransactionFraudScoringServiceEngineIntelligenceTestSupport.json;
import static com.frauddetection.scoring.service.TransactionFraudScoringServiceEngineIntelligenceTestSupport.scoreResult;
import static com.frauddetection.scoring.service.TransactionFraudScoringServiceEngineIntelligenceTestSupport.summary;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TransactionFraudScoringServiceEngineIntelligenceEmissionTest {

    @Test
    void defaultConfigPublishesEventWithoutEngineIntelligence() throws Exception {
        TransactionScoredEvent event = harness(Optional.empty()).scoreAndCapture();
        assertThat(event.engineIntelligence()).isNull();
        assertThat(json(event)).doesNotContain("\"engineIntelligence\"");
    }

    @Test
    void explicitFalsePublishesEventWithoutEngineIntelligence() throws Exception {
        TransactionScoredEvent event = harness(Optional.empty()).scoreAndCapture();
        assertThat(event.engineIntelligence()).isNull();
        assertThat(json(event)).doesNotContain("\"engineIntelligence\"");
    }

    @Test
    void explicitTruePublishesEventWithBoundedEngineIntelligence() throws Exception {
        TransactionScoredEvent event = harness(Optional.of(summary())).scoreAndCapture();
        assertThat(event.engineIntelligence()).isEqualTo(summary());
        assertThat(json(event)).contains("\"engineIntelligence\"");
    }

    @Test
    void enabledEmissionSummaryIsPassedToMapperAndPublished() throws Exception {
        var input = TransactionFixtures.enrichedTransaction().build();
        var request = FraudScoringRequest.from(input);
        var scoreResult = scoreResult();
        var summary = summary();
        var scoredEvent = new TransactionScoredEventMapper().toEvent(request, scoreResult, Optional.of(summary));
        FraudScoringEngine scoringEngine = mock(FraudScoringEngine.class);
        EngineIntelligenceEmissionService emissionService = mock(EngineIntelligenceEmissionService.class);
        TransactionScoredEventMapper mapper = mock(TransactionScoredEventMapper.class);
        TransactionScoredEventPublisher publisher = mock(TransactionScoredEventPublisher.class);
        ScoringMetrics metrics = mock(ScoringMetrics.class);
        when(scoringEngine.score(request)).thenReturn(scoreResult);
        when(emissionService.emitIfEnabled(request)).thenReturn(Optional.of(summary));
        when(mapper.toEvent(request, scoreResult, Optional.of(summary))).thenReturn(scoredEvent);
        var service = new TransactionFraudScoringService(
                scoringEngine,
                mapper,
                publisher,
                new ScoringProperties(0.75d, 0.90d, ScoringMode.RULE_BASED),
                metrics,
                emissionService
        );

        service.score(input);

        verify(emissionService).emitIfEnabled(request);
        verify(mapper).toEvent(request, scoreResult, Optional.of(summary));
        verify(publisher).publish(scoredEvent);
        assertThat(scoredEvent.engineIntelligence()).isEqualTo(summary);
        assertThat(json(scoredEvent))
                .contains("\"engineIntelligence\"", "\"contractVersion\":1")
                .doesNotContain(
                        "normalizedEngineResults",
                        "aggregationResult",
                        "rawEvidence",
                        "internalDiagnostics",
                        "\"contributions\""
                );
    }

    @Test
    void enabledEmissionFailurePublishesBaseEvent() throws Exception {
        TransactionScoredEvent event = harness(Optional.empty()).scoreAndCapture();
        assertThat(event.engineIntelligence()).isNull();
        assertThat(json(event)).doesNotContain("\"engineIntelligence\"", "raw-secret");
    }

    @Test
    void enabledEmissionDoesNotChangeBaseScoringFields() {
        TransactionScoredEvent disabled = harness(Optional.empty()).scoreAndCapture();
        TransactionScoredEvent enabled = harness(Optional.of(summary())).scoreAndCapture();

        assertThat(enabled)
                .usingRecursiveComparison()
                .ignoringFields("eventId", "createdAt", "engineIntelligence")
                .isEqualTo(disabled);
    }
}
