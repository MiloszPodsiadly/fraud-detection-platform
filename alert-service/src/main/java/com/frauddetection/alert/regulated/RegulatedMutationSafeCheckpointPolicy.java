package com.frauddetection.alert.regulated;

import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

@Component
public class RegulatedMutationSafeCheckpointPolicy {

    private static final Set<RegulatedMutationState> RECOVERY_STATES = Set.of(
            RegulatedMutationState.FINALIZE_RECOVERY_REQUIRED,
            RegulatedMutationState.FAILED
    );
    private static final Set<RegulatedMutationState> TERMINAL_STATES = Set.of(
            RegulatedMutationState.FINALIZED_VISIBLE,
            RegulatedMutationState.FINALIZED_EVIDENCE_PENDING_EXTERNAL,
            RegulatedMutationState.FINALIZED_EVIDENCE_CONFIRMED,
            RegulatedMutationState.REJECTED_EVIDENCE_UNAVAILABLE,
            RegulatedMutationState.FAILED_BUSINESS_VALIDATION,
            RegulatedMutationState.SUCCESS_AUDIT_RECORDED,
            RegulatedMutationState.EVIDENCE_PENDING,
            RegulatedMutationState.EVIDENCE_CONFIRMED,
            RegulatedMutationState.COMMITTED,
            RegulatedMutationState.COMMITTED_DEGRADED,
            RegulatedMutationState.REJECTED
    );

    private final Map<RegulatedMutationModelVersion, Map<RegulatedMutationState, Set<RegulatedMutationRenewalCheckpoint>>> table;

    public RegulatedMutationSafeCheckpointPolicy() {
        this.table = table();
    }

    public RegulatedMutationLeaseRenewalReason rejectionReason(
            RegulatedMutationModelVersion modelVersion,
            RegulatedMutationState state,
            RegulatedMutationExecutionStatus executionStatus,
            RegulatedMutationRenewalCheckpoint checkpoint
    ) {
        if (checkpoint == null) {
            return RegulatedMutationLeaseRenewalReason.UNKNOWN;
        }
        if (executionStatus == RegulatedMutationExecutionStatus.RECOVERY_REQUIRED || RECOVERY_STATES.contains(state)) {
            return RegulatedMutationLeaseRenewalReason.RECOVERY_STATE;
        }
        if (TERMINAL_STATES.contains(state)) {
            return RegulatedMutationLeaseRenewalReason.TERMINAL_STATE;
        }
        if (executionStatus != RegulatedMutationExecutionStatus.PROCESSING) {
            return RegulatedMutationLeaseRenewalReason.EXECUTION_STATUS_MISMATCH;
        }
        if (!isAllowed(modelVersion, state, executionStatus, checkpoint)) {
            return RegulatedMutationLeaseRenewalReason.NON_RENEWABLE_STATE;
        }
        return RegulatedMutationLeaseRenewalReason.NONE;
    }

    public boolean isAllowed(
            RegulatedMutationModelVersion modelVersion,
            RegulatedMutationState state,
            RegulatedMutationExecutionStatus executionStatus,
            RegulatedMutationRenewalCheckpoint checkpoint
    ) {
        if (state == null
                || checkpoint == null
                || executionStatus != RegulatedMutationExecutionStatus.PROCESSING
                || RECOVERY_STATES.contains(state)
                || TERMINAL_STATES.contains(state)) {
            return false;
        }
        RegulatedMutationModelVersion effectiveModel =
                modelVersion == null ? RegulatedMutationModelVersion.LEGACY_REGULATED_MUTATION : modelVersion;
        return table.getOrDefault(effectiveModel, Map.of())
                .getOrDefault(state, Set.of())
                .contains(checkpoint);
    }

    private Map<RegulatedMutationModelVersion, Map<RegulatedMutationState, Set<RegulatedMutationRenewalCheckpoint>>> table() {
        Map<RegulatedMutationModelVersion, Map<RegulatedMutationState, Set<RegulatedMutationRenewalCheckpoint>>> byModel =
                new EnumMap<>(RegulatedMutationModelVersion.class);

        Map<RegulatedMutationState, Set<RegulatedMutationRenewalCheckpoint>> legacy =
                new EnumMap<>(RegulatedMutationState.class);
        legacy.put(RegulatedMutationState.REQUESTED, Set.of(
                RegulatedMutationRenewalCheckpoint.BEFORE_ATTEMPTED_AUDIT
        ));
        legacy.put(RegulatedMutationState.AUDIT_ATTEMPTED, Set.of(
                RegulatedMutationRenewalCheckpoint.AFTER_ATTEMPTED_AUDIT,
                RegulatedMutationRenewalCheckpoint.BEFORE_LEGACY_BUSINESS_COMMIT
        ));
        legacy.put(RegulatedMutationState.BUSINESS_COMMITTING, Set.of(
                RegulatedMutationRenewalCheckpoint.BEFORE_LEGACY_BUSINESS_COMMIT
        ));
        legacy.put(RegulatedMutationState.BUSINESS_COMMITTED, Set.of(
                RegulatedMutationRenewalCheckpoint.BEFORE_SUCCESS_AUDIT_RETRY
        ));
        legacy.put(RegulatedMutationState.SUCCESS_AUDIT_PENDING, Set.of(
                RegulatedMutationRenewalCheckpoint.BEFORE_SUCCESS_AUDIT_RETRY
        ));
        byModel.put(RegulatedMutationModelVersion.LEGACY_REGULATED_MUTATION, Map.copyOf(legacy));

        Map<RegulatedMutationState, Set<RegulatedMutationRenewalCheckpoint>> evidence =
                new EnumMap<>(RegulatedMutationState.class);
        evidence.put(RegulatedMutationState.EVIDENCE_PREPARING, Set.of(
                RegulatedMutationRenewalCheckpoint.BEFORE_EVIDENCE_PREPARATION
        ));
        evidence.put(RegulatedMutationState.EVIDENCE_PREPARED, Set.of(
                RegulatedMutationRenewalCheckpoint.AFTER_EVIDENCE_PREPARED_BEFORE_FINALIZE,
                RegulatedMutationRenewalCheckpoint.BEFORE_EVIDENCE_GATED_FINALIZE
        ));
        evidence.put(RegulatedMutationState.FINALIZING, Set.of(
                RegulatedMutationRenewalCheckpoint.BEFORE_EVIDENCE_GATED_FINALIZE
        ));
        byModel.put(RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1, Map.copyOf(evidence));

        return Map.copyOf(byModel);
    }
}
