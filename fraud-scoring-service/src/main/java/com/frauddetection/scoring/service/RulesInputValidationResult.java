package com.frauddetection.scoring.service;

import java.util.Objects;

public record RulesInputValidationResult(RulesInputValidationStatus status) {
    public RulesInputValidationResult {
        Objects.requireNonNull(status, "status is required");
    }

    public static RulesInputValidationResult ok() {
        return new RulesInputValidationResult(RulesInputValidationStatus.VALID);
    }

    public static RulesInputValidationResult invalid(RulesInputValidationStatus status) {
        if (status == RulesInputValidationStatus.VALID) {
            throw new IllegalArgumentException("invalid status must not be VALID");
        }
        return new RulesInputValidationResult(status);
    }

    public boolean valid() {
        return status == RulesInputValidationStatus.VALID;
    }

    public void requireNoAdapterDefect() {
        if (status == RulesInputValidationStatus.ADAPTER_ACCESSOR_DEFECT) {
            throw new IllegalStateException("adapter feature accessor mismatch");
        }
        if (status == RulesInputValidationStatus.ACCESS_POLICY_DEFECT) {
            throw new IllegalStateException("adapter feature access policy violation");
        }
    }
}
