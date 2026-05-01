package com.frauddetection.alert.regulated;

public record RecoveryValidationResult(
        boolean valid,
        String reasonCode
) {
    public static RecoveryValidationResult accepted() {
        return new RecoveryValidationResult(true, null);
    }

    public static RecoveryValidationResult recoveryRequired(String reasonCode) {
        return new RecoveryValidationResult(false, reasonCode);
    }
}
