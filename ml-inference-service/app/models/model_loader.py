from __future__ import annotations

import base64
import binascii
import hashlib
import json
import math
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from app.features.feature_contract import FEATURE_CONTRACT
from app.features.feature_pipeline import FeaturePipeline
from app.models.logistic_model import LogisticFraudModel
from app.models.xgboost_model import XGBoostFraudModel
from app.model_identity_policy import (
    ModelArtifactIdentity,
    ModelLogicalIdentity,
    validate_feature_contract_version,
    validate_model_name,
    validate_model_version,
)


class ModelConfigurationError(RuntimeError):
    """Raised when a model artifact cannot be loaded safely."""


MAX_MODEL_ARTIFACT_BYTES = 16 * 1024 * 1024


@dataclass(frozen=True)
class LoadedModelArtifact:
    """Parsed model metadata and identity derived from one exact byte sequence."""

    artifact: dict[str, Any]
    artifact_identity: ModelArtifactIdentity
    canonical_artifact_path: Path
    exact_bytes: bytes

    @property
    def logical_identity(self) -> ModelLogicalIdentity:
        return self.artifact_identity.logical_identity

    @property
    def artifact_sha256(self) -> str:
        return self.artifact_identity.artifact_sha256


@dataclass(frozen=True)
class ValidatedModelArtifact(LoadedModelArtifact):
    """Exact-byte model artifact accepted by the current runtime contract."""


def load_model_from_artifact(artifact_path: Path) -> LogisticFraudModel | XGBoostFraudModel:
    """Load the model implementation declared by artifact metadata."""
    validated = load_validated_model_artifact(artifact_path)
    return model_from_validated_artifact(validated)


def model_from_validated_artifact(validated: ValidatedModelArtifact) -> LogisticFraudModel | XGBoostFraudModel:
    """Construct a model from the same parsed bytes that established artifact identity."""
    artifact = validated.artifact
    model_type = validated.artifact_identity.model_type
    if model_type == "logistic":
        return LogisticFraudModel(artifact)
    if model_type == "xgboost":
        try:
            return XGBoostFraudModel(artifact)
        except RuntimeError as exception:
            if "requires the optional 'xgboost' Python package" in str(exception):
                raise
            raise ModelConfigurationError("XGBoost runtime payload could not be loaded.") from exception
        except Exception as exception:
            raise ModelConfigurationError("XGBoost runtime payload could not be loaded.") from exception
    raise ModelConfigurationError(
        f"Unsupported modelType '{model_type}' in artifact {validated.canonical_artifact_path}. "
        "Supported values: logistic, xgboost."
    )


def model_type_from_artifact(artifact_path: Path) -> str:
    """Return the validated model type derived from exact artifact bytes."""
    return load_validated_model_artifact(artifact_path).artifact_identity.model_type


def load_validated_model_artifact(
        artifact_path: Path,
        max_bytes: int | None = None,
) -> ValidatedModelArtifact:
    """Read, hash, parse and validate one bounded artifact byte sequence."""
    return validate_loaded_model_artifact(load_model_artifact(artifact_path, max_bytes=max_bytes))


def load_model_artifact(artifact_path: Path, max_bytes: int | None = None) -> LoadedModelArtifact:
    """Read, hash, parse and identify one bounded artifact byte sequence."""
    canonical_path, exact_bytes = _read_exact_artifact_bytes(
        artifact_path,
        MAX_MODEL_ARTIFACT_BYTES if max_bytes is None else max_bytes,
    )
    artifact_sha256 = hashlib.sha256(exact_bytes).hexdigest()
    artifact = _parse_artifact(exact_bytes, canonical_path)
    model_type = str(artifact.get("modelType", "logistic")).lower()
    if model_type not in {"logistic", "xgboost"}:
        raise ModelConfigurationError(
            f"Unsupported modelType '{model_type}' in artifact {canonical_path}. "
            "Supported values: logistic, xgboost."
        )
    try:
        artifact_identity = ModelArtifactIdentity(
            model_name=artifact["modelName"],
            model_version=artifact["modelVersion"],
            model_type=model_type,
            model_family=artifact["modelFamily"],
            feature_contract_version=artifact["featureContractVersion"],
            artifact_sha256=artifact_sha256,
        )
    except (KeyError, ValueError) as exception:
        raise ModelConfigurationError(
            f"model artifact identity invalid in artifact {canonical_path}: {exception}"
        ) from exception
    return LoadedModelArtifact(
        artifact=artifact,
        artifact_identity=artifact_identity,
        canonical_artifact_path=canonical_path,
        exact_bytes=exact_bytes,
    )


