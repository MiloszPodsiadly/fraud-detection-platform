package com.frauddetection.enricher.persistence;

import com.frauddetection.common.events.contract.TransactionRawEvent;
import com.frauddetection.common.events.contract.TransactionEnrichedEvent;
import com.frauddetection.enricher.domain.FeatureStoreSnapshot;

public interface FeatureStore {

    boolean hasRecordedTransaction(TransactionRawEvent event);

    FeatureStoreSnapshot loadSnapshot(TransactionRawEvent event);

    void recordTransaction(TransactionRawEvent event);

    /** Atomically applies one attested history effect; commitment is receipt + immutable ENRICHED commitment. */
    default void recordAttestedTransaction(TransactionEnrichedEvent event, FeatureNamespace namespace, String historyCommitment) {
        throw new UnsupportedOperationException("ATTESTED_FEATURE_HISTORY_UNSUPPORTED");
    }

    /** Reconciles an already Mongo-acknowledged effect; a missing Redis proof must fail closed. */
    default void verifyAttestedTransaction(TransactionEnrichedEvent event, FeatureNamespace namespace, String historyCommitment) {
        recordAttestedTransaction(event, namespace, historyCommitment);
    }

    default boolean hasRecordedTransaction(TransactionRawEvent event, FeatureNamespace namespace) {
        requireLegacy(namespace);
        return hasRecordedTransaction(event);
    }

    default FeatureStoreSnapshot loadSnapshot(TransactionRawEvent event, FeatureNamespace namespace) {
        requireLegacy(namespace);
        return loadSnapshot(event);
    }

    default void recordTransaction(TransactionRawEvent event, FeatureNamespace namespace) {
        requireLegacy(namespace);
        recordTransaction(event);
    }

    private static void requireLegacy(FeatureNamespace namespace) {
        if (namespace == null) { throw new IllegalArgumentException("FEATURE_NAMESPACE_REQUIRED"); }
        if (!namespace.isLegacy()) { throw new UnsupportedOperationException("FEATURE_NAMESPACE_UNSUPPORTED"); }
    }
}
