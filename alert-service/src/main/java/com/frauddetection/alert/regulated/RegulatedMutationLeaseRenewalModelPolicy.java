package com.frauddetection.alert.regulated;

public interface RegulatedMutationLeaseRenewalModelPolicy {

    RegulatedMutationModelVersion modelVersion();

    boolean isRenewableState(RegulatedMutationState state);

    RegulatedMutationState recoveryStateForBudgetExceeded(RegulatedMutationState currentState);
}
