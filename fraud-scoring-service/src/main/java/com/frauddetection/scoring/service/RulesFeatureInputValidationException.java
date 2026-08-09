package com.frauddetection.scoring.service;

public final class RulesFeatureInputValidationException extends RuntimeException {
    public RulesFeatureInputValidationException() {
        super("RULES_FEATURE_INPUT_INVALID");
    }
}
