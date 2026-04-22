from __future__ import annotations

import base64
import json
import importlib.util
import tempfile
from pathlib import Path
from typing import Any

from app.features.feature_pipeline import FeaturePipeline


class XGBoostFraudModel:
    """Optional XGBoost model adapter."""

    model_name = "python-xgboost-fraud-model"
    model_version = "untrained"
    model_family = "XGBOOST"
    training_mode = "production"
    weights: dict[str, float] = {}
    thresholds = {"medium": 0.45, "high": 0.75, "critical": 0.90}
    bias = 0.0

    def __init__(self, artifact: dict[str, Any] | None = None) -> None:
        if importlib.util.find_spec("xgboost") is None:
            raise RuntimeError("model_type=xgboost requires the optional 'xgboost' Python package.")
        import xgboost as xgb

        artifact = artifact or {}
        self._xgb = xgb
        self.model_name = str(artifact.get("modelName", self.model_name))
        self.model_version = str(artifact.get("modelVersion", self.model_version))
        self.model_family = str(artifact.get("modelFamily", self.model_family))
        self.training_mode = self._training_mode(artifact)
        self.feature_schema = self._feature_schema(artifact.get("featureSchema"))
        self.thresholds = artifact.get("thresholds") if isinstance(artifact.get("thresholds"), dict) else dict(self.thresholds)
        self.booster = None
        model_data = artifact.get("modelDataBase64")
        if isinstance(model_data, str):
            self.booster = xgb.Booster()
            self._load_booster_from_bytes(base64.b64decode(model_data))

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
        artifact = {
            "modelName": self.model_name,
            "modelVersion": self.model_version,
            "modelType": "xgboost",
            "modelFamily": self.model_family,
            "trainingMode": self.training_mode,
            "featureSetUsed": self.feature_schema,
            "featureSchema": self.feature_schema,
            "thresholds": self.thresholds,
            "featureImportance": self.feature_importance(),
            "training": {
                **(metadata or {}),
                "trainingMode": self.training_mode,
                "featureSetUsed": self.feature_schema,
            },
            "modelDataBase64": base64.b64encode(self.booster.save_raw()).decode("ascii"),
        }
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(artifact, indent=2, sort_keys=True) + "\n", encoding="utf-8")

    @classmethod
    def load(cls, artifact_path: Path) -> XGBoostFraudModel:
        """Load an XGBoost model artifact."""
        if not artifact_path.exists():
            return cls()
        with artifact_path.open("r", encoding="utf-8") as artifact_file:
            artifact = json.load(artifact_file)
        return cls(artifact if isinstance(artifact, dict) else {})

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

    def _feature_schema(self, value: Any) -> list[str]:
        if isinstance(value, list) and all(isinstance(name, str) for name in value):
            return list(value)
        return list(FeaturePipeline.PRODUCTION_FEATURE_NAMES)

    def _training_mode(self, artifact: dict[str, Any]) -> str:
        value = artifact.get("trainingMode")
        training = artifact.get("training")
        if value is None and isinstance(training, dict):
            value = training.get("trainingMode")
        return str(value or "production")
