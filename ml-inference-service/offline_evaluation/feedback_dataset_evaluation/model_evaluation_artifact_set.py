from __future__ import annotations

import json
from collections.abc import Mapping
from dataclasses import dataclass
from enum import Enum
from pathlib import Path, PurePosixPath, PureWindowsPath
from types import MappingProxyType
from typing import Any

from offline_evaluation.feedback_dataset_evaluation.artifact_integrity import (
    BoundedArtifactReadError,
    is_lowercase_sha256,
    read_bounded_regular_file,
    reject_symlink_path,
    sha256_hex,
)
from offline_evaluation.feedback_dataset_evaluation.dataset_reader import read_feedback_dataset_jsonl
from offline_evaluation.feedback_dataset_evaluation.model_evaluation import (
    build_source_dataset_identity,
    validate_model_evaluation_summary,
)
from offline_evaluation.feedback_dataset_evaluation.report_contract import (
    MODEL_EVALUATION_ARTIFACT_SET_VERSION,
    MODEL_EVALUATION_REPORT_TYPE,
)
from offline_evaluation.feedback_dataset_evaluation.timestamp_contract import (
    TimestampContractError,
    normalize_rfc3339_timestamp,
)
from offline_evaluation.json_contract import JsonContractError, loads_strict_json


SUMMARY_FILENAME = "model_evaluation_summary.json"
MANIFEST_FILENAME = "manifest.json"
MAX_SUMMARY_BYTES = 262_144
MAX_MANIFEST_BYTES = 65_536
EXPECTED_DIRECTORY_ENTRIES = {SUMMARY_FILENAME, MANIFEST_FILENAME}
MANIFEST_FIELDS = {"artifactSetVersion", "files", "generatedAt", "reportType"}
MANIFEST_FILE_FIELDS = {"name", "sha256", "sizeBytes"}


class ModelEvaluationArtifactSetError(ValueError):
    """Raised when model-specific evaluation evidence is incomplete or untrusted."""


class ModelEvaluationArtifactVerificationLevel(str, Enum):
    CLAIM_ONLY = "CLAIM_ONLY"
    VERIFIED_AGAINST_SOURCE_BYTES = "VERIFIED_AGAINST_SOURCE_BYTES"


@dataclass(frozen=True)
class ValidatedModelEvaluationArtifactSet:
    summary: Mapping[str, Any]
    manifest_sha256: str
    verification_level: ModelEvaluationArtifactVerificationLevel


def read_validated_model_evaluation_artifact_set(
        artifact_dir: str | Path,
        source_dataset_path: str | Path | None = None,
) -> ValidatedModelEvaluationArtifactSet:
    directory = Path(artifact_dir)
    _validate_artifact_directory(directory)

    manifest_path = directory / MANIFEST_FILENAME
    summary_path = directory / SUMMARY_FILENAME
    manifest_bytes = _read_artifact_bytes(manifest_path, "model evaluation manifest", MAX_MANIFEST_BYTES)
    manifest = _load_json_object(manifest_bytes, "model evaluation manifest")
    file_entry = _validate_manifest(manifest)

    summary_bytes = _read_artifact_bytes(summary_path, "model evaluation summary", MAX_SUMMARY_BYTES)
    if file_entry["sizeBytes"] != len(summary_bytes):
        raise ModelEvaluationArtifactSetError("model evaluation summary size does not match manifest")
    if file_entry["sha256"] != sha256_hex(summary_bytes):
        raise ModelEvaluationArtifactSetError("model evaluation summary sha256 does not match manifest")

    summary = _load_json_object(summary_bytes, "model evaluation summary")
    try:
        validated_summary = validate_model_evaluation_summary(summary)
    except ValueError as exc:
        raise ModelEvaluationArtifactSetError(str(exc)) from exc
    if manifest["generatedAt"] != validated_summary["generatedAt"]:
        raise ModelEvaluationArtifactSetError(
            "model evaluation manifest generatedAt must match summary generatedAt"
        )
    verification_level = ModelEvaluationArtifactVerificationLevel.CLAIM_ONLY
    if source_dataset_path is not None:
        try:
            actual_source = build_source_dataset_identity(
                read_feedback_dataset_jsonl(source_dataset_path)
            )
        except (FileNotFoundError, ValueError) as exc:
            raise ModelEvaluationArtifactSetError(
                "source feedback dataset cannot be validated"
            ) from exc
        if validated_summary["sourceDataset"] != actual_source:
            raise ModelEvaluationArtifactSetError(
                "model evaluation source dataset identity does not match actual source bytes"
            )
        verification_level = ModelEvaluationArtifactVerificationLevel.VERIFIED_AGAINST_SOURCE_BYTES
    return ValidatedModelEvaluationArtifactSet(
        summary=_freeze_json(validated_summary),
        manifest_sha256=sha256_hex(manifest_bytes),
        verification_level=verification_level,
    )


