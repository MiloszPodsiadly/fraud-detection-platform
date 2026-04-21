package com.frauddetection.ingest.messaging;

import com.frauddetection.common.events.contract.TransactionRawEvent;

public interface TransactionRawEventProducer {

    void publish(TransactionRawEvent event);
}
