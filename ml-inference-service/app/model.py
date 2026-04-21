from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timezone
import json
from math import exp
from pathlib import Path
from typing import Any


DEFAULT_ARTIFACT_PATH = Path(__file__).with_name("model_artifact.json")


@dataclass(frozen=True)
class FeatureContribution:
    reason_code: str
    value: float
    weight: float

    @property
    def contribution(self) -> float:
        return self.value * self.weight


class FraudModel:
    """Versioned logistic model loaded from a repo-owned training artifact."""

    _DEFAULT_WEIGHTS = {
        "recentTransactionCount": 0.35,
        "recentAmountSum": 0.45,
        "transactionVelocityPerMinute": 0.80,
        "merchantFrequency7d": 0.16,
        "deviceNovelty": 1.10,
        "countryMismatch": 1.30,
        "proxyOrVpnDetected": 0.95,
        "highRiskFlagCount": 0.42,
        "rapidTransferBurst": 5.25,
    }
    _DEFAULT_THRESHOLDS = {
        "medium": 0.45,
        "high": 0.75,
        "critical": 0.90,
    }
    _DEFAULT_BIAS = -2.25

    def __init__(self, artifact_path: Path = DEFAULT_ARTIFACT_PATH) -> None:
        artifact = self._load_artifact(artifact_path)
        self.model_name = str(artifact.get("modelName", "python-logistic-fraud-model"))
        self.model_version = str(artifact.get("modelVersion", "unversioned"))
        self.model_family = str(artifact.get("modelFamily", "LOGISTIC_REGRESSION"))
        self.weights = self._weights(artifact.get("weights"))
        self.thresholds = self._thresholds(artifact.get("thresholds"))
        self.bias = self._signed_number(artifact.get("bias"), self._DEFAULT_BIAS)

    def score(self, features: dict[str, Any]) -> dict[str, Any]:
        normalized = self._normalize(features)
        contributions = [
            FeatureContribution(name, normalized[name], weight)
            for name, weight in self.weights.items()
            if normalized[name] > 0
        ]
        logit = self.bias + sum(item.contribution for item in contributions)
        fraud_score = round(1.0 / (1.0 + exp(-logit)), 4)
        risk_level = self._risk_level(fraud_score)

        return {
            "available": True,
            "fraudScore": fraud_score,
            "riskLevel": risk_level,
            "modelName": self.model_name,
            "modelVersion": self.model_version,
            "inferenceTimestamp": datetime.now(timezone.utc).isoformat(),
            "reasonCodes": self._reason_codes(contributions),
            "scoreDetails": {
                "modelFamily": self.model_family,
                "bias": self.bias,
                "logit": round(logit, 4),
                "normalizedFeatures": normalized,
                "featureContributions": {
                    item.reason_code: round(item.contribution, 4)
                    for item in contributions
                },
            },
            "explanationMetadata": {
                "engineType": "PYTHON_ML",
                "explanationType": "MODEL_FEATURE_CONTRIBUTIONS",
                "modelAvailable": True,
                "modelName": self.model_name,
                "modelVersion": self.model_version,
            },
            "fallbackReason": None,
        }

    def _normalize(self, features: dict[str, Any]) -> dict[str, float]:
        feature_flags = features.get("featureFlags") or []
        amount_sum = self._money_amount(features.get("recentAmountSum"))
        return {
            "recentTransactionCount": min(self._number(features.get("recentTransactionCount")) / 10.0, 1.0),
            "recentAmountSum": min(amount_sum / 10000.0, 1.0),
            "transactionVelocityPerMinute": min(self._number(features.get("transactionVelocityPerMinute")) / 5.0, 1.0),
            "merchantFrequency7d": min(self._number(features.get("merchantFrequency7d")) / 12.0, 1.0),
            "deviceNovelty": self._flag(features.get("deviceNovelty")),
            "countryMismatch": self._flag(features.get("countryMismatch")),
            "proxyOrVpnDetected": self._flag(features.get("proxyOrVpnDetected")),
            "highRiskFlagCount": min(len(feature_flags) / 6.0, 1.0) if isinstance(feature_flags, list) else 0.0,
            "rapidTransferBurst": self._rapid_transfer_burst(features, feature_flags),
        }

    def _rapid_transfer_burst(self, features: dict[str, Any], feature_flags: Any) -> float:
        if isinstance(feature_flags, list) and "RAPID_PLN_20K_BURST" in feature_flags:
            return 1.0
        if features.get("rapidTransferFraudCaseCandidate") is True:
            return 1.0
        return 1.0 if self._number(features.get("rapidTransferTotalPln")) >= 20_000.0 else 0.0

    def _reason_codes(self, contributions: list[FeatureContribution]) -> list[str]:
        sorted_contributions = sorted(contributions, key=lambda item: item.contribution, reverse=True)
        return [item.reason_code for item in sorted_contributions[:5]]

    def _risk_level(self, fraud_score: float) -> str:
        if fraud_score >= self.thresholds["critical"]:
            return "CRITICAL"
        if fraud_score >= self.thresholds["high"]:
            return "HIGH"
        if fraud_score >= self.thresholds["medium"]:
            return "MEDIUM"
        return "LOW"

    def _load_artifact(self, artifact_path: Path) -> dict[str, Any]:
        if not artifact_path.exists():
            return {}
        with artifact_path.open("r", encoding="utf-8") as artifact_file:
            artifact = json.load(artifact_file)
        return artifact if isinstance(artifact, dict) else {}

    def _weights(self, value: Any) -> dict[str, float]:
        if not isinstance(value, dict):
            return dict(self._DEFAULT_WEIGHTS)
        return {
            name: self._number(value.get(name), default_weight)
            for name, default_weight in self._DEFAULT_WEIGHTS.items()
        }

    def _thresholds(self, value: Any) -> dict[str, float]:
        if not isinstance(value, dict):
            return dict(self._DEFAULT_THRESHOLDS)
        thresholds = {
            name: self._number(value.get(name), default_threshold)
            for name, default_threshold in self._DEFAULT_THRESHOLDS.items()
        }
        if not thresholds["medium"] <= thresholds["high"] <= thresholds["critical"]:
            return dict(self._DEFAULT_THRESHOLDS)
        return thresholds

    def _money_amount(self, value: Any) -> float:
        if isinstance(value, dict):
            return self._number(value.get("amount"))
        return self._number(value)

    def _number(self, value: Any, default: float = 0.0) -> float:
        if isinstance(value, bool) or value is None:
            return default
        try:
            return max(float(value), 0.0)
        except (TypeError, ValueError):
            return default

    def _signed_number(self, value: Any, default: float) -> float:
        if isinstance(value, bool) or value is None:
            return default
        try:
            return float(value)
        except (TypeError, ValueError):
            return default

    def _flag(self, value: Any) -> float:
        return 1.0 if value is True else 0.0


_DEFAULT_MODEL = FraudModel()
MODEL_NAME = _DEFAULT_MODEL.model_name
MODEL_VERSION = _DEFAULT_MODEL.model_version
