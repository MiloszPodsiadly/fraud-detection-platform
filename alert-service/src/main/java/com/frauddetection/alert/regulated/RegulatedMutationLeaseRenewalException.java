package com.frauddetection.alert.regulated;

public class RegulatedMutationLeaseRenewalException extends RuntimeException {

    private final String commandId;
    private final RegulatedMutationLeaseRenewalReason reason;

    public RegulatedMutationLeaseRenewalException(String commandId, RegulatedMutationLeaseRenewalReason reason) {
        super("Regulated mutation lease renewal rejected: reason="
                + (reason == null ? RegulatedMutationLeaseRenewalReason.UNKNOWN : reason));
        this.commandId = commandId;
        this.reason = reason == null ? RegulatedMutationLeaseRenewalReason.UNKNOWN : reason;
    }

    public String commandId() {
        return commandId;
    }

    public RegulatedMutationLeaseRenewalReason reason() {
        return reason;
    }
}
