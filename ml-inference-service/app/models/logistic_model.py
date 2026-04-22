from __future__ import annotations

import json
from math import exp
from pathlib import Path
from typing import Any

from app.features.feature_pipeline import FeaturePipeline


class LogisticFraudModel:
    """Logistic regression model backed by a JSON artifact."""

    DEFAULT_WEIGHTS = {
        "recentTransactionCount": 0.35,
        "recentAmountSum": 0.45,
        "transactionVelocityPerMinute": 0.80,
        "transactionVelocityPerHour": 0.0,
        "transactionVelocityPerDay": 0.0,
        "recentAmountAverage": 0.0,
        "recentAmountStdDev": 0.0,
        "amountDeviationFromUserMean": 0.0,
        "merchantEntropy": 0.0,
        "countryEntropy": 0.0,
        "merchantFrequency7d": 0.16,
        "deviceNovelty": 1.10,
        "countryMismatch": 1.30,
        "proxyOrVpnDetected": 0.95,
        "highRiskFlagCount": 0.42,
        "rapidTransferBurst": 5.25,
    }
    DEFAULT_THRESHOLDS = {
        "medium": 0.45,
        "high": 0.75,
        "critical": 0.90,
    }
    DEFAULT_BIAS = -2.25

    def __init__(self, artifact: dict[str, Any] | None = None) -> None:
        artifact = artifact or {}
        self.model_name = str(artifact.get("modelName", "python-logistic-fraud-model"))
        self.model_version = str(artifact.get("modelVersion", "unversioned"))
        self.model_family = str(artifact.get("modelFamily", "LOGISTIC_REGRESSION"))
        self.weights = self._weights(artifact.get("weights"))
        self.feature_schema = self._feature_schema(artifact.get("featureSchema"), self.weights)
        self.training_mode = self._training_mode(artifact, self.feature_schema)
        self.thresholds = self._thresholds(artifact.get("thresholds"))
        self.bias = self._signed_number(artifact.get("bias"), self.DEFAULT_BIAS)

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
        artifact = {
            "modelName": self.model_name,
            "modelVersion": self.model_version,
            "modelType": "logistic",
            "modelFamily": self.model_family,
            "bias": self.bias,
            "weights": self.weights,
            "thresholds": self.thresholds,
            "featureSchema": self.runtime_feature_names(),
            "featureImportance": self.feature_importance(),
            "training": {
                **(metadata or {}),
                "trainingMode": self.training_mode,
                "featureSetUsed": self.runtime_feature_names(),
            },
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
        if not artifact_path.exists():
            return cls()
        with artifact_path.open("r", encoding="utf-8") as artifact_file:
            artifact = json.load(artifact_file)
        return cls(artifact if isinstance(artifact, dict) else {})

    @staticmethod
    def feature_names() -> list[str]:
        """Return the ordered feature schema used by the model."""
        return list(FeaturePipeline.FEATURE_NAMES)

    def runtime_feature_names(self) -> list[str]:
        """Return the artifact feature schema used at inference."""
        return list(self.feature_schema)

    def _weights(self, value: Any) -> dict[str, float]:
        if not isinstance(value, dict):
            return dict(self.DEFAULT_WEIGHTS)
        return {
            str(name): self._signed_number(weight, 0.0)
            for name, weight in value.items()
        }

    def _feature_schema(self, value: Any, weights: dict[str, float]) -> list[str]:
        if isinstance(value, list) and all(isinstance(name, str) for name in value):
            return list(value)
        return list(weights)

    def _training_mode(self, artifact: dict[str, Any], feature_schema: list[str]) -> str:
        value = artifact.get("trainingMode")
        training = artifact.get("training")
        if value is None and isinstance(training, dict):
            value = training.get("trainingMode")
        if value is not None:
            return str(value)
        production = set(FeaturePipeline.PRODUCTION_FEATURE_NAMES)
        return "production" if set(feature_schema).issubset(production) else "full"

    def _thresholds(self, value: Any) -> dict[str, float]:
        if not isinstance(value, dict):
            return dict(self.DEFAULT_THRESHOLDS)
        thresholds = {
            name: self._number(value.get(name), default_threshold)
            for name, default_threshold in self.DEFAULT_THRESHOLDS.items()
        }
        if not thresholds["medium"] <= thresholds["high"] <= thresholds["critical"]:
            return dict(self.DEFAULT_THRESHOLDS)
        return thresholds

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
