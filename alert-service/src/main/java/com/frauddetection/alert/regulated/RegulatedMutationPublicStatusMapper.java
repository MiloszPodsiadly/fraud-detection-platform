package com.frauddetection.alert.regulated;

import com.frauddetection.alert.api.SubmitDecisionOperationStatus;
import org.springframework.stereotype.Component;

@Component
public class RegulatedMutationPublicStatusMapper {

    public SubmitDecisionOperationStatus submitDecisionStatus(RegulatedMutationCommandDocument command) {
        if (command == null) {
            return SubmitDecisionOperationStatus.IN_PROGRESS;
        }
        return submitDecisionStatus(command.getState(), command.mutationModelVersionOrLegacy());
    }

    public SubmitDecisionOperationStatus submitDecisionStatus(
            RegulatedMutationState state,
            RegulatedMutationModelVersion modelVersion
    ) {
        if (modelVersion == RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1) {
            return evidenceGatedSubmitDecisionStatus(state);
        }
        return legacySubmitDecisionStatus(state);
    }

    private SubmitDecisionOperationStatus evidenceGatedSubmitDecisionStatus(RegulatedMutationState state) {
        return switch (state) {
            case REQUESTED, AUDIT_ATTEMPTED -> SubmitDecisionOperationStatus.IN_PROGRESS;
            case EVIDENCE_PREPARING -> SubmitDecisionOperationStatus.EVIDENCE_PREPARING;
            case EVIDENCE_PREPARED -> SubmitDecisionOperationStatus.EVIDENCE_PREPARED;
            case FINALIZING -> SubmitDecisionOperationStatus.FINALIZING;
            case FINALIZED_VISIBLE -> SubmitDecisionOperationStatus.FINALIZED_EVIDENCE_PENDING_EXTERNAL;
            case FINALIZED_EVIDENCE_PENDING_EXTERNAL -> SubmitDecisionOperationStatus.FINALIZED_EVIDENCE_PENDING_EXTERNAL;
            case FINALIZED_EVIDENCE_CONFIRMED -> SubmitDecisionOperationStatus.FINALIZED_EVIDENCE_CONFIRMED;
            case REJECTED_EVIDENCE_UNAVAILABLE -> SubmitDecisionOperationStatus.REJECTED_EVIDENCE_UNAVAILABLE;
            case FAILED_BUSINESS_VALIDATION -> SubmitDecisionOperationStatus.FAILED_BUSINESS_VALIDATION;
            case FINALIZE_RECOVERY_REQUIRED -> SubmitDecisionOperationStatus.FINALIZE_RECOVERY_REQUIRED;
            case FAILED, BUSINESS_COMMITTING, BUSINESS_COMMITTED, SUCCESS_AUDIT_PENDING,
                 SUCCESS_AUDIT_RECORDED, EVIDENCE_PENDING, COMMITTED_DEGRADED ->
                    SubmitDecisionOperationStatus.FINALIZE_RECOVERY_REQUIRED;
            case EVIDENCE_CONFIRMED, COMMITTED -> SubmitDecisionOperationStatus.FINALIZED_EVIDENCE_CONFIRMED;
            case REJECTED -> SubmitDecisionOperationStatus.REJECTED_EVIDENCE_UNAVAILABLE;
        };
    }

    private SubmitDecisionOperationStatus legacySubmitDecisionStatus(RegulatedMutationState state) {
        return switch (state) {
            case REQUESTED, AUDIT_ATTEMPTED, EVIDENCE_PREPARING, EVIDENCE_PREPARED, FINALIZING ->
                    SubmitDecisionOperationStatus.IN_PROGRESS;
            case BUSINESS_COMMITTING -> SubmitDecisionOperationStatus.COMMIT_UNKNOWN;
            case EVIDENCE_PENDING, COMMITTED, SUCCESS_AUDIT_RECORDED,
                 FINALIZED_VISIBLE, FINALIZED_EVIDENCE_PENDING_EXTERNAL ->
                    SubmitDecisionOperationStatus.COMMITTED_EVIDENCE_PENDING;
            case EVIDENCE_CONFIRMED, FINALIZED_EVIDENCE_CONFIRMED ->
                    SubmitDecisionOperationStatus.COMMITTED_EVIDENCE_CONFIRMED;
            case COMMITTED_DEGRADED -> SubmitDecisionOperationStatus.COMMITTED_EVIDENCE_INCOMPLETE;
            case FAILED, BUSINESS_COMMITTED, SUCCESS_AUDIT_PENDING, FINALIZE_RECOVERY_REQUIRED ->
                    SubmitDecisionOperationStatus.RECOVERY_REQUIRED;
            case REJECTED, REJECTED_EVIDENCE_UNAVAILABLE, FAILED_BUSINESS_VALIDATION ->
                    SubmitDecisionOperationStatus.REJECTED_BEFORE_MUTATION;
        };
    }
}
