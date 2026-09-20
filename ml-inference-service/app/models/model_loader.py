from __future__ import annotations

import json
from pathlib import Path
from typing import Any

from app.features.feature_contract import FEATURE_CONTRACT
from app.features.feature_pipeline import FeaturePipeline
from app.models.logistic_model import LogisticFraudModel
from app.models.xgboost_model import XGBoostFraudModel


class ModelConfigurationError(RuntimeError):
    """Raised when a model artifact cannot be loaded safely."""


def load_model_from_artifact(artifact_path: Path) -> LogisticFraudModel | XGBoostFraudModel:
    """Load the model implementation declared by artifact metadata."""
    artifact = _read_artifact(artifact_path)
    validate_model_artifact(artifact, artifact_path)
    model_type = str(artifact.get("modelType", "logistic")).lower()
    if model_type == "logistic":
        return LogisticFraudModel(artifact)
    if model_type == "xgboost":
        return XGBoostFraudModel(artifact)
    raise ModelConfigurationError(
        f"Unsupported modelType '{model_type}' in artifact {artifact_path}. "
        "Supported values: logistic, xgboost."
    )


def model_type_from_artifact(artifact_path: Path) -> str:
    """Read modelType from an artifact, defaulting missing metadata to logistic."""
    return str(_read_artifact(artifact_path).get("modelType", "logistic")).lower()


def validate_model_artifact(artifact: dict[str, Any], artifact_path: Path | None = None) -> None:
    """Fail fast when an artifact is not aligned with the runtime feature contract."""
    location = f" in artifact {artifact_path}" if artifact_path is not None else ""
    model_type = str(artifact.get("modelType", "logistic")).lower()
    if model_type not in {"logistic", "xgboost"}:
        raise ModelConfigurationError(
            f"Unsupported modelType '{model_type}'{location}. Supported values: logistic, xgboost."
        )
    _require_top_level_fields(
        artifact,
        (
            "modelName",
            "modelVersion",
            "modelType",
            "modelFamily",
            "trainingMode",
            "featureSchema",
            "featureContractVersion",
            "featureSchemaVersion",
            "featureSetVersion",
            "thresholds",
            "thresholdPolicy",
            "productionReadiness",
            "evaluation",
            "training",
        ),
        location,
    )
    training_mode = _training_mode(artifact)
    expected_schema = _expected_schema(training_mode)
    for field in ("featureContractVersion", "featureSchemaVersion", "featureSetVersion"):
        if artifact.get(field) != FEATURE_CONTRACT.version:
            raise ModelConfigurationError(
                f"{field} mismatch{location}: expected {FEATURE_CONTRACT.version}, got {artifact.get(field)!r}."
            )
    schema = artifact.get("featureSchema")
    if schema != expected_schema:
        raise ModelConfigurationError(
            f"featureSchema mismatch{location}: expected {expected_schema}, got {schema!r}."
        )
    feature_set = artifact.get("featureSetUsed")
    if feature_set is not None and feature_set != expected_schema:
        raise ModelConfigurationError(
            f"featureSetUsed mismatch{location}: expected {expected_schema}, got {feature_set!r}."
        )
    training = artifact.get("training")
    if isinstance(training, dict):
        training_feature_set = training.get("featureSetUsed")
        if training_feature_set is not None and training_feature_set != expected_schema:
            raise ModelConfigurationError(
                f"training.featureSetUsed mismatch{location}: expected {expected_schema}, got {training_feature_set!r}."
            )
        for field in ("featureContractVersion", "featureSetVersion"):
            if training.get(field) != FEATURE_CONTRACT.version:
                raise ModelConfigurationError(
                    f"training.{field} mismatch{location}: expected {FEATURE_CONTRACT.version}, got {training.get(field)!r}."
                )
    if model_type == "logistic":
        weights = artifact.get("weights")
        if not isinstance(weights, dict):
            raise ModelConfigurationError(f"logistic artifact weights must be a JSON object{location}.")
        actual_weight_keys = list(weights)
        if set(actual_weight_keys) != set(expected_schema) or len(actual_weight_keys) != len(expected_schema):
            raise ModelConfigurationError(
                f"logistic weights schema mismatch{location}: expected keys {expected_schema}, got {actual_weight_keys!r}."
            )
    _require_ready_production_readiness(artifact.get("productionReadiness"), location)


def _require_top_level_fields(artifact: dict[str, Any], fields: tuple[str, ...], location: str) -> None:
    missing = [field for field in fields if field not in artifact]
    if missing:
        raise ModelConfigurationError(f"model artifact missing required fields{location}: {missing}.")
    object_fields = ("thresholds", "thresholdPolicy", "evaluation", "training")
    malformed = [field for field in object_fields if not isinstance(artifact.get(field), dict)]
    if malformed:
        raise ModelConfigurationError(f"model artifact required object fields are malformed{location}: {malformed}.")


def _require_ready_production_readiness(readiness: Any, location: str) -> None:
    if not isinstance(readiness, dict):
        raise ModelConfigurationError(f"productionReadiness must be an object{location}.")
    required_fields = (
        "status",
        "reasons",
        "policyVersion",
        "deployedAlertThresholdName",
        "deployedAlertThreshold",
        "rankingMetricsAreNotSufficient",
    )
    missing = [field for field in required_fields if field not in readiness]
    if missing:
        raise ModelConfigurationError(f"productionReadiness missing required fields{location}: {missing}.")
    if not isinstance(readiness.get("reasons"), list):
        raise ModelConfigurationError(f"productionReadiness.reasons must be a list{location}.")
    if readiness.get("status") != "READY":
        raise ModelConfigurationError(
            f"production readiness failed{location}: {readiness.get('reasons', [])!r}."
        )


def _read_artifact(artifact_path: Path) -> dict[str, Any]:
    if not artifact_path.exists():
        raise ModelConfigurationError(f"Model artifact does not exist: {artifact_path}")
    try:
        artifact = json.loads(artifact_path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as exc:
        raise ModelConfigurationError(f"Invalid model artifact JSON: {artifact_path}") from exc
    if not isinstance(artifact, dict):
        raise ModelConfigurationError(f"Model artifact must be a JSON object: {artifact_path}")
    return artifact


def _training_mode(artifact: dict[str, Any]) -> str:
    value = artifact.get("trainingMode")
    training = artifact.get("training")
    if value is None and isinstance(training, dict):
        value = training.get("trainingMode")
    mode = str(value or "production")
    if mode not in FeaturePipeline.TRAINING_MODES:
        raise ModelConfigurationError(f"Unsupported trainingMode '{mode}'.")
    return mode


def _expected_schema(training_mode: str) -> list[str]:
    if training_mode == "production":
        return list(FEATURE_CONTRACT.production_inference_features)
    return list(FEATURE_CONTRACT.ml_feature_names)
