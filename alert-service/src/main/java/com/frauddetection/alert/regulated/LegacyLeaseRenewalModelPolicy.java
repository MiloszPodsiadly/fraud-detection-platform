package com.frauddetection.alert.regulated;

import org.springframework.stereotype.Component;

import java.util.Set;

@Component
public class LegacyLeaseRenewalModelPolicy implements RegulatedMutationLeaseRenewalModelPolicy {

    private static final Set<RegulatedMutationState> RENEWABLE_STATES = Set.of(
            RegulatedMutationState.REQUESTED,
            RegulatedMutationState.AUDIT_ATTEMPTED,
            RegulatedMutationState.BUSINESS_COMMITTING,
            RegulatedMutationState.BUSINESS_COMMITTED,
            RegulatedMutationState.SUCCESS_AUDIT_PENDING
    );

    @Override
    public RegulatedMutationModelVersion modelVersion() {
        return RegulatedMutationModelVersion.LEGACY_REGULATED_MUTATION;
    }

    @Override
    public boolean isRenewableState(RegulatedMutationState state) {
        return RENEWABLE_STATES.contains(state);
    }

    @Override
    public RegulatedMutationState recoveryStateForBudgetExceeded(RegulatedMutationState currentState) {
        return currentState;
    }
}
