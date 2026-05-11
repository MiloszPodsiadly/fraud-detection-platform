package com.frauddetection.alert.fraudcase;

import com.frauddetection.alert.api.FraudCaseDecisionResponse;
import com.frauddetection.alert.api.FraudCaseNoteResponse;
import com.frauddetection.alert.api.FraudCaseResponse;
import java.time.Instant;

public record FraudCaseLifecycleReplaySnapshot(
        String snapshotFormat,
        int snapshotVersion,
        FraudCaseLifecycleReplaySnapshotType snapshotType,
        String action,
        Instant completedAt,
        FraudCaseResponse caseResponse,
        FraudCaseNoteResponse noteResponse,
        FraudCaseDecisionResponse decisionResponse
) {
    public static final String FORMAT = "FDP_44_REPLAY_SNAPSHOT";
    public static final int VERSION = 1;
}
