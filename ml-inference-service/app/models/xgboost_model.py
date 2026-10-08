from __future__ import annotations

import base64
import json
import importlib.util
import math
import tempfile
from pathlib import Path
from typing import Any

from app.features.feature_contract import FEATURE_CONTRACT
from app.features.feature_pipeline import FeaturePipeline


class XGBoostFraudModel:
    """Optional XGBoost model adapter."""

    model_name = "python-xgboost-fraud-model"
    model_version = "untrained"
    model_family = "XGBOOST"
    feature_contract_version = FEATURE_CONTRACT.version
    training_mode = "production"
    weights: dict[str, float] = {}
    thresholds = {"medium": 0.45, "high": 0.75, "critical": 0.90}
    bias = 0.0

    def __init__(self, artifact: dict[str, Any] | None = None) -> None:
        if importlib.util.find_spec("xgboost") is None:
            raise RuntimeError("model_type=xgboost requires the optional 'xgboost' Python package.")
        import xgboost as xgb

        self._xgb = xgb
        self.booster = None
        if artifact is None:
            self.model_name = "python-xgboost-fraud-model"
            self.model_version = "untrained"
            self.model_family = "XGBOOST"
            self.feature_contract_version = FEATURE_CONTRACT.version
            self.training_mode = "production"
            self.feature_schema = list(FeaturePipeline.PRODUCTION_FEATURE_NAMES)
            self.thresholds = {"medium": 0.45, "high": 0.75, "critical": 0.90}
            self.weights = {}
            self.bias = 0.0
            return

        self.model_name = artifact["modelName"]
        self.model_version = artifact["modelVersion"]
        self.model_family = artifact["modelFamily"]
        self.feature_contract_version = artifact["featureContractVersion"]
        self.training_mode = artifact["trainingMode"]
        self.feature_schema = list(artifact["featureSchema"])
        self.thresholds = {
            name: self._persisted_number(artifact["thresholds"][name], f"threshold {name!r}")
            for name in ("medium", "high", "critical")
        }
        model_data = artifact["modelDataBase64"]
        try:
            payload = base64.b64decode(model_data, validate=True)
        except (TypeError, ValueError) as exception:
            raise ValueError("Persisted XGBoost modelDataBase64 is invalid.") from exception
        self.booster = xgb.Booster()
        self._load_booster_from_bytes(payload)

    def fit(self, X: list[dict[str, float]], y: list[int]) -> None:
        """Fit the XGBoost model."""
        if not X:
            raise ValueError("XGBoost training requires at least one row.")
        self.feature_schema = list(X[0])
        matrix = self._matrix(X, label=y)
        params = {
            "objective": "binary:logistic",
            "eval_metric": "aucpr",
            "max_depth": 3,
            "eta": 0.12,
            "subsample": 0.9,
            "colsample_bytree": 0.9,
            "seed": 7341,
        }
        self.booster = self._xgb.train(params, matrix, num_boost_round=40)

    def predict_proba(self, features: dict[str, float]) -> float:
        """Return fraud probability for one transformed feature row."""
        if self.booster is None:
            raise RuntimeError("XGBoost model is not fitted or loaded.")
        prediction = self.booster.predict(self._matrix([features]))
        return float(prediction[0])

    def save(self, path: Path, metadata: dict[str, object] | None = None) -> None:
        """Persist the XGBoost artifact."""
        if self.booster is None:
            raise RuntimeError("XGBoost model is not fitted.")
        metadata = metadata or {}
        evaluation = metadata.get("evaluation") if isinstance(metadata.get("evaluation"), dict) else {}
        training_metadata = {key: value for key, value in metadata.items() if key != "evaluation"}
        artifact = {
            "modelName": self.model_name,
            "modelVersion": self.model_version,
            "modelType": "xgboost",
            "modelFamily": self.model_family,
            "trainingMode": self.training_mode,
            "featureSetUsed": self.feature_schema,
            "featureSchema": self.feature_schema,
            "featureContractVersion": FEATURE_CONTRACT.version,
            "featureSchemaVersion": FEATURE_CONTRACT.version,
            "featureSetVersion": FEATURE_CONTRACT.version,
            "thresholds": self.thresholds,
            "thresholdPolicy": self._threshold_policy(),
            "modelRuntimeReadiness": self._model_runtime_readiness(evaluation),
            "featureImportance": self.feature_importance(),
            "training": {
                **training_metadata,
                "trainingMode": self.training_mode,
                "featureSetUsed": self.feature_schema,
                "featureContractVersion": FEATURE_CONTRACT.version,
                "featureSetVersion": FEATURE_CONTRACT.version,
            },
            "evaluation": evaluation,
            "modelDataBase64": base64.b64encode(self._booster_bytes()).decode("ascii"),
        }
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(artifact, indent=2, sort_keys=True) + "\n", encoding="utf-8")

    @classmethod
    def load(cls, artifact_path: Path) -> XGBoostFraudModel:
        """Load an XGBoost model artifact."""
        from app.models.model_loader import load_model_from_artifact

        model = load_model_from_artifact(artifact_path)
        if not isinstance(model, cls):
            raise ValueError("Artifact does not declare an XGBoost model.")
        return model

    def feature_importance(self) -> dict[str, float]:
        """Return XGBoost feature importances."""
        if self.booster is None:
            return {name: 0.0 for name in self.feature_schema}
        scores = self.booster.get_score(importance_type="gain")
        return {name: float(scores.get(name, 0.0)) for name in self.feature_schema}

    def runtime_feature_names(self) -> list[str]:
        """Return the artifact feature schema used at inference."""
        return list(self.feature_schema)

    def _matrix(self, rows: list[dict[str, float]], label: list[int] | None = None):
        values = [[row[name] for name in self.feature_schema] for row in rows]
        return self._xgb.DMatrix(values, label=label, feature_names=self.feature_schema)

    def _load_booster_from_bytes(self, payload: bytes) -> None:
        with tempfile.NamedTemporaryFile(delete=False) as handle:
            handle.write(payload)
            temp_path = Path(handle.name)
        try:
            self.booster.load_model(str(temp_path))
        finally:
            temp_path.unlink(missing_ok=True)

    def _booster_bytes(self) -> bytes:
        with tempfile.NamedTemporaryFile(delete=False) as handle:
            temp_path = Path(handle.name)
        try:
            self.booster.save_model(str(temp_path))
            return temp_path.read_bytes()
        finally:
            temp_path.unlink(missing_ok=True)

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
        if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value):
            raise ValueError(f"Persisted XGBoost {label} must be a finite number.")
        return float(value)
