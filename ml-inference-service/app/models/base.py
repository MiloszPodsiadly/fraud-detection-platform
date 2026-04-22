from __future__ import annotations

from pathlib import Path
from typing import Protocol


class FraudDetectionModel(Protocol):
    """Interface implemented by fraud model algorithms."""

    model_name: str
    model_version: str
    model_family: str
    weights: dict[str, float]

    def fit(self, X: list[dict[str, float]], y: list[int]) -> None:
        """Fit the model from transformed features and labels."""

    def predict_proba(self, features: dict[str, float]) -> float:
        """Return fraud probability for one transformed feature row."""

    def save(self, path: Path, metadata: dict[str, object] | None = None) -> None:
        """Persist the model artifact."""

    @classmethod
    def load(cls, artifact_path: Path) -> FraudDetectionModel:
        """Load a model artifact."""

    def feature_importance(self) -> dict[str, float]:
        """Return feature importance values by feature name."""
