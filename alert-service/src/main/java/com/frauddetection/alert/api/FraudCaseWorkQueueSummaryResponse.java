package com.frauddetection.alert.api;

import java.time.Instant;

public record FraudCaseWorkQueueSummaryResponse(
        long totalFraudCases,
        Instant generatedAt,
        String scope,
        boolean snapshotConsistentWithWorkQueue
) {
    public static final String GLOBAL_FRAUD_CASES_SCOPE = "GLOBAL_FRAUD_CASES";

    public FraudCaseWorkQueueSummaryResponse(long totalFraudCases) {
        this(totalFraudCases, Instant.EPOCH);
    }

    public FraudCaseWorkQueueSummaryResponse(long totalFraudCases, Instant generatedAt) {
        this(totalFraudCases, generatedAt, GLOBAL_FRAUD_CASES_SCOPE, false);
    }
}
