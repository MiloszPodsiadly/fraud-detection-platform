package com.frauddetection.alert.regulated.status;

import com.frauddetection.alert.api.SubmitDecisionOperationStatus;
import com.frauddetection.alert.regulated.RegulatedMutationCommandDocument;
import com.frauddetection.alert.regulated.RegulatedMutationModelVersion;
import com.frauddetection.alert.regulated.RegulatedMutationState;
import org.springframework.stereotype.Component;

@Component
public class RegulatedMutationPublicStatusMapper {

    public SubmitDecisionOperationStatus currentStatus(RegulatedMutationCommandDocument command) {
        if (command == null) {
            return SubmitDecisionOperationStatus.IN_PROGRESS;
        }
        if (command.getMutationModelVersion() != RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1) {
            throw new IllegalStateException("Current regulated mutation status requires EVIDENCE_GATED_FINALIZE_V1.");
        }
        return currentStatus(command.getState());
    }

    public SubmitDecisionOperationStatus currentStatus(RegulatedMutationState state) {
        return evidenceGatedSubmitDecisionStatus(state);
    }

    public SubmitDecisionOperationStatus submitDecisionStatus(RegulatedMutationCommandDocument command) {
        if (command == null) {
            return SubmitDecisionOperationStatus.IN_PROGRESS;
        }
        return submitDecisionStatus(command.getState(), command.getMutationModelVersion());
    }

    public SubmitDecisionOperationStatus submitDecisionStatus(
            RegulatedMutationState state,
            RegulatedMutationModelVersion modelVersion
    ) {
        if (modelVersion != RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1) {
            throw new IllegalStateException("Current regulated mutation status requires EVIDENCE_GATED_FINALIZE_V1.");
        }
        return evidenceGatedSubmitDecisionStatus(state);
    }

    private SubmitDecisionOperationStatus evidenceGatedSubmitDecisionStatus(RegulatedMutationState state) {
        return switch (state) {
            case REQUESTED -> SubmitDecisionOperationStatus.IN_PROGRESS;
            case EVIDENCE_PREPARING -> SubmitDecisionOperationStatus.EVIDENCE_PREPARING;
            case EVIDENCE_PREPARED -> SubmitDecisionOperationStatus.EVIDENCE_PREPARED;
            case FINALIZING -> SubmitDecisionOperationStatus.FINALIZING;
            case FINALIZED_EVIDENCE_PENDING_EXTERNAL ->
                    SubmitDecisionOperationStatus.FINALIZED_EVIDENCE_PENDING_EXTERNAL;
            case FINALIZED_EVIDENCE_CONFIRMED -> SubmitDecisionOperationStatus.FINALIZED_EVIDENCE_CONFIRMED;
            case REJECTED_EVIDENCE_UNAVAILABLE -> SubmitDecisionOperationStatus.REJECTED_EVIDENCE_UNAVAILABLE;
            case FAILED_BUSINESS_VALIDATION -> SubmitDecisionOperationStatus.FAILED_BUSINESS_VALIDATION;
            case FINALIZE_RECOVERY_REQUIRED -> SubmitDecisionOperationStatus.FINALIZE_RECOVERY_REQUIRED;
            case FAILED -> SubmitDecisionOperationStatus.FINALIZE_RECOVERY_REQUIRED;
        };
    }
}
