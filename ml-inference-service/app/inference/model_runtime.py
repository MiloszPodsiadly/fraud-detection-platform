from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from app.features.feature_pipeline import FeaturePipeline
from app.models.logistic_model import LogisticFraudModel
from app.models.model_loader import load_model_from_artifact
from app.registry.model_registry import ModelRegistry, default_registry_path


REASON_CODE_BY_FEATURE = {
    "recentTransactionCount": "RECENT_TRANSACTION_SPIKE",
    "recentAmountSumPln": "RECENT_AMOUNT_ACCUMULATION",
    "transactionVelocityPerMinute": "TRANSACTION_VELOCITY",
    "merchantFrequency7d": "MERCHANT_CONCENTRATION",
    "deviceNovelty": "DEVICE_NOVELTY",
    "countryMismatch": "COUNTRY_MISMATCH",
    "proxyOrVpnDetected": "PROXY_OR_VPN",
    "suspiciousFactRatio": "MODEL_HIGH_RISK",
    "rapidTransferBurst": "RAPID_PLN_20K_BURST",
}


@dataclass(frozen=True)
class FeatureContribution:
    """Feature-level contribution used for reason-code generation."""

    reason_code: str
    value: float
    weight: float

    @property
    def contribution(self) -> float:
        return self.value * self.weight


class FraudModelRuntime:
    """Compatibility runtime for the fraud scoring HTTP API."""

    def __init__(
            self,
            artifact_path: Path,
            feature_pipeline: FeaturePipeline | None = None,
            model: LogisticFraudModel | None = None,
            registry: ModelRegistry | None = None,
            model_version: str | None = None,
            registry_role: str = "champion",
    ) -> None:
        self.feature_pipeline = feature_pipeline or FeaturePipeline()
        artifact = self._resolve_artifact_path(
            artifact_path=artifact_path,
            registry=registry or ModelRegistry(default_registry_path()),
            model_version=model_version,
            registry_role=registry_role,
        )
        self.model = model or load_model_from_artifact(artifact)

    def _resolve_artifact_path(
            self,
            artifact_path: Path,
            registry: ModelRegistry,
            model_version: str | None,
            registry_role: str,
    ) -> Path:
        if model_version:
            entry = registry.by_version(model_version)
            if entry:
                return Path(entry.artifact_path)
        entry = registry.champion() if registry_role == "champion" else registry.challenger()
        if entry:
            return Path(entry.artifact_path)
        latest = registry.latest()
        if latest:
            return Path(latest.artifact_path)
        return artifact_path

    @property
    def model_name(self) -> str:
        """Name of the loaded model."""
        return self.model.model_name

    @property
    def model_version(self) -> str:
        """Version of the loaded model."""
        return self.model.model_version

    @property
    def model_family(self) -> str:
        """Family of the loaded model."""
        return self.model.model_family

    def score(self, features: dict[str, Any]) -> dict[str, Any]:
        """Score a fraud feature payload without changing the public response contract."""
        compatibility = self.feature_pipeline.validate_production_snapshot(features)
        if not compatibility["compatible"]:
            return self._incompatible_features_response(compatibility)
        training_mode = getattr(self.model, "training_mode", "production")
        normalized = self.feature_pipeline.transform_single(features, mode=training_mode)
        weights = getattr(self.model, "weights", {}) or getattr(self.model, "feature_importance")()
        contributions = [
            FeatureContribution(name, normalized[name], weight)
            for name, weight in weights.items()
            if name in normalized and normalized[name] > 0 and weight != 0
        ]
        logit = getattr(self.model, "bias", 0.0) + sum(item.contribution for item in contributions)
        fraud_score = round(self.model.predict_proba(normalized), 4)
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
                "bias": getattr(self.model, "bias", 0.0),
                "logit": round(logit, 4),
                "normalizedFeatures": normalized,
                "featureCompatibility": compatibility,
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

    def _incompatible_features_response(self, compatibility: dict[str, Any]) -> dict[str, Any]:
        return {
            "available": False,
            "fraudScore": None,
            "riskLevel": None,
            "modelName": self.model_name,
            "modelVersion": self.model_version,
            "inferenceTimestamp": datetime.now(timezone.utc).isoformat(),
            "reasonCodes": [],
            "scoreDetails": {
                "modelFamily": self.model_family,
                "featureCompatibility": compatibility,
                "normalizedFeatures": {},
                "featureContributions": {},
            },
            "explanationMetadata": {
                "engineType": "PYTHON_ML",
                "explanationType": "MODEL_FEATURE_CONTRIBUTIONS",
                "modelAvailable": False,
                "modelName": self.model_name,
                "modelVersion": self.model_version,
            },
            "fallbackReason": "INCOMPATIBLE_FEATURE_SNAPSHOT",
        }

    def compare_with(self, other: FraudModelRuntime, features: dict[str, Any]) -> dict[str, Any]:
        """Compare this model with another ML runtime on the same feature payload."""
        model_a = self.score(features)
        model_b = other.score(features)
        threshold_a = getattr(self.model, "thresholds", {})
        threshold_b = getattr(other.model, "thresholds", {})
        score_delta = _score_delta(model_a, model_b)
        risk_level_mismatch = _risk_level_mismatch(model_a, model_b)
        decision_disagreement = _decision_disagreement(model_a, model_b)
        return {
            "mode": "ML_COMPARE",
            "modelA": _model_summary(model_a),
            "modelB": _model_summary(model_b),
            "scoreDelta": score_delta,
            "absoluteScoreDelta": round(abs(score_delta), 6) if score_delta is not None else None,
            "riskLevelMismatch": risk_level_mismatch,
            "decisionDisagreement": decision_disagreement,
            "thresholdDifferences": {
                name: round(float(threshold_a.get(name, 0.0)) - float(threshold_b.get(name, 0.0)), 6)
                for name in sorted(set(threshold_a) | set(threshold_b))
            },
            "comparisonMetricsByVersion": {
                str(model_a["modelVersion"]): _model_summary(model_a),
                str(model_b["modelVersion"]): _model_summary(model_b),
            },
        }

    def _reason_codes(self, contributions: list[FeatureContribution]) -> list[str]:
        sorted_contributions = sorted(
            [item for item in contributions if item.contribution > 0],
            key=lambda item: item.contribution,
            reverse=True,
        )
        codes: list[str] = []
        for item in sorted_contributions:
            code = REASON_CODE_BY_FEATURE.get(item.reason_code)
            if code and code not in codes:
                codes.append(code)
            if len(codes) == 5:
                break
        return codes

    def _risk_level(self, fraud_score: float) -> str:
        if fraud_score >= self.model.thresholds["critical"]:
            return "CRITICAL"
        if fraud_score >= self.model.thresholds["high"]:
            return "HIGH"
        if fraud_score >= self.model.thresholds["medium"]:
            return "MEDIUM"
        return "LOW"

    def _alert(self, risk_level: str) -> bool:
        return risk_level in {"HIGH", "CRITICAL"}


