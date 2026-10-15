from __future__ import annotations

import json
from collections.abc import Mapping
from dataclasses import dataclass
from pathlib import Path
from types import MappingProxyType
from typing import Any

from offline_evaluation.feedback_dataset_evaluation.artifact_integrity import (
    BoundedArtifactReadError,
    is_lowercase_sha256,
    read_bounded_regular_file,
    reject_symlink_path,
    sha256_hex,
)
from offline_evaluation.feedback_dataset_evaluation.exact_model_shadow_performance.schema import (
    MAX_SUMMARY_BYTES,
    REPORT_TYPE,
    SCHEMA_VERSION,
    ExactModelShadowPerformanceValidationError,
    exact_report_selection_key,
    validate_exact_model_shadow_performance,
)
from offline_evaluation.feedback_dataset_evaluation.timestamp_contract import normalize_rfc3339_timestamp
from offline_evaluation.json_contract import JsonContractError, dumps_strict_json, loads_strict_json


ARTIFACT_SET_VERSION = "exact-model-shadow-performance-artifact-set-v1"
SUMMARY_FILENAME = "exact_model_shadow_performance.json"
MANIFEST_FILENAME = "manifest.json"
MAX_MANIFEST_BYTES = 65_536
EXPECTED_ENTRIES = {SUMMARY_FILENAME, MANIFEST_FILENAME}
MANIFEST_FIELDS = {
    "artifactSetVersion", "schemaVersion", "reportType", "selectionKey", "generatedAt", "files"
}
FILE_FIELDS = {"name", "sha256", "sizeBytes"}


@dataclass(frozen=True)
class ValidatedExactModelShadowArtifactSet:
    summary: Mapping[str, Any]
    manifest_sha256: str
    selection_key: str


def canonical_summary_json(summary: dict[str, Any]) -> str:
    validated = validate_exact_model_shadow_performance(summary)
    return dumps_strict_json(validated, sort_keys=True, separators=(",", ":")) + "\n"


def build_manifest(summary_bytes: bytes, summary: dict[str, Any], selection_key: str) -> str:
    manifest = {
        "artifactSetVersion": ARTIFACT_SET_VERSION,
        "schemaVersion": SCHEMA_VERSION,
        "reportType": REPORT_TYPE,
        "selectionKey": selection_key,
        "generatedAt": summary["generatedAt"],
        "files": [{
            "name": SUMMARY_FILENAME,
            "sha256": sha256_hex(summary_bytes),
            "sizeBytes": len(summary_bytes),
        }],
    }
    return dumps_strict_json(manifest, sort_keys=True, separators=(",", ":")) + "\n"


def read_selected_exact_model_shadow_artifact_set(
        artifact_root: str | Path,
        subject: dict[str, Any],
        evaluation_window: dict[str, Any],
) -> ValidatedExactModelShadowArtifactSet | None:
    key = exact_report_selection_key(subject, evaluation_window)
    directory = Path(artifact_root) / key
    if not directory.exists() and not directory.is_symlink():
        return None
    evidence = read_exact_model_shadow_artifact_set(directory)
    summary = evidence.summary
    if summary["evaluationSummary"]["evaluationSubject"] != subject:
        raise ExactModelShadowPerformanceValidationError("selected report subject mismatch")
    if summary["evaluationSummary"]["evaluationWindow"] != evaluation_window:
        raise ExactModelShadowPerformanceValidationError("selected report evaluation window mismatch")
    if evidence.selection_key != key:
        raise ExactModelShadowPerformanceValidationError("selected report key mismatch")
    return evidence


def read_exact_model_shadow_artifact_set(
        artifact_dir: str | Path,
) -> ValidatedExactModelShadowArtifactSet:
    directory = Path(artifact_dir)
    _validate_directory(directory)
    manifest_path = directory / MANIFEST_FILENAME
    summary_path = directory / SUMMARY_FILENAME
    first_manifest = _read(manifest_path, "exact-model shadow manifest", MAX_MANIFEST_BYTES)
    manifest = _object(first_manifest, "exact-model shadow manifest")
    summary_bytes = _read(summary_path, "exact-model shadow summary", MAX_SUMMARY_BYTES)
    summary = validate_exact_model_shadow_performance(_object(summary_bytes, "exact-model shadow summary"))
    selection_key = exact_report_selection_key(
        summary["evaluationSummary"]["evaluationSubject"],
        summary["evaluationSummary"]["evaluationWindow"],
    )
    _validate_manifest(manifest, summary_bytes, summary, selection_key)
    if summary_bytes != canonical_summary_json(summary).encode("utf-8"):
        raise ExactModelShadowPerformanceValidationError("exact-model shadow summary is not canonical")
    expected_manifest = build_manifest(summary_bytes, summary, selection_key).encode("utf-8")
    if first_manifest != expected_manifest:
        raise ExactModelShadowPerformanceValidationError("exact-model shadow manifest is not canonical")
    if (
            _read(manifest_path, "exact-model shadow manifest", MAX_MANIFEST_BYTES) != first_manifest
            or _read(summary_path, "exact-model shadow summary", MAX_SUMMARY_BYTES) != summary_bytes
    ):
        raise ExactModelShadowPerformanceValidationError("exact-model shadow artifact changed while being read")
    return ValidatedExactModelShadowArtifactSet(
        summary=_freeze(summary),
        manifest_sha256=sha256_hex(first_manifest),
        selection_key=selection_key,
    )


