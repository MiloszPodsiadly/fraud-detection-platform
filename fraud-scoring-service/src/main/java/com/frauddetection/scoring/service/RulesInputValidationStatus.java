package com.frauddetection.scoring.service;

public enum RulesInputValidationStatus {
    VALID,
    INVALID_TYPE,
    INVALID_WINDOW,
    OUT_OF_BOUNDS,
    INCONSISTENT_FACTS,
    INCOMPLETE_FACT_PAIR,
    UNSUPPORTED_CURRENCY_BASIS,
    ADAPTER_ACCESSOR_DEFECT,
    ACCESS_POLICY_DEFECT
}
