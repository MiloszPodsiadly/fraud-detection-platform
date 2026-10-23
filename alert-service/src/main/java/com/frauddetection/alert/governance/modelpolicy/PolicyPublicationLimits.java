package com.frauddetection.alert.governance.modelpolicy;

/** Shared publication and operator-recovery budget. A lease reconciliation is not a new signing attempt. */
public final class PolicyPublicationLimits {
    public static final int BASE_ATTEMPTS = 5;
    public static final int MAX_RECOVERIES = 3;
    public static final int MAX_TOTAL_ATTEMPTS = BASE_ATTEMPTS + MAX_RECOVERIES;
    public static final int MAX_RECONCILIATIONS = 3;
    public static final int LEASE_SECONDS = 120;

    private PolicyPublicationLimits() { }

    public static int authorizedAttempts(int recoveryCount) {
        if (recoveryCount < 0 || recoveryCount > MAX_RECOVERIES)
            throw new IllegalArgumentException("Invalid publication recovery count");
        return BASE_ATTEMPTS + recoveryCount;
    }
}
