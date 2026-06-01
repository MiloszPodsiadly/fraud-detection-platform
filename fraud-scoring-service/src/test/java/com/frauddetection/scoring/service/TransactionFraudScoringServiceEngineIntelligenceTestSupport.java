package com.frauddetection.scoring.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.frauddetection.common.events.contract.TransactionEnrichedEvent;
import com.frauddetection.common.events.contract.TransactionScoredEvent;
import com.frauddetection.common.events.enums.RiskLevel;
import com.frauddetection.common.events.intelligence.EngineIntelligenceAgreementStatus;
import com.frauddetection.common.events.intelligence.EngineIntelligenceComparison;
import com.frauddetection.common.events.intelligence.EngineIntelligenceRiskMismatchStatus;
import com.frauddetection.common.events.intelligence.EngineIntelligenceScoreDeltaBucket;
import com.frauddetection.common.events.intelligence.EngineIntelligenceSummary;
import com.frauddetection.common.testsupport.fixture.TransactionFixtures;
import com.frauddetection.scoring.config.ScoringMode;
import com.frauddetection.scoring.config.ScoringProperties;
import com.frauddetection.scoring.domain.FraudScoreResult;
import com.frauddetection.scoring.domain.FraudScoringRequest;
import com.frauddetection.scoring.mapper.TransactionScoredEventMapper;
import com.frauddetection.scoring.messaging.TransactionScoredEventPublisher;
import com.frauddetection.scoring.observability.ScoringMetrics;
import com.frauddetection.scoring.orchestration.aggregation.EngineIntelligenceEmissionService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

final class TransactionFraudScoringServiceEngineIntelligenceTestSupport {
    static final Instant GENERATED_AT = Instant.parse("2026-05-31T10:00:00Z");

    private TransactionFraudScoringServiceEngineIntelligenceTestSupport() {
    }

    static Harness harness(Optional<EngineIntelligenceSummary> summary) {
        EngineIntelligenceEmissionService emissionService = mock(EngineIntelligenceEmissionService.class);
        TransactionEnrichedEvent input = TransactionFixtures.enrichedTransaction().build();
        FraudScoringRequest request = FraudScoringRequest.from(input);
        when(emissionService.emitIfEnabled(request)).thenReturn(summary);
        return harness(input, request, emissionService);
    }

    static Harness harness(EngineIntelligenceEmissionService emissionService) {
        TransactionEnrichedEvent input = TransactionFixtures.enrichedTransaction().build();
        FraudScoringRequest request = FraudScoringRequest.from(input);
        return harness(input, request, emissionService);
    }

    private static Harness harness(
            TransactionEnrichedEvent input,
            FraudScoringRequest request,
            EngineIntelligenceEmissionService emissionService
    ) {
        FraudScoreResult result = scoreResult();
        FraudScoringEngine scoringEngine = mock(FraudScoringEngine.class);
        TransactionScoredEventPublisher publisher = mock(TransactionScoredEventPublisher.class);
        when(scoringEngine.score(request)).thenReturn(result);
        var service = new TransactionFraudScoringService(
                scoringEngine,
                new TransactionScoredEventMapper(),
                publisher,
                new ScoringProperties(0.75d, 0.90d, ScoringMode.RULE_BASED),
                new ScoringMetrics(new SimpleMeterRegistry()),
                emissionService
        );
        return new Harness(input, request, result, scoringEngine, publisher, emissionService, service);
    }

    static FraudScoreResult scoreResult() {
        return new FraudScoreResult(
                0.91d,
                RiskLevel.CRITICAL,
                "RULE_BASED",
                "rule-based-engine",
                "v1",
                GENERATED_AT,
                List.of("HIGH_VELOCITY"),
                Map.of("finalScore", 0.91d),
                Map.of("transactionAmount", 100.0d),
                Map.of("explanationType", "WEIGHTED_REASON_CODES"),
                true
        );
    }

    static EngineIntelligenceSummary summary() {
        return new EngineIntelligenceSummary(
                EngineIntelligenceSummary.CONTRACT_VERSION,
                GENERATED_AT,
                List.of(),
                new EngineIntelligenceComparison(
                        EngineIntelligenceAgreementStatus.INSUFFICIENT_DATA,
                        EngineIntelligenceRiskMismatchStatus.NOT_COMPARABLE,
                        EngineIntelligenceScoreDeltaBucket.UNAVAILABLE
                ),
                List.of(),
                List.of()
        );
    }

    static String json(TransactionScoredEvent event) throws JsonProcessingException {
        return new ObjectMapper().findAndRegisterModules().writeValueAsString(event);
    }

    record Harness(
            TransactionEnrichedEvent input,
            FraudScoringRequest request,
            FraudScoreResult scoreResult,
            FraudScoringEngine scoringEngine,
            TransactionScoredEventPublisher publisher,
            EngineIntelligenceEmissionService emissionService,
            TransactionFraudScoringService service
    ) {
        TransactionScoredEvent scoreAndCapture() {
            service.score(input);
            ArgumentCaptor<TransactionScoredEvent> captor = ArgumentCaptor.forClass(TransactionScoredEvent.class);
            verify(publisher).publish(captor.capture());
            return captor.getValue();
        }
    }
}
