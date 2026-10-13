package com.frauddetection.alert.audit;

import java.time.Instant;

public interface ExternalAuditHeadRecoveryTransitions {

    void recordVerifiedRepair(String incidentId, Instant attemptedAt);

    void recordFailedRepair(String incidentId, String outcome, Instant attemptedAt);
}