def _validate_artifact_directory(directory: Path) -> None:
    try:
        reject_symlink_path(directory, "model evaluation artifact directory")
    except BoundedArtifactReadError as exc:
        raise ModelEvaluationArtifactSetError(str(exc)) from exc
    if not directory.exists():
        raise ModelEvaluationArtifactSetError("model evaluation artifact directory is missing")
    if not directory.is_dir():
        raise ModelEvaluationArtifactSetError("model evaluation artifact path must be a directory")
    try:
        entries = list(directory.iterdir())
    except OSError as exc:
        raise ModelEvaluationArtifactSetError("model evaluation artifact directory cannot be read") from exc
    unexpected = sorted(entry.name for entry in entries if entry.name not in EXPECTED_DIRECTORY_ENTRIES)
    if unexpected:
        raise ModelEvaluationArtifactSetError(
            f"model evaluation artifact directory contains unsupported entries: {', '.join(unexpected)}"
        )


def _validate_manifest(manifest: dict[str, Any]) -> dict[str, Any]:
    _reject_unknown_or_missing(manifest, MANIFEST_FIELDS, "model evaluation manifest")
    if manifest["reportType"] != MODEL_EVALUATION_REPORT_TYPE:
        raise ModelEvaluationArtifactSetError("model evaluation manifest reportType unsupported")
    if manifest["artifactSetVersion"] != MODEL_EVALUATION_ARTIFACT_SET_VERSION:
        raise ModelEvaluationArtifactSetError("model evaluation manifest artifactSetVersion unsupported")
    try:
        normalized_generated_at = normalize_rfc3339_timestamp(
            manifest["generatedAt"],
            "model evaluation manifest generatedAt",
        )
    except TimestampContractError as exc:
        raise ModelEvaluationArtifactSetError(str(exc)) from exc
    if normalized_generated_at != manifest["generatedAt"]:
        raise ModelEvaluationArtifactSetError("model evaluation manifest generatedAt must be canonical")

    files = manifest["files"]
    if not isinstance(files, list):
        raise ModelEvaluationArtifactSetError("model evaluation manifest files must be a list")
    seen_names: set[str] = set()
    validated_entry: dict[str, Any] | None = None
    for entry in files:
        if not isinstance(entry, dict):
            raise ModelEvaluationArtifactSetError("model evaluation manifest file entry must be an object")
        _reject_unknown_or_missing(entry, MANIFEST_FILE_FIELDS, "model evaluation manifest file entry")
        name = entry["name"]
        _require_canonical_artifact_name(name)
        if name in seen_names:
            raise ModelEvaluationArtifactSetError("model evaluation manifest contains duplicate artifact")
        seen_names.add(name)
        _validate_manifest_file_metadata(entry)
        validated_entry = entry
    if seen_names != {SUMMARY_FILENAME} or len(files) != 1 or validated_entry is None:
        raise ModelEvaluationArtifactSetError(
            "model evaluation manifest must list exactly model_evaluation_summary.json"
        )
    return validated_entry


def _require_canonical_artifact_name(name: Any) -> None:
    if not isinstance(name, str):
        raise ModelEvaluationArtifactSetError("model evaluation manifest artifact name must be a string")
    if (
            PurePosixPath(name).is_absolute()
            or PureWindowsPath(name).is_absolute()
            or "/" in name
            or "\\" in name
            or name in {".", ".."}
    ):
        raise ModelEvaluationArtifactSetError("model evaluation manifest artifact name must be canonical")
    if name != SUMMARY_FILENAME:
        raise ModelEvaluationArtifactSetError("model evaluation manifest lists unsupported artifact")


def _validate_manifest_file_metadata(entry: dict[str, Any]) -> None:
    size_bytes = entry["sizeBytes"]
    if isinstance(size_bytes, bool) or not isinstance(size_bytes, int) or size_bytes < 0:
        raise ModelEvaluationArtifactSetError(
            "model evaluation manifest sizeBytes must be a non-negative integer"
        )
    sha256 = entry["sha256"]
    if not is_lowercase_sha256(sha256):
        raise ModelEvaluationArtifactSetError("model evaluation manifest sha256 must be lowercase hex")


def _read_artifact_bytes(path: Path, label: str, max_bytes: int) -> bytes:
    try:
        return read_bounded_regular_file(path, label, max_bytes)
    except BoundedArtifactReadError as exc:
        raise ModelEvaluationArtifactSetError(str(exc)) from exc


def _load_json_object(payload: bytes, label: str) -> dict[str, Any]:
    try:
        value = loads_strict_json(payload)
    except (UnicodeDecodeError, json.JSONDecodeError, JsonContractError) as exc:
        raise ModelEvaluationArtifactSetError(f"{label} must be valid strict JSON") from exc
    if not isinstance(value, dict):
        raise ModelEvaluationArtifactSetError(f"{label} must be a JSON object")
    return value


def _reject_unknown_or_missing(raw: dict[str, Any], expected: set[str], label: str) -> None:
    extra = sorted(set(raw) - expected)
    if extra:
        raise ModelEvaluationArtifactSetError(f"{label} contains unsupported fields: {', '.join(extra)}")
    missing = sorted(expected - set(raw))
    if missing:
        raise ModelEvaluationArtifactSetError(f"{label} missing required fields: {', '.join(missing)}")


def _freeze_json(value: Any) -> Any:
    if isinstance(value, dict):
        return MappingProxyType({key: _freeze_json(nested) for key, nested in value.items()})
    if isinstance(value, list):
        return tuple(_freeze_json(item) for item in value)
    return value
