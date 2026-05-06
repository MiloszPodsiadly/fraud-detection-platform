package com.frauddetection.alert.regulated.chaos;

import com.frauddetection.alert.api.SubmitDecisionOperationStatus;
import com.frauddetection.alert.regulated.RegulatedMutationExecutionStatus;
import com.frauddetection.alert.regulated.RegulatedMutationState;
import com.frauddetection.common.events.enums.AlertStatus;
import com.frauddetection.common.events.enums.AnalystDecision;

public record RegulatedMutationChaosResult(
        String scenarioName,
        RegulatedMutationChaosWindow window,
        String killedContainerId,
        String restartedContainerId,
        boolean containerKilled,
        boolean containerRestarted,
        RegulatedMutationState commandState,
        RegulatedMutationExecutionStatus executionStatus,
        SubmitDecisionOperationStatus publicStatus,
        boolean responseSnapshotPresent,
        AlertStatus alertStatus,
        AnalystDecision analystDecision,
        long outboxRecords,
        long attemptedAuditEvents,
        long successAuditEvents,
        long businessMutationCount
) {
}
