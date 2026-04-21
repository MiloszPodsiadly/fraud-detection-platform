package com.frauddetection.scoring.service;

import com.frauddetection.common.events.contract.TransactionScoredEvent;
import com.frauddetection.common.events.enums.RiskLevel;
import com.frauddetection.common.testsupport.fixture.TransactionFixtures;
import com.frauddetection.scoring.domain.FraudScoreResult;
import com.frauddetection.scoring.domain.FraudScoringRequest;
import com.frauddetection.scoring.mapper.TransactionScoredEventMapper;
import com.frauddetection.scoring.messaging.TransactionScoredEventPublisher;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TransactionFraudScoringServiceTest {

    @Test
    void shouldScoreAndPublishTransaction() {
        FraudScoringEngine scoringEngine = mock(FraudScoringEngine.class);
        TransactionScoredEventMapper mapper = mock(TransactionScoredEventMapper.class);
        TransactionScoredEventPublisher publisher = mock(TransactionScoredEventPublisher.class);

        var service = new TransactionFraudScoringService(scoringEngine, mapper, publisher);
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
        when(mapper.toEvent(FraudScoringRequest.from(event), scoreResult)).thenReturn(scoredEvent);

        service.score(event);

        var inOrder = inOrder(scoringEngine, mapper, publisher);
        inOrder.verify(scoringEngine).score(FraudScoringRequest.from(event));
        inOrder.verify(mapper).toEvent(FraudScoringRequest.from(event), scoreResult);
        inOrder.verify(publisher).publish(scoredEvent);
    }
}