def _model_summary(result: dict[str, Any]) -> dict[str, Any]:
    return {
        "available": result["available"],
        "modelName": result["modelName"],
        "modelVersion": result["modelVersion"],
        "fraudScore": result["fraudScore"],
        "riskLevel": result["riskLevel"],
        "fallbackReason": result["fallbackReason"],
    }


def _score_delta(model_a: dict[str, Any], model_b: dict[str, Any]) -> float | None:
    if model_a["fraudScore"] is None or model_b["fraudScore"] is None:
        return None
    return round(float(model_a["fraudScore"]) - float(model_b["fraudScore"]), 6)


def _risk_level_mismatch(model_a: dict[str, Any], model_b: dict[str, Any]) -> bool | None:
    if model_a["riskLevel"] is None or model_b["riskLevel"] is None:
        return None
    return model_a["riskLevel"] != model_b["riskLevel"]


def _decision_disagreement(model_a: dict[str, Any], model_b: dict[str, Any]) -> bool | None:
    if model_a["riskLevel"] is None or model_b["riskLevel"] is None:
        return None
    return _alert_from_risk_level(model_a["riskLevel"]) != _alert_from_risk_level(model_b["riskLevel"])


def _alert_from_risk_level(risk_level: str) -> bool:
    return risk_level in {"HIGH", "CRITICAL"}