def _validate_directory(directory: Path) -> None:
    try:
        reject_symlink_path(directory, "exact-model shadow artifact directory")
    except BoundedArtifactReadError as exc:
        raise ExactModelShadowPerformanceValidationError(str(exc)) from exc
    if not directory.is_dir():
        raise ExactModelShadowPerformanceValidationError("exact-model shadow artifact directory is missing")
    try:
        entries = {entry.name for entry in directory.iterdir()}
    except OSError as exc:
        raise ExactModelShadowPerformanceValidationError("exact-model shadow artifact directory cannot be read") from exc
    if entries != EXPECTED_ENTRIES:
        raise ExactModelShadowPerformanceValidationError(
            "exact-model shadow artifact directory must contain only canonical artifacts"
        )


def _validate_manifest(
        manifest: dict[str, Any],
        summary_bytes: bytes,
        summary: dict[str, Any],
        selection_key: str,
) -> None:
    _exact_fields(manifest, MANIFEST_FIELDS, "manifest")
    if manifest["artifactSetVersion"] != ARTIFACT_SET_VERSION:
        raise ExactModelShadowPerformanceValidationError("manifest artifactSetVersion is unsupported")
    if manifest["schemaVersion"] != SCHEMA_VERSION or manifest["reportType"] != REPORT_TYPE:
        raise ExactModelShadowPerformanceValidationError("manifest contract identity is unsupported")
    if manifest["selectionKey"] != selection_key:
        raise ExactModelShadowPerformanceValidationError("manifest selectionKey mismatch")
    if normalize_rfc3339_timestamp(manifest["generatedAt"], "manifest.generatedAt") != summary["generatedAt"]:
        raise ExactModelShadowPerformanceValidationError("manifest generatedAt mismatch")
    files = manifest["files"]
    if not isinstance(files, list) or len(files) != 1 or not isinstance(files[0], dict):
        raise ExactModelShadowPerformanceValidationError("manifest must list one canonical summary")
    entry = files[0]
    _exact_fields(entry, FILE_FIELDS, "manifest file")
    if entry["name"] != SUMMARY_FILENAME:
        raise ExactModelShadowPerformanceValidationError("manifest filename is unsupported")
    if not is_lowercase_sha256(entry["sha256"]):
        raise ExactModelShadowPerformanceValidationError("manifest sha256 must be lowercase hex")
    if isinstance(entry["sizeBytes"], bool) or not isinstance(entry["sizeBytes"], int):
        raise ExactModelShadowPerformanceValidationError("manifest sizeBytes must be an integer")
    if entry["sizeBytes"] != len(summary_bytes) or entry["sha256"] != sha256_hex(summary_bytes):
        raise ExactModelShadowPerformanceValidationError("manifest summary integrity mismatch")


def _read(path: Path, label: str, maximum: int) -> bytes:
    try:
        return read_bounded_regular_file(path, label, maximum)
    except BoundedArtifactReadError as exc:
        raise ExactModelShadowPerformanceValidationError(str(exc)) from exc


def _object(payload: bytes, label: str) -> dict[str, Any]:
    try:
        value = loads_strict_json(payload)
    except (UnicodeDecodeError, json.JSONDecodeError, JsonContractError) as exc:
        raise ExactModelShadowPerformanceValidationError(f"{label} must be strict JSON") from exc
    if not isinstance(value, dict):
        raise ExactModelShadowPerformanceValidationError(f"{label} must be an object")
    return value


def _exact_fields(value: dict[str, Any], expected: set[str], label: str) -> None:
    if set(value) != expected:
        raise ExactModelShadowPerformanceValidationError(f"{label} field set is unsupported")


def _freeze(value: Any) -> Any:
    if isinstance(value, dict):
        return MappingProxyType({key: _freeze(nested) for key, nested in value.items()})
    if isinstance(value, list):
        return tuple(_freeze(item) for item in value)
    return value

