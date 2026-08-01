package com.frauddetection.scoring.engine.velocity;

record VelocityInputValidation(
        VelocityInputReadiness readiness,
        VelocitySignalReasonCode reasonCode,
        ValidatedVelocityInputs validated
) {
    static VelocityInputValidation available(ValidatedVelocityInputs validated) {
        return new VelocityInputValidation(VelocityInputReadiness.READY, null, validated);
    }

    static VelocityInputValidation unavailable(VelocitySignalReasonCode reasonCode) {
        return new VelocityInputValidation(VelocityInputReadiness.UNAVAILABLE, reasonCode, null);
    }

    static VelocityInputValidation degraded(VelocitySignalReasonCode reasonCode) {
        return new VelocityInputValidation(VelocityInputReadiness.DEGRADED, reasonCode, null);
    }
}
