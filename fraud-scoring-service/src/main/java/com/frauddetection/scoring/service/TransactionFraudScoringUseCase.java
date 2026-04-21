package com.frauddetection.scoring.service;

import com.frauddetection.common.events.contract.TransactionEnrichedEvent;

public interface TransactionFraudScoringUseCase {

    void score(TransactionEnrichedEvent event);
}
