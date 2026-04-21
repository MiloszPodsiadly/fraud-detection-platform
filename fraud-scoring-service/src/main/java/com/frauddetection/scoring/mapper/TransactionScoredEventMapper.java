package com.frauddetection.scoring.mapper;

import com.frauddetection.common.events.contract.TransactionEnrichedEvent;
import com.frauddetection.common.events.contract.TransactionScoredEvent;
import com.frauddetection.scoring.domain.FraudScoreResult;
import com.frauddetection.scoring.domain.FraudScoringRequest;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

@Component
public class TransactionScoredEventMapper {

    public TransactionScoredEvent toEvent(FraudScoringRequest scoringRequest, FraudScoreResult scoreResult) {
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
                scoreResult.alertRecommended()
        );
    }
}
