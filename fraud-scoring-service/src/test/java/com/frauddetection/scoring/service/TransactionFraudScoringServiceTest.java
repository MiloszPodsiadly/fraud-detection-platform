package com.frauddetection.scoring.service;

import com.frauddetection.common.events.contract.TransactionScoredEvent;
import com.frauddetection.common.events.enums.RiskLevel;
import com.frauddetection.common.testsupport.fixture.TransactionFixtures;
import com.frauddetection.scoring.config.ScoringMode;
import com.frauddetection.scoring.config.ScoringProperties;
import com.frauddetection.scoring.domain.FraudScoreResult;
import com.frauddetection.scoring.domain.FraudScoringRequest;
import com.frauddetection.scoring.mapper.TransactionScoredEventMapper;
import com.frauddetection.scoring.messaging.TransactionScoredEventPublisher;
import com.frauddetection.scoring.observability.ScoringMetrics;
import com.frauddetection.scoring.orchestration.aggregation.EngineIntelligenceEmissionService;
import org.junit.jupiter.api.Test;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TransactionFraudScoringServiceTest {

    @Test
    void shouldScoreAndPublishTransaction() {
        FraudScoringEngine scoringEngine = mock(FraudScoringEngine.class);
        TransactionScoredEventMapper mapper = mock(TransactionScoredEventMapper.class);
        TransactionScoredEventPublisher publisher = mock(TransactionScoredEventPublisher.class);
        EngineIntelligenceEmissionService emissionService = mock(EngineIntelligenceEmissionService.class);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

        var service = new TransactionFraudScoringService(
                scoringEngine,
                mapper,
                publisher,
                new ScoringProperties(0.75d, 0.90d, ScoringMode.RULE_BASED),
                new ScoringMetrics(meterRegistry),
                emissionService
        );
        var event = TransactionFixtures.enrichedTransaction().build();
        var scoreResult = new FraudScoreResult(
                0.91d,
                RiskLevel.CRITICAL,
                "RULE_BASED",
                "rule-based-engine",
                "v1",
                Instant.now(),
                List.of("DEVICE_NOVELTY"),
                Map.of("finalScore", 0.91d),
                event.featureSnapshot(),
                Map.of("explanationType", "WEIGHTED_REASON_CODES"),
                true
        );
        TransactionScoredEvent scoredEvent = TransactionFixtures.scoredTransaction().build();

        when(scoringEngine.score(FraudScoringRequest.from(event))).thenReturn(scoreResult);
        when(emissionService.emitIfEnabled(FraudScoringRequest.from(event))).thenReturn(Optional.empty());
        when(mapper.toEvent(FraudScoringRequest.from(event), scoreResult, Optional.empty())).thenReturn(scoredEvent);

        service.score(event);

        var inOrder = inOrder(scoringEngine, emissionService, mapper, publisher);
        inOrder.verify(scoringEngine).score(FraudScoringRequest.from(event));
        inOrder.verify(emissionService).emitIfEnabled(FraudScoringRequest.from(event));
        inOrder.verify(mapper).toEvent(FraudScoringRequest.from(event), scoreResult, Optional.empty());
        inOrder.verify(publisher).publish(scoredEvent);
        org.assertj.core.api.Assertions.assertThat(meterRegistry.get("fraud.scoring.requests")
                .tags("mode", "rule_based", "outcome", "success", "fallback_used", "false", "risk_level", "critical")
                .counter()
                .count()).isEqualTo(1.0d);
    }
}
