package com.frauddetection.simulator.messaging;

import com.frauddetection.common.events.contract.TransactionRawEvent;

public interface TransactionRawEventPublisher {

    void publish(TransactionRawEvent event);
}
