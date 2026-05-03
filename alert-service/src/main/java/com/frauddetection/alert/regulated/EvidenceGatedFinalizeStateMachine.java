package com.frauddetection.alert.regulated;

import java.util.Map;
import java.util.Set;

public final class EvidenceGatedFinalizeStateMachine {

    private static final Map<RegulatedMutationState, Set<RegulatedMutationState>> ALLOWED = Map.of(
            RegulatedMutationState.REQUESTED, Set.of(RegulatedMutationState.EVIDENCE_PREPARING, RegulatedMutationState.REJECTED_EVIDENCE_UNAVAILABLE),
            RegulatedMutationState.EVIDENCE_PREPARING, Set.of(RegulatedMutationState.EVIDENCE_PREPARED, RegulatedMutationState.REJECTED_EVIDENCE_UNAVAILABLE),
            RegulatedMutationState.EVIDENCE_PREPARED, Set.of(RegulatedMutationState.FINALIZING, RegulatedMutationState.REJECTED_EVIDENCE_UNAVAILABLE),
            RegulatedMutationState.FINALIZING, Set.of(RegulatedMutationState.FINALIZED_VISIBLE, RegulatedMutationState.FINALIZE_RECOVERY_REQUIRED),
            RegulatedMutationState.FINALIZED_VISIBLE, Set.of(RegulatedMutationState.FINALIZED_EVIDENCE_PENDING_EXTERNAL, RegulatedMutationState.FINALIZE_RECOVERY_REQUIRED),
            RegulatedMutationState.FINALIZED_EVIDENCE_PENDING_EXTERNAL, Set.of(RegulatedMutationState.FINALIZED_EVIDENCE_CONFIRMED)
    );

    public boolean isTerminal(RegulatedMutationState state) {
        return state == RegulatedMutationState.FINALIZED_EVIDENCE_CONFIRMED
                || state == RegulatedMutationState.FINALIZED_EVIDENCE_PENDING_EXTERNAL
                || state == RegulatedMutationState.FINALIZED_VISIBLE
                || state == RegulatedMutationState.REJECTED_EVIDENCE_UNAVAILABLE
                || state == RegulatedMutationState.FAILED_BUSINESS_VALIDATION
                || state == RegulatedMutationState.FINALIZE_RECOVERY_REQUIRED;
    }

    public boolean canTransition(RegulatedMutationState from, RegulatedMutationState to) {
        return ALLOWED.getOrDefault(from, Set.of()).contains(to);
    }

    public void requireTransition(RegulatedMutationState from, RegulatedMutationState to) {
        if (!canTransition(from, to)) {
            throw new IllegalStateException("Invalid evidence-gated finalize transition: " + from + " -> " + to);
        }
    }
}
