package com.frauddetection.enricher.persistence;

import com.frauddetection.common.events.contract.TransactionRawEvent;
import com.frauddetection.enricher.domain.FeatureStoreSnapshot;

public interface FeatureStore {

    boolean hasRecordedTransaction(TransactionRawEvent event);

    FeatureStoreSnapshot loadSnapshot(TransactionRawEvent event);

    void recordTransaction(TransactionRawEvent event);
}
