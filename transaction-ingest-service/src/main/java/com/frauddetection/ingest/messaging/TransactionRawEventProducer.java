package com.frauddetection.ingest.messaging;

import com.frauddetection.common.events.contract.TransactionRawEvent;
import com.frauddetection.common.events.source.SourceOriginProofs;

public interface TransactionRawEventProducer {

    void publish(TransactionRawEvent event);

    /** Optional private provenance assertion; implementations without proof support retain their legacy behavior. */
    default void publish(TransactionRawEvent event, SourceOriginProofs.Assertion origin) { publish(event); }
}
