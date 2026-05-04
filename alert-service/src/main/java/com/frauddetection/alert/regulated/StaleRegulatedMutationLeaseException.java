package com.frauddetection.alert.regulated;

public class StaleRegulatedMutationLeaseException extends RuntimeException {

    private final String commandId;
    private final StaleRegulatedMutationLeaseReason reason;

    public StaleRegulatedMutationLeaseException(String commandId, StaleRegulatedMutationLeaseReason reason) {
        super("Regulated mutation fenced transition rejected: reason=" + reason);
        this.commandId = commandId;
        this.reason = reason == null ? StaleRegulatedMutationLeaseReason.UNKNOWN : reason;
    }

    public String commandId() {
        return commandId;
    }

    public StaleRegulatedMutationLeaseReason reason() {
        return reason;
    }
}