def validate_loaded_model_artifact(loaded: LoadedModelArtifact) -> ValidatedModelArtifact:
    """Validate a previously identified artifact without reopening its path."""
    validate_model_artifact(loaded.artifact, loaded.canonical_artifact_path)
    return ValidatedModelArtifact(
        artifact=loaded.artifact,
        artifact_identity=loaded.artifact_identity,
        canonical_artifact_path=loaded.canonical_artifact_path,
        exact_bytes=loaded.exact_bytes,
    )


def validate_model_artifact(artifact: dict[str, Any], artifact_path: Path | None = None) -> None:
    """Fail fast when an artifact is not aligned with the runtime feature contract."""
    location = f" in artifact {artifact_path}" if artifact_path is not None else ""
    raw_model_type = artifact.get("modelType")
    if not isinstance(raw_model_type, str) or raw_model_type not in {"logistic", "xgboost"}:
        raise ModelConfigurationError(
            f"Unsupported modelType '{raw_model_type}'{location}. Supported values: logistic, xgboost."
        )
    model_type = raw_model_type
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
            "modelRuntimeReadiness",
            "evaluation",
            "training",
        ),
        location,
    )
    _require_canonical_model_identity(artifact, location)
    _require_model_type_family(artifact, model_type, location)
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
    _require_thresholds(artifact.get("thresholds"), location)
    _require_ready_model_runtime_readiness(artifact.get("modelRuntimeReadiness"), location)
    if model_type == "logistic":
        _require_logistic_parameters(artifact, expected_schema, location)
    else:
        _require_xgboost_parameters(artifact, location)


def _require_model_type_family(artifact: dict[str, Any], model_type: str, location: str) -> None:
    expected_family = "LOGISTIC_REGRESSION" if model_type == "logistic" else "XGBOOST"
    if artifact.get("modelFamily") != expected_family:
        raise ModelConfigurationError(
            f"modelFamily is incompatible with modelType '{model_type}'{location}: "
            f"expected {expected_family!r}, got {artifact.get('modelFamily')!r}."
        )


def _require_logistic_parameters(
        artifact: dict[str, Any],
        expected_schema: list[str],
        location: str,
) -> None:
    if "bias" not in artifact:
        raise ModelConfigurationError(f"logistic artifact missing required bias{location}.")
    _require_finite_number(artifact.get("bias"), "logistic bias", location)
    weights = artifact.get("weights")
    if not isinstance(weights, dict):
        raise ModelConfigurationError(f"logistic artifact weights must be a JSON object{location}.")
    actual_weight_keys = list(weights)
    if set(actual_weight_keys) != set(expected_schema) or len(actual_weight_keys) != len(expected_schema):
        raise ModelConfigurationError(
            f"logistic weights schema mismatch{location}: expected keys {expected_schema}, got {actual_weight_keys!r}."
        )
    for feature_name in expected_schema:
        _require_finite_number(weights[feature_name], f"logistic weight {feature_name!r}", location)


def _require_xgboost_parameters(artifact: dict[str, Any], location: str) -> None:
    encoded_payload = artifact.get("modelDataBase64")
    if not isinstance(encoded_payload, str) or not encoded_payload:
        raise ModelConfigurationError(f"xgboost artifact requires non-empty modelDataBase64{location}.")
    try:
        payload = base64.b64decode(encoded_payload, validate=True)
    except (binascii.Error, ValueError) as exception:
        raise ModelConfigurationError(f"xgboost modelDataBase64 is invalid{location}.") from exception
    if not payload:
        raise ModelConfigurationError(f"xgboost modelDataBase64 decodes to an empty payload{location}.")


def _require_thresholds(value: Any, location: str) -> None:
    if not isinstance(value, dict):
        raise ModelConfigurationError(f"model thresholds must be a JSON object{location}.")
    expected_names = {"medium", "high", "critical"}
    if set(value) != expected_names:
        raise ModelConfigurationError(
            f"model thresholds must contain exactly {sorted(expected_names)}{location}; got {sorted(map(str, value))}."
        )
    thresholds = {
        name: _require_finite_number(value[name], f"threshold {name!r}", location)
        for name in ("medium", "high", "critical")
    }
    for name, threshold in thresholds.items():
        if not 0.0 <= threshold <= 1.0:
            raise ModelConfigurationError(f"threshold {name!r} must be within [0, 1]{location}.")
    if not thresholds["medium"] <= thresholds["high"] <= thresholds["critical"]:
        raise ModelConfigurationError(
            f"model thresholds must satisfy medium <= high <= critical{location}."
        )


