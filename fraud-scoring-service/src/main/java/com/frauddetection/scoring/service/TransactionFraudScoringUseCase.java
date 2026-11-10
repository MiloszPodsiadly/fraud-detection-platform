package com.frauddetection.scoring.service;

import com.frauddetection.common.events.contract.TransactionEnrichedEvent;

public interface TransactionFraudScoringUseCase {

    void score(TransactionEnrichedEvent event);

    /** Private consumer-to-service provenance, supplied only from the authenticated Kafka header. */
    default void score(TransactionEnrichedEvent event, String originProof) { score(event); }
}
