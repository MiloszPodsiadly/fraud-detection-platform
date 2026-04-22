from __future__ import annotations

import importlib.util
from pathlib import Path


class XGBoostFraudModel:
    """Optional XGBoost model adapter.

    The repository does not currently ship an XGBoost dependency, so selecting
    this model type gives a clear configuration error instead of silently
    falling back to logistic regression.
    """

    model_name = "python-xgboost-fraud-model"
    model_version = "untrained"
    model_family = "XGBOOST"
    weights: dict[str, float] = {}

    def __init__(self) -> None:
        if importlib.util.find_spec("xgboost") is None:
            raise RuntimeError("model_type=xgboost requires the optional 'xgboost' Python package.")

    def fit(self, X: list[dict[str, float]], y: list[int]) -> None:
        """Fit the XGBoost model."""
        raise NotImplementedError("XGBoost training is unavailable until the dependency is installed.")

    def predict_proba(self, features: dict[str, float]) -> float:
        """Return fraud probability for one transformed feature row."""
        raise NotImplementedError("XGBoost inference is unavailable until the dependency is installed.")

    def save(self, path: Path, metadata: dict[str, object] | None = None) -> None:
        """Persist the XGBoost artifact."""
        raise NotImplementedError("XGBoost persistence is unavailable until the dependency is installed.")

    @classmethod
    def load(cls, artifact_path: Path) -> XGBoostFraudModel:
        """Load an XGBoost model artifact."""
        return cls()

    def feature_importance(self) -> dict[str, float]:
        """Return XGBoost feature importances."""
        return {}