def _require_finite_number(value: Any, label: str, location: str) -> float:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise ModelConfigurationError(f"{label} must be a finite JSON number{location}.")
    try:
        numeric_value = float(value)
    except (OverflowError, TypeError, ValueError) as exception:
        raise ModelConfigurationError(f"{label} must be a finite JSON number{location}.") from exception
    if not math.isfinite(numeric_value):
        raise ModelConfigurationError(f"{label} must be a finite JSON number{location}.")
    return numeric_value


def _require_canonical_model_identity(artifact: dict[str, Any], location: str) -> None:
    try:
        validate_model_name(artifact.get("modelName"), "modelName")
        validate_model_version(artifact.get("modelVersion"), "modelVersion")
        validate_feature_contract_version(artifact.get("featureContractVersion"), "featureContractVersion")
    except ValueError as exception:
        raise ModelConfigurationError(f"model artifact identity invalid{location}: {exception}") from exception


def _require_top_level_fields(artifact: dict[str, Any], fields: tuple[str, ...], location: str) -> None:
    missing = [field for field in fields if field not in artifact]
    if missing:
        raise ModelConfigurationError(f"model artifact missing required fields{location}: {missing}.")
    object_fields = ("thresholds", "thresholdPolicy", "evaluation", "training")
    malformed = [field for field in object_fields if not isinstance(artifact.get(field), dict)]
    if malformed:
        raise ModelConfigurationError(f"model artifact required object fields are malformed{location}: {malformed}.")


def _require_ready_model_runtime_readiness(readiness: Any, location: str) -> None:
    """Validate technical runtime readiness; this is not model promotion approval."""
    if not isinstance(readiness, dict):
        raise ModelConfigurationError(f"modelRuntimeReadiness must be an object{location}.")
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
        raise ModelConfigurationError(f"modelRuntimeReadiness missing required fields{location}: {missing}.")
    if not isinstance(readiness.get("reasons"), list):
        raise ModelConfigurationError(f"modelRuntimeReadiness.reasons must be a list{location}.")
    if readiness.get("status") != "READY":
        raise ModelConfigurationError(
            f"model runtime readiness failed{location}: {readiness.get('reasons', [])!r}."
        )


def _read_exact_artifact_bytes(artifact_path: Path, max_bytes: int) -> tuple[Path, bytes]:
    if max_bytes < 1:
        raise ValueError("max_bytes must be positive")
    try:
        canonical_path = artifact_path.resolve(strict=True)
    except FileNotFoundError as exception:
        raise ModelConfigurationError(f"Model artifact does not exist: {artifact_path}") from exception
    except OSError as exception:
        raise ModelConfigurationError(f"Model artifact cannot be resolved: {artifact_path}") from exception
    try:
        with canonical_path.open("rb") as artifact_file:
            exact_bytes = artifact_file.read(max_bytes + 1)
    except OSError as exception:
        raise ModelConfigurationError(f"Model artifact cannot be read: {canonical_path}") from exception
    if len(exact_bytes) > max_bytes:
        raise ModelConfigurationError(
            f"Model artifact exceeds maximum size of {max_bytes} bytes: {canonical_path}"
        )
    return canonical_path, exact_bytes


def _parse_artifact(exact_bytes: bytes, artifact_path: Path) -> dict[str, Any]:
    try:
        artifact = json.loads(exact_bytes)
    except (UnicodeDecodeError, json.JSONDecodeError) as exception:
        raise ModelConfigurationError(f"Invalid model artifact JSON: {artifact_path}") from exception
    if not isinstance(artifact, dict):
        raise ModelConfigurationError(f"Model artifact must be a JSON object: {artifact_path}")
    return artifact


def _training_mode(artifact: dict[str, Any]) -> str:
    value = artifact.get("trainingMode")
    if not isinstance(value, str) or value not in FeaturePipeline.TRAINING_MODES:
        raise ModelConfigurationError(f"Unsupported trainingMode {value!r}.")
    training = artifact.get("training")
    if not isinstance(training, dict) or training.get("trainingMode") != value:
        raise ModelConfigurationError(
            "training.trainingMode must exist and match the top-level trainingMode."
        )
    return value


def _expected_schema(training_mode: str) -> list[str]:
    if training_mode == "production":
        return list(FEATURE_CONTRACT.production_inference_features)
    return list(FEATURE_CONTRACT.ml_feature_names)
