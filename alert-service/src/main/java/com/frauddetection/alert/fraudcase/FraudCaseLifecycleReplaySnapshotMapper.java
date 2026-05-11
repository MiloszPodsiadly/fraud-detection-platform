package com.frauddetection.alert.fraudcase;

import com.frauddetection.alert.api.FraudCaseDecisionResponse;
import com.frauddetection.alert.api.FraudCaseNoteResponse;
import com.frauddetection.alert.api.FraudCaseResponse;

import java.time.Instant;

public class FraudCaseLifecycleReplaySnapshotMapper {

    public FraudCaseLifecycleReplaySnapshot toSnapshot(
            FraudCaseLifecycleIdempotencyCommand command,
            Object response,
            Instant completedAt
    ) {
        if (response instanceof FraudCaseResponse caseResponse) {
            return new FraudCaseLifecycleReplaySnapshot(
                    FraudCaseLifecycleReplaySnapshot.FORMAT,
                    FraudCaseLifecycleReplaySnapshot.VERSION,
                    FraudCaseLifecycleReplaySnapshotType.CASE,
                    command.action(),
                    completedAt,
                    caseResponse,
                    null,
                    null
            );
        }
        if (response instanceof FraudCaseNoteResponse note) {
            return new FraudCaseLifecycleReplaySnapshot(
                    FraudCaseLifecycleReplaySnapshot.FORMAT,
                    FraudCaseLifecycleReplaySnapshot.VERSION,
                    FraudCaseLifecycleReplaySnapshotType.NOTE,
                    command.action(),
                    completedAt,
                    null,
                    note,
                    null
            );
        }
        if (response instanceof FraudCaseDecisionResponse decision) {
            return new FraudCaseLifecycleReplaySnapshot(
                    FraudCaseLifecycleReplaySnapshot.FORMAT,
                    FraudCaseLifecycleReplaySnapshot.VERSION,
                    FraudCaseLifecycleReplaySnapshotType.DECISION,
                    command.action(),
                    completedAt,
                    null,
                    null,
                    decision
            );
        }
        throw new UnsupportedFraudCaseLifecycleReplaySnapshotException();
    }

    @SuppressWarnings("unchecked")
    public <T> T toResponse(FraudCaseLifecycleReplaySnapshot snapshot, Class<T> responseType) {
        if (responseType == FraudCaseResponse.class && snapshot.snapshotType() == FraudCaseLifecycleReplaySnapshotType.CASE) {
            if (snapshot.caseResponse() == null) {
                throw new IllegalStateException("Fraud case lifecycle replay snapshot is missing case response.");
            }
            return (T) snapshot.caseResponse();
        }
        if (responseType == FraudCaseNoteResponse.class && snapshot.snapshotType() == FraudCaseLifecycleReplaySnapshotType.NOTE) {
            if (snapshot.noteResponse() == null) {
                throw new IllegalStateException("Fraud case lifecycle replay snapshot is missing note response.");
            }
            return (T) snapshot.noteResponse();
        }
        if (responseType == FraudCaseDecisionResponse.class && snapshot.snapshotType() == FraudCaseLifecycleReplaySnapshotType.DECISION) {
            if (snapshot.decisionResponse() == null) {
                throw new IllegalStateException("Fraud case lifecycle replay snapshot is missing decision response.");
            }
            return (T) snapshot.decisionResponse();
        }
        throw new IllegalStateException("Unsupported fraud case lifecycle replay snapshot type.");
    }
}
