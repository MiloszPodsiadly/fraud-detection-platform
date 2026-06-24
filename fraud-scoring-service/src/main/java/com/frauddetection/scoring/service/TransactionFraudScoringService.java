package com.frauddetection.scoring.service;

import com.frauddetection.common.events.contract.TransactionEnrichedEvent;
import com.frauddetection.common.events.contract.TransactionScoredEvent;
import com.frauddetection.common.events.intelligence.EngineIntelligenceSummary;
import com.frauddetection.common.events.recommendation.AnalystRecommendationResult;
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
    private final AnalystRecommendationService analystRecommendationService;

    public TransactionFraudScoringService(
            FraudScoringEngine fraudScoringEngine,
            TransactionScoredEventMapper transactionScoredEventMapper,
            TransactionScoredEventPublisher transactionScoredEventPublisher,
            ScoringProperties scoringProperties,
            ScoringMetrics scoringMetrics,
            EngineIntelligenceEmissionService engineIntelligenceEmissionService,
            AnalystRecommendationService analystRecommendationService
    ) {
        this.fraudScoringEngine = fraudScoringEngine;
        this.transactionScoredEventMapper = transactionScoredEventMapper;
        this.transactionScoredEventPublisher = transactionScoredEventPublisher;
        this.scoringProperties = scoringProperties;
        this.scoringMetrics = scoringMetrics;
        this.engineIntelligenceEmissionService = engineIntelligenceEmissionService;
        this.analystRecommendationService = analystRecommendationService;
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
            EngineIntelligenceEmission engineIntelligence = engineIntelligence(scoringRequest);
            AnalystRecommendationResult analystRecommendation = analystRecommendation(scoreResult, engineIntelligence);
            TransactionScoredEvent scoredEvent = transactionScoredEventMapper.toEvent(
                    scoringRequest,
                    scoreResult,
                    engineIntelligence.summary(),
                    analystRecommendation
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

    private AnalystRecommendationResult analystRecommendation(FraudScoreResult scoreResult, EngineIntelligenceEmission engineIntelligence) {
        if (engineIntelligence.unavailable()) {
            return analystRecommendationService.unavailable();
        }
        try {
            return analystRecommendationService.recommend(scoreResult, engineIntelligence.summary());
        } catch (RuntimeException exception) {
            log.warn("Analyst recommendation enrichment omitted.", exception);
            return analystRecommendationService.unavailable();
        }
    }

    private EngineIntelligenceEmission engineIntelligence(FraudScoringRequest scoringRequest) {
        try {
            return new EngineIntelligenceEmission(engineIntelligenceEmissionService.emitIfEnabled(scoringRequest), false);
        } catch (RuntimeException exception) {
            log.warn("Engine intelligence enrichment omitted.");
            return new EngineIntelligenceEmission(Optional.empty(), true);
        }
    }

    private boolean fallbackUsed(FraudScoreResult result) {
        Object value = result.explanationMetadata().get("mlDiagnostics");
        if (!(value instanceof java.util.Map<?, ?> diagnostics)) {
            return false;
        }
        return Boolean.TRUE.equals(diagnostics.get("fallbackUsed"));
    }

    private record EngineIntelligenceEmission(Optional<EngineIntelligenceSummary> summary, boolean unavailable) {
    }
}
