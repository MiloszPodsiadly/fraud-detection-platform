package com.frauddetection.enricher.service;

import com.frauddetection.common.events.contract.TransactionRawEvent;

public interface TransactionFeatureEnrichmentUseCase {

    void enrich(TransactionRawEvent event);

    /** Private origin proof supplied by the Kafka listener, never by transaction JSON. */
    default void enrich(TransactionRawEvent event, String originProof) { enrich(event); }
}
