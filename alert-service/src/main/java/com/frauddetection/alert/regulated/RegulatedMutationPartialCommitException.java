package com.frauddetection.alert.regulated;

public class RegulatedMutationPartialCommitException extends RuntimeException {

    private final RegulatedMutationResponseSnapshot responseSnapshot;
    private final String reasonCode;

    public RegulatedMutationPartialCommitException(
            String reasonCode,
            RegulatedMutationResponseSnapshot responseSnapshot,
            Throwable cause
    ) {
        super(reasonCode, cause);
        this.reasonCode = reasonCode;
        this.responseSnapshot = responseSnapshot;
    }

    public RegulatedMutationResponseSnapshot responseSnapshot() {
        return responseSnapshot;
    }

    public String reasonCode() {
        return reasonCode;
    }
}
