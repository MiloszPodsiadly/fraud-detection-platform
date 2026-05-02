package com.frauddetection.alert.api;

import com.fasterxml.jackson.annotation.JsonProperty;

public record UpdateFraudCaseResponse(
        @JsonProperty("operation_status")
        SubmitDecisionOperationStatus operationStatus,
        @JsonProperty("command_id")
        String commandId,
        @JsonProperty("idempotency_key_hash")
        String idempotencyKeyHash,
        @JsonProperty("case_id")
        String caseId,
        @JsonProperty("current_case_snapshot")
        FraudCaseResponse currentCaseSnapshot,
        @JsonProperty("updated_case")
        FraudCaseResponse updatedCase,
        @JsonProperty("recovery_required_reason")
        String recoveryRequiredReason
) implements RegulatedMutationPublicStatusProjection<UpdateFraudCaseResponse> {

    @Override
    public UpdateFraudCaseResponse withPublicStatus(SubmitDecisionOperationStatus status) {
        return new UpdateFraudCaseResponse(
                status,
                commandId,
                idempotencyKeyHash,
                caseId,
                currentCaseSnapshot,
                updatedCase,
                recoveryRequiredReason
        );
    }
}
