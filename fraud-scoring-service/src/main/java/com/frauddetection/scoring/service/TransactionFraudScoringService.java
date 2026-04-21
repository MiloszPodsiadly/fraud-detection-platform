package com.frauddetection.scoring.service;

import com.frauddetection.common.events.contract.TransactionEnrichedEvent;
import com.frauddetection.common.events.contract.TransactionScoredEvent;
import com.frauddetection.scoring.domain.FraudScoreResult;
import com.frauddetection.scoring.domain.FraudScoringRequest;
import com.frauddetection.scoring.mapper.TransactionScoredEventMapper;
import com.frauddetection.scoring.messaging.TransactionScoredEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class TransactionFraudScoringService implements TransactionFraudScoringUseCase {

    private static final Logger log = LoggerFactory.getLogger(TransactionFraudScoringService.class);

    private final FraudScoringEngine fraudScoringEngine;
    private final TransactionScoredEventMapper transactionScoredEventMapper;
    private final TransactionScoredEventPublisher transactionScoredEventPublisher;

    public TransactionFraudScoringService(
            FraudScoringEngine fraudScoringEngine,
            TransactionScoredEventMapper transactionScoredEventMapper,
            TransactionScoredEventPublisher transactionScoredEventPublisher
    ) {
        this.fraudScoringEngine = fraudScoringEngine;
        this.transactionScoredEventMapper = transactionScoredEventMapper;
        this.transactionScoredEventPublisher = transactionScoredEventPublisher;
    }

    @Override
    public void score(TransactionEnrichedEvent event) {
        log.atInfo()
                .addKeyValue("transactionId", event.transactionId())
                .addKeyValue("correlationId", event.correlationId())
                .log("Started transaction fraud scoring.");

        FraudScoringRequest scoringRequest = FraudScoringRequest.from(event);
        FraudScoreResult scoreResult = fraudScoringEngine.score(scoringRequest);
        TransactionScoredEvent scoredEvent = transactionScoredEventMapper.toEvent(scoringRequest, scoreResult);
        transactionScoredEventPublisher.publish(scoredEvent);

        log.atInfo()
                .addKeyValue("transactionId", event.transactionId())
                .addKeyValue("correlationId", event.correlationId())
                .addKeyValue("riskLevel", scoreResult.riskLevel())
                .addKeyValue("fraudScore", scoreResult.fraudScore())
                .log("Completed transaction fraud scoring.");
    }
}
