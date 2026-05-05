package com.frauddetection.alert.regulated;

import org.springframework.stereotype.Component;

import java.util.Set;

@Component
public class EvidenceGatedLeaseRenewalModelPolicy implements RegulatedMutationLeaseRenewalModelPolicy {

    private static final Set<RegulatedMutationState> RENEWABLE_STATES = Set.of(
            RegulatedMutationState.EVIDENCE_PREPARING,
            RegulatedMutationState.EVIDENCE_PREPARED,
            RegulatedMutationState.FINALIZING
    );

    @Override
    public RegulatedMutationModelVersion modelVersion() {
        return RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1;
    }

    @Override
    public boolean isRenewableState(RegulatedMutationState state) {
        return RENEWABLE_STATES.contains(state);
    }

    @Override
    public RegulatedMutationState recoveryStateForBudgetExceeded(RegulatedMutationState currentState) {
        return RegulatedMutationState.FINALIZE_RECOVERY_REQUIRED;
    }
}
