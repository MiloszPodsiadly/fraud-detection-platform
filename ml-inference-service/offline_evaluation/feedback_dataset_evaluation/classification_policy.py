from __future__ import annotations


RISK_CLASSIFICATION_POLICY = "RISK_HIGH_OR_CRITICAL_POSITIVE_V1"
POSITIVE_RISK_LEVELS = frozenset({"HIGH", "CRITICAL"})
NEGATIVE_RISK_LEVELS = frozenset({"LOW", "MEDIUM"})
SUPPORTED_RISK_LEVELS = POSITIVE_RISK_LEVELS | NEGATIVE_RISK_LEVELS


def is_positive_risk(risk_level: str) -> bool:
    if risk_level not in SUPPORTED_RISK_LEVELS:
        raise ValueError("risk level is unsupported")
    return risk_level in POSITIVE_RISK_LEVELS
