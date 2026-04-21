package com.frauddetection.enricher.messaging;

import com.frauddetection.common.events.contract.TransactionEnrichedEvent;

public interface TransactionEnrichedEventPublisher {

    void publish(TransactionEnrichedEvent event);
}
