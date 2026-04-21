package com.frauddetection.scoring.messaging;

import com.frauddetection.common.events.contract.TransactionScoredEvent;

public interface TransactionScoredEventPublisher {

    void publish(TransactionScoredEvent event);
}
