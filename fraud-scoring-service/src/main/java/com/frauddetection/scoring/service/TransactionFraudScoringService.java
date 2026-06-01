package com.frauddetection.scoring.service;

import com.frauddetection.common.events.contract.TransactionEnrichedEvent;
import com.frauddetection.common.events.contract.TransactionScoredEvent;
import com.frauddetection.common.events.intelligence.EngineIntelligenceSummary;
import com.frauddetection.scoring.domain.FraudScoreResult;
import com.frauddetection.scoring.domain.FraudScoringRequest;
import com.frauddetection.scoring.config.ScoringProperties;
import com.frauddetection.scoring.mapper.TransactionScoredEventMapper;
import com.frauddetection.scoring.messaging.TransactionScoredEventPublisher;
import com.frauddetection.scoring.observability.ScoringMetrics;
import com.frauddetection.scoring.orchestration.aggregation.EngineIntelligenceEmissionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class TransactionFraudScoringService implements TransactionFraudScoringUseCase {

    private static final Logger log = LoggerFactory.getLogger(TransactionFraudScoringService.class);

    private final FraudScoringEngine fraudScoringEngine;
    private final TransactionScoredEventMapper transactionScoredEventMapper;
    private final TransactionScoredEventPublisher transactionScoredEventPublisher;
    private final ScoringProperties scoringProperties;
    private final ScoringMetrics scoringMetrics;
    private final EngineIntelligenceEmissionService engineIntelligenceEmissionService;

    public TransactionFraudScoringService(
            FraudScoringEngine fraudScoringEngine,
            TransactionScoredEventMapper transactionScoredEventMapper,
            TransactionScoredEventPublisher transactionScoredEventPublisher,
            ScoringProperties scoringProperties,
            ScoringMetrics scoringMetrics,
            EngineIntelligenceEmissionService engineIntelligenceEmissionService
    ) {
        this.fraudScoringEngine = fraudScoringEngine;
        this.transactionScoredEventMapper = transactionScoredEventMapper;
        this.transactionScoredEventPublisher = transactionScoredEventPublisher;
        this.scoringProperties = scoringProperties;
        this.scoringMetrics = scoringMetrics;
        this.engineIntelligenceEmissionService = engineIntelligenceEmissionService;
    }

    @Override
    public void score(TransactionEnrichedEvent event) {
        long startedAt = System.nanoTime();
        log.atInfo()
                .addKeyValue("transactionId", event.transactionId())
                .addKeyValue("correlationId", event.correlationId())
                .log("Started transaction fraud scoring.");

        try {
            FraudScoringRequest scoringRequest = FraudScoringRequest.from(event);
            FraudScoreResult scoreResult = fraudScoringEngine.score(scoringRequest);
            Optional<EngineIntelligenceSummary> engineIntelligence = engineIntelligence(scoringRequest);
            TransactionScoredEvent scoredEvent = transactionScoredEventMapper.toEvent(
                    scoringRequest,
                    scoreResult,
                    engineIntelligence
            );
            transactionScoredEventPublisher.publish(scoredEvent);

            scoringMetrics.recordScoringRequest(
                    scoringProperties.mode(),
                    scoreResult.riskLevel(),
                    fallbackUsed(scoreResult),
                    true,
                    System.nanoTime() - startedAt
            );

            log.atInfo()
                    .addKeyValue("transactionId", event.transactionId())
                    .addKeyValue("correlationId", event.correlationId())
                    .addKeyValue("riskLevel", scoreResult.riskLevel())
                    .addKeyValue("fraudScore", scoreResult.fraudScore())
                    .log("Completed transaction fraud scoring.");
        } catch (RuntimeException exception) {
            scoringMetrics.recordScoringRequest(
                    scoringProperties.mode(),
                    null,
                    false,
                    false,
                    System.nanoTime() - startedAt
            );
            throw exception;
        }
    }

    private Optional<EngineIntelligenceSummary> engineIntelligence(FraudScoringRequest scoringRequest) {
        return engineIntelligenceEmissionService.emitIfEnabled(scoringRequest);
    }

    private boolean fallbackUsed(FraudScoreResult result) {
        Object value = result.explanationMetadata().get("mlDiagnostics");
        if (!(value instanceof java.util.Map<?, ?> diagnostics)) {
            return false;
        }
        return Boolean.TRUE.equals(diagnostics.get("fallbackUsed"));
    }
}
