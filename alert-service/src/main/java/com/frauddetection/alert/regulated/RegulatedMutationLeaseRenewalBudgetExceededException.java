package com.frauddetection.alert.regulated;

public class RegulatedMutationLeaseRenewalBudgetExceededException extends RegulatedMutationLeaseRenewalException {

    public RegulatedMutationLeaseRenewalBudgetExceededException(String commandId) {
        super(commandId, RegulatedMutationLeaseRenewalReason.BUDGET_EXCEEDED);
    }
}
