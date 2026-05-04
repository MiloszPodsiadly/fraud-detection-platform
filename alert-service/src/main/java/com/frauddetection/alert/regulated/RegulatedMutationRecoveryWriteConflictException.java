package com.frauddetection.alert.regulated;

public class RegulatedMutationRecoveryWriteConflictException extends RuntimeException {

    private final String commandId;

    public RegulatedMutationRecoveryWriteConflictException(String commandId) {
        super("Regulated mutation recovery transition rejected: reason=RECOVERY_WRITE_CONFLICT");
        this.commandId = commandId;
    }

    public String commandId() {
        return commandId;
    }
}
