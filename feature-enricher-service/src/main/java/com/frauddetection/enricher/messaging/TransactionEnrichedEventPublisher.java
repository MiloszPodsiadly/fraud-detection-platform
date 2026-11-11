package com.frauddetection.enricher.messaging;

import com.frauddetection.common.events.contract.TransactionEnrichedEvent;
import com.frauddetection.common.events.source.SourceOriginProofs;

public interface TransactionEnrichedEventPublisher {

    void publish(TransactionEnrichedEvent event);

    /** Optional private provenance assertion; implementations without proof support retain their legacy behavior. */
    default void publish(TransactionEnrichedEvent event, SourceOriginProofs.Assertion origin) { publish(event); }
}
