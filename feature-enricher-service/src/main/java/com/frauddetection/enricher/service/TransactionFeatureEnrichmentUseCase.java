package com.frauddetection.enricher.service;

import com.frauddetection.common.events.contract.TransactionRawEvent;

public interface TransactionFeatureEnrichmentUseCase {

    void enrich(TransactionRawEvent event);
}
