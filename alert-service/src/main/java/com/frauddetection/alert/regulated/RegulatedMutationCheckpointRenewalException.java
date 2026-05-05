package com.frauddetection.alert.regulated;

public class RegulatedMutationCheckpointRenewalException extends RuntimeException {

    private final RegulatedMutationRenewalCheckpoint checkpoint;
    private final RegulatedMutationLeaseRenewalReason reason;

    public RegulatedMutationCheckpointRenewalException(
            RegulatedMutationRenewalCheckpoint checkpoint,
            RegulatedMutationLeaseRenewalReason reason
    ) {
        super("Regulated mutation checkpoint renewal rejected: checkpoint="
                + checkpoint
                + ", reason="
                + (reason == null ? RegulatedMutationLeaseRenewalReason.UNKNOWN : reason));
        this.checkpoint = checkpoint;
        this.reason = reason == null ? RegulatedMutationLeaseRenewalReason.UNKNOWN : reason;
    }

    public RegulatedMutationRenewalCheckpoint checkpoint() {
        return checkpoint;
    }

    public RegulatedMutationLeaseRenewalReason reason() {
        return reason;
    }
}
