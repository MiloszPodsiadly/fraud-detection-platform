from __future__ import annotations

import json
from pathlib import Path
from typing import Any

from app.models.logistic_model import LogisticFraudModel
from app.models.xgboost_model import XGBoostFraudModel


class ModelConfigurationError(RuntimeError):
    """Raised when a model artifact cannot be loaded safely."""


def load_model_from_artifact(artifact_path: Path) -> LogisticFraudModel | XGBoostFraudModel:
    """Load the model implementation declared by artifact metadata."""
    artifact = _read_artifact(artifact_path)
    model_type = str(artifact.get("modelType", "logistic")).lower()
    if model_type == "logistic":
        return LogisticFraudModel.load(artifact_path)
    if model_type == "xgboost":
        return XGBoostFraudModel.load(artifact_path)
    raise ModelConfigurationError(
        f"Unsupported modelType '{model_type}' in artifact {artifact_path}. "
        "Supported values: logistic, xgboost."
    )


def model_type_from_artifact(artifact_path: Path) -> str:
    """Read modelType from an artifact, defaulting legacy artifacts to logistic."""
    return str(_read_artifact(artifact_path).get("modelType", "logistic")).lower()


def _read_artifact(artifact_path: Path) -> dict[str, Any]:
    if not artifact_path.exists():
        return {}
    try:
        artifact = json.loads(artifact_path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as exc:
        raise ModelConfigurationError(f"Invalid model artifact JSON: {artifact_path}") from exc
    if not isinstance(artifact, dict):
        raise ModelConfigurationError(f"Model artifact must be a JSON object: {artifact_path}")
    return artifact
