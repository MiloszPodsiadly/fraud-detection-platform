from __future__ import annotations

import json
from math import exp, isfinite
from pathlib import Path
from typing import Any

from app.features.feature_contract import FEATURE_CONTRACT
from app.features.feature_pipeline import FeaturePipeline


class LogisticFraudModel:
    """Logistic regression model backed by a JSON artifact."""

    DEFAULT_WEIGHTS = {
        "recentTransactionCount": 0.35,
        "recentAmountSumPln": 0.45,
        "transactionVelocityPerMinute": 0.80,
        "merchantFrequency7d": 0.16,
        "deviceNovelty": 1.10,
        "countryMismatch": 1.30,
        "proxyOrVpnDetected": 0.95,
        "suspiciousFactRatio": 0.42,
        "rapidTransferBurst": 5.25,
    }
    DEFAULT_THRESHOLDS = {
        "medium": 0.45,
        "high": 0.75,
        "critical": 0.90,
    }
    DEFAULT_BIAS = -2.25

    def __init__(self, artifact: dict[str, Any] | None = None) -> None:
        if artifact is None:
            self.model_name = "python-logistic-fraud-model"
            self.model_version = "unversioned"
            self.model_family = "LOGISTIC_REGRESSION"
            self.feature_contract_version = FEATURE_CONTRACT.version
            self.weights = dict(self.DEFAULT_WEIGHTS)
            self.feature_schema = list(self.weights)
            self.training_mode = "production"
            self.thresholds = dict(self.DEFAULT_THRESHOLDS)
            self.bias = self.DEFAULT_BIAS
            return

        self.model_name = artifact["modelName"]
        self.model_version = artifact["modelVersion"]
        self.model_family = artifact["modelFamily"]
        self.feature_contract_version = artifact["featureContractVersion"]
        self.feature_schema = list(artifact["featureSchema"])
        self.training_mode = artifact["trainingMode"]
        self.weights = {
            name: self._persisted_number(value, f"weight {name!r}")
            for name, value in artifact["weights"].items()
        }
        self.thresholds = {
            name: self._persisted_number(artifact["thresholds"][name], f"threshold {name!r}")
            for name in ("medium", "high", "critical")
        }
        self.bias = self._persisted_number(artifact["bias"], "bias")

    def predict_proba(self, features: dict[str, float]) -> float:
        """Predict the fraud probability for normalized features."""
        return 1.0 / (1.0 + exp(-self.logit(features)))

    def fit(self, X: list[dict[str, float]], y: list[int], epochs: int = 1100, learning_rate: float = 0.9) -> None:
        """Fit logistic weights with batch gradient descent."""
        feature_names = list(X[0].keys()) if X else self.feature_names()
        weights = {name: 0.0 for name in feature_names}
        bias = 0.0

        for _ in range(epochs):
            gradients = {name: 0.0 for name in feature_names}
            bias_gradient = 0.0

            for features, label in zip(X, y):
                prediction = 1.0 / (1.0 + exp(-(bias + sum(weights[name] * features[name] for name in weights))))
                error = prediction - label
                bias_gradient += error
                for name in weights:
                    gradients[name] += error * features[name]

            scale = learning_rate / len(X)
            bias -= scale * bias_gradient
            for name in weights:
                weights[name] -= scale * gradients[name]

        self.bias = round(bias, 6)
        self.weights = {name: round(weight, 6) for name, weight in weights.items()}
        self.feature_schema = list(weights)

    def save(self, path: Path, metadata: dict[str, object] | None = None) -> None:
        """Persist a logistic model artifact."""
        metadata = metadata or {}
        evaluation = metadata.get("evaluation") if isinstance(metadata.get("evaluation"), dict) else {}
        training_metadata = {key: value for key, value in metadata.items() if key != "evaluation"}
        runtime_features = self.runtime_feature_names()
        artifact = {
            "modelName": self.model_name,
            "modelVersion": self.model_version,
            "modelType": "logistic",
            "modelFamily": self.model_family,
            "trainingMode": self.training_mode,
            "featureSetUsed": runtime_features,
            "bias": self.bias,
            "weights": self.weights,
            "thresholds": self.thresholds,
            "thresholdPolicy": self._threshold_policy(),
            "modelRuntimeReadiness": self._model_runtime_readiness(evaluation),
            "featureSchema": runtime_features,
            "featureContractVersion": FEATURE_CONTRACT.version,
            "featureSchemaVersion": FEATURE_CONTRACT.version,
            "featureSetVersion": FEATURE_CONTRACT.version,
            "featureImportance": self.feature_importance(),
            "training": {
                **training_metadata,
                "trainingMode": self.training_mode,
                "featureSetUsed": runtime_features,
                "featureContractVersion": FEATURE_CONTRACT.version,
                "featureSetVersion": FEATURE_CONTRACT.version,
            },
            "evaluation": evaluation,
        }
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(artifact, indent=2, sort_keys=True) + "\n", encoding="utf-8")

    def feature_importance(self) -> dict[str, float]:
        """Use absolute logistic weights as feature importance."""
        return {name: abs(weight) for name, weight in self.weights.items()}

    def logit(self, features: dict[str, float]) -> float:
        """Calculate the raw logistic model score before sigmoid."""
        return self.bias + sum(self.weights[name] * features[name] for name in self.weights)

    def feature_contributions(self, features: dict[str, float]) -> dict[str, float]:
        """Return non-zero feature contributions to the model score."""
        return {
            name: features[name] * weight
            for name, weight in self.weights.items()
            if features[name] > 0
        }

    @classmethod
    def load(cls, artifact_path: Path) -> LogisticFraudModel:
        """Load a logistic model from a JSON artifact."""
        from app.models.model_loader import load_model_from_artifact

        model = load_model_from_artifact(artifact_path)
        if not isinstance(model, cls):
            raise ValueError("Artifact does not declare a logistic model.")
        return model

    @staticmethod
    def feature_names() -> list[str]:
        """Return the ordered feature schema used by the model."""
        return list(FeaturePipeline.FEATURE_NAMES)

    def runtime_feature_names(self) -> list[str]:
        """Return the artifact feature schema used at inference."""
        return list(self.feature_schema)

    def _threshold_policy(self) -> dict[str, object]:
        return {
            "policyVersion": "fixed-business-risk-thresholds-v1",
            "ownership": "fixed_business_risk_thresholds",
            "runtimeSemantics": "riskLevel bands are business-owned; alertRecommended is true for HIGH or CRITICAL",
            "deployedAlertThresholdName": "high",
            "deployedAlertThreshold": self.thresholds["high"],
            "thresholds": dict(self.thresholds),
        }

    def _model_runtime_readiness(self, evaluation: dict[str, object]) -> dict[str, object]:
        readiness = evaluation.get("modelRuntimeReadiness")
        if isinstance(readiness, dict):
            return readiness
        return {
            "status": "UNKNOWN",
            "reasons": ["MODEL_RUNTIME_READINESS_NOT_EVALUATED"],
            "policyVersion": "fixed-business-risk-thresholds-v1",
            "deployedAlertThresholdName": "high",
            "deployedAlertThreshold": self.thresholds["high"],
            "rankingMetricsAreNotSufficient": True,
        }

    def _persisted_number(self, value: Any, label: str) -> float:
        if isinstance(value, bool) or not isinstance(value, (int, float)) or not isfinite(value):
            raise ValueError(f"Persisted logistic {label} must be a finite number.")
        return float(value)
