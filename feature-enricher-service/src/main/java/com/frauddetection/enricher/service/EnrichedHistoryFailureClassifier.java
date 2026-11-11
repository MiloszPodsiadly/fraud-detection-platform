package com.frauddetection.enricher.service;

import java.util.Set;

/** Classifies deterministic feature-history integrity failures separately from infrastructure failures. */
final class EnrichedHistoryFailureClassifier {
    private static final Set<String> PERMANENT_CODES = Set.of(
            "ATTESTED_FEATURE_HISTORY_INPUT_INVALID",
            "FEATURE_HISTORY_COMMITMENT_CONFLICT",
            "FEATURE_HISTORY_REBUILD_REQUIRED",
            "FEATURE_HISTORY_REDIS_SCHEMA_CONFLICT",
            "FEATURE_HISTORY_TIMESTAMP_INVALID",
            "MALFORMED_LAST_TRANSACTION_TIMESTAMP");

    private EnrichedHistoryFailureClassifier() { }

    static boolean isPermanent(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current.getMessage() != null && PERMANENT_CODES.contains(current.getMessage())) { return true; }
        }
        return false;
    }
}
