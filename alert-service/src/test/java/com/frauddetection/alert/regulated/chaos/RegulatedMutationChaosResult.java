package com.frauddetection.alert.regulated.chaos;

import com.frauddetection.alert.api.SubmitDecisionOperationStatus;
import com.frauddetection.alert.regulated.RegulatedMutationExecutionStatus;
import com.frauddetection.alert.regulated.RegulatedMutationState;
import com.frauddetection.common.events.enums.AlertStatus;
import com.frauddetection.common.events.enums.AnalystDecision;
import com.fasterxml.jackson.databind.JsonNode;

public record RegulatedMutationChaosResult(
        String scenarioName,
        RegulatedMutationChaosWindow window,
        RegulatedMutationStateReachMethod stateReachMethod,
        RegulatedMutationProofLevel proofLevel,
        String killedTargetId,
        String restartedTargetId,
        String killedTargetName,
        String restartedTargetName,
        boolean targetKilled,
        boolean targetRestarted,
        RegulatedMutationState commandState,
        RegulatedMutationExecutionStatus executionStatus,
        SubmitDecisionOperationStatus publicStatus,
        boolean responseSnapshotPresent,
        boolean localCommitMarkerPresent,
        AlertStatus alertStatus,
        AnalystDecision analystDecision,
        long outboxRecords,
        long attemptedAuditEvents,
        long successAuditEvents,
        long businessMutationCount,
        JsonNode inspectionResponse,
        JsonNode recoveryResponse
) {
}
