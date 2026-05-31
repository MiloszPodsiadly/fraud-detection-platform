package com.frauddetection.scoring.orchestration.aggregation;

public enum FraudEngineAgreementStatus {
    AGREEMENT,
    ADJACENT_RISK_VARIANCE,
    DISAGREEMENT,
    PARTIAL,
    INSUFFICIENT_DATA,
    REQUIRED_ENGINE_NOT_COMPARABLE
}
