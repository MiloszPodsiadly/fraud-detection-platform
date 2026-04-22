from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from app.features.feature_pipeline import FeaturePipeline
from app.models.logistic_model import LogisticFraudModel
from app.models.model_loader import load_model_from_artifact
from app.registry.model_registry import ModelRegistry, default_registry_path


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
        normalized = self.feature_pipeline.transform_single(features)
        contributions = [
            FeatureContribution(name, normalized[name], weight)
            for name, weight in self.model.weights.items()
            if normalized[name] > 0 and weight != 0
        ]
        logit = self.model.bias + sum(item.contribution for item in contributions)
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
                "bias": self.model.bias,
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

    def _reason_codes(self, contributions: list[FeatureContribution]) -> list[str]:
        sorted_contributions = sorted(contributions, key=lambda item: item.contribution, reverse=True)
        return [item.reason_code for item in sorted_contributions[:5]]

    def _risk_level(self, fraud_score: float) -> str:
        if fraud_score >= self.model.thresholds["critical"]:
            return "CRITICAL"
        if fraud_score >= self.model.thresholds["high"]:
            return "HIGH"
        if fraud_score >= self.model.thresholds["medium"]:
            return "MEDIUM"
        return "LOW"
