package com.frauddetection.scoring.mapper;

import com.frauddetection.common.events.contract.TransactionEnrichedEvent;
import com.frauddetection.common.events.contract.TransactionScoredEvent;
import com.frauddetection.common.events.intelligence.EngineIntelligenceSummary;
import com.frauddetection.common.events.recommendation.AnalystRecommendationResult;
import com.frauddetection.scoring.domain.FraudScoreResult;
import com.frauddetection.scoring.domain.FraudScoringRequest;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Component
public class TransactionScoredEventMapper {

    public TransactionScoredEvent toEvent(FraudScoringRequest scoringRequest, FraudScoreResult scoreResult) {
        return toEvent(scoringRequest, scoreResult, Optional.empty(), null);
    }

    public TransactionScoredEvent toEvent(
            FraudScoringRequest scoringRequest,
            FraudScoreResult scoreResult,
            Optional<EngineIntelligenceSummary> engineIntelligence
    ) {
        return toEvent(scoringRequest, scoreResult, engineIntelligence, null);
    }

    public TransactionScoredEvent toEvent(
            FraudScoringRequest scoringRequest,
            FraudScoreResult scoreResult,
            Optional<EngineIntelligenceSummary> engineIntelligence,
            AnalystRecommendationResult analystRecommendation
    ) {
        TransactionEnrichedEvent event = scoringRequest.event();
        return new TransactionScoredEvent(
                UUID.randomUUID().toString(),
                event.transactionId(),
                event.correlationId(),
                event.customerId(),
                event.accountId(),
                Instant.now(),
                event.transactionTimestamp(),
                event.transactionAmount(),
                event.merchantInfo(),
                event.deviceInfo(),
                event.locationInfo(),
                event.customerContext(),
                scoreResult.fraudScore(),
                scoreResult.riskLevel(),
                scoreResult.scoringStrategy(),
                scoreResult.modelName(),
                scoreResult.modelVersion(),
                scoreResult.inferenceTimestamp(),
                scoreResult.reasonCodes(),
                scoreResult.scoreDetails(),
                scoreResult.featureSnapshot(),
                scoreResult.alertRecommended(),
                scoreResult.scoringEvidence(),
                engineIntelligence.orElse(null),
                analystRecommendation
        );
    }
}
