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
from offline_evaluation.feedback_dataset_evaluation.model_card.schema import (
    MAX_MODEL_CARD_BYTES,
    MODEL_CARD_TYPE,
    ModelCardValidationError,
    validate_model_card,
)
from offline_evaluation.feedback_dataset_evaluation.model_card.writer import (
    MODEL_CARD_ARTIFACT_SET_VERSION,
    MODEL_CARD_FILENAME,
    MODEL_CARD_MANIFEST_FILENAME,
    MODEL_CARD_MARKDOWN_FILENAME,
    build_model_card_manifest,
    model_card_json,
    model_card_markdown,
)
from offline_evaluation.feedback_dataset_evaluation.timestamp_contract import normalize_rfc3339_timestamp
from offline_evaluation.json_contract import JsonContractError, loads_strict_json


MAX_MODEL_CARD_MARKDOWN_BYTES = 262_144
MAX_MODEL_CARD_MANIFEST_BYTES = 65_536
EXPECTED_DIRECTORY_ENTRIES = {
    MODEL_CARD_FILENAME,
    MODEL_CARD_MARKDOWN_FILENAME,
    MODEL_CARD_MANIFEST_FILENAME,
}
MANIFEST_FIELDS = {"artifactSetVersion", "files", "generatedAt", "reportType"}
MANIFEST_FILE_FIELDS = {"name", "sha256", "sizeBytes"}


@dataclass(frozen=True)
class ValidatedModelCardArtifactSet:
    card: Mapping[str, Any]
    manifest_sha256: str


def read_validated_model_card_artifact_set(artifact_dir: str | Path) -> ValidatedModelCardArtifactSet:
    directory = Path(artifact_dir)
    _validate_directory(directory)
    manifest_path = directory / MODEL_CARD_MANIFEST_FILENAME
    manifest_bytes = _read(manifest_path, "model card manifest", MAX_MODEL_CARD_MANIFEST_BYTES)
    manifest = _load_object(manifest_bytes, "model card manifest")
    card_bytes = _read(directory / MODEL_CARD_FILENAME, "model card", MAX_MODEL_CARD_BYTES)
    markdown_bytes = _read(
        directory / MODEL_CARD_MARKDOWN_FILENAME,
        "model card markdown",
        MAX_MODEL_CARD_MARKDOWN_BYTES,
    )
    card = _load_object(card_bytes, "model card")
    validated = validate_model_card(card)
    artifact_bytes = {
        MODEL_CARD_FILENAME: card_bytes,
        MODEL_CARD_MARKDOWN_FILENAME: markdown_bytes,
    }
    _validate_manifest(manifest, artifact_bytes, validated["generatedAt"])
    try:
        markdown = markdown_bytes.decode("utf-8")
    except UnicodeDecodeError as exc:
        raise ModelCardValidationError("model card markdown must be UTF-8") from exc
    if card_bytes != model_card_json(validated).encode("utf-8"):
        raise ModelCardValidationError("model card JSON is not canonical")
    if markdown != model_card_markdown(validated):
        raise ModelCardValidationError("model card markdown does not match the canonical card")
    canonical_manifest = build_model_card_manifest(
        {
            directory / MODEL_CARD_FILENAME: card_bytes.decode("utf-8"),
            directory / MODEL_CARD_MARKDOWN_FILENAME: markdown,
        },
        validated["generatedAt"],
    ).encode("utf-8")
    if manifest_bytes != canonical_manifest:
        raise ModelCardValidationError("model card manifest is not canonical")
    if _read(manifest_path, "model card manifest", MAX_MODEL_CARD_MANIFEST_BYTES) != manifest_bytes:
        raise ModelCardValidationError("model card manifest changed while the artifact set was read")
    return ValidatedModelCardArtifactSet(
        card=_freeze_json(validated),
        manifest_sha256=sha256_hex(manifest_bytes),
    )


def _validate_directory(directory: Path) -> None:
    try:
        reject_symlink_path(directory, "model card artifact directory")
    except BoundedArtifactReadError as exc:
        raise ModelCardValidationError(str(exc)) from exc
    if not directory.is_dir():
        raise ModelCardValidationError("model card artifact directory is missing")
    entries = {entry.name for entry in directory.iterdir()}
    if entries != EXPECTED_DIRECTORY_ENTRIES:
        raise ModelCardValidationError("model card artifact directory must contain only canonical artifacts")


def _validate_manifest(manifest: dict[str, Any], artifact_bytes: dict[str, bytes], generated_at: str) -> None:
    _require_exact_fields(manifest, MANIFEST_FIELDS, "model card manifest")
    if manifest["artifactSetVersion"] != MODEL_CARD_ARTIFACT_SET_VERSION:
        raise ModelCardValidationError("model card manifest artifactSetVersion unsupported")
    if manifest["reportType"] != MODEL_CARD_TYPE:
        raise ModelCardValidationError("model card manifest reportType unsupported")
    normalized = normalize_rfc3339_timestamp(manifest["generatedAt"], "model card manifest generatedAt")
    if normalized != manifest["generatedAt"] or manifest["generatedAt"] != generated_at:
        raise ModelCardValidationError("model card manifest generatedAt must match the card")
    files = manifest["files"]
    if not isinstance(files, list) or len(files) != len(artifact_bytes):
        raise ModelCardValidationError("model card manifest must list the canonical artifacts")
    seen: set[str] = set()
    for entry in files:
        if not isinstance(entry, dict):
            raise ModelCardValidationError("model card manifest file entry must be an object")
        _require_exact_fields(entry, MANIFEST_FILE_FIELDS, "model card manifest file entry")
        name = entry["name"]
        if name not in artifact_bytes or name in seen:
            raise ModelCardValidationError("model card manifest lists an unsupported or duplicate artifact")
        seen.add(name)
        if not is_lowercase_sha256(entry["sha256"]):
            raise ModelCardValidationError("model card manifest sha256 must be lowercase hex")
        size = entry["sizeBytes"]
        if isinstance(size, bool) or not isinstance(size, int) or size < 0:
            raise ModelCardValidationError("model card manifest sizeBytes must be a non-negative integer")
        payload = artifact_bytes[name]
        if entry["sha256"] != sha256_hex(payload) or size != len(payload):
            raise ModelCardValidationError("model card artifact integrity mismatch")
    if seen != set(artifact_bytes):
        raise ModelCardValidationError("model card manifest must list the canonical artifacts")


def _read(path: Path, label: str, max_bytes: int) -> bytes:
    try:
        return read_bounded_regular_file(path, label, max_bytes)
    except BoundedArtifactReadError as exc:
        raise ModelCardValidationError(str(exc)) from exc


def _load_object(payload: bytes, label: str) -> dict[str, Any]:
    try:
        value = loads_strict_json(payload)
    except (UnicodeDecodeError, json.JSONDecodeError, JsonContractError) as exc:
        raise ModelCardValidationError(f"{label} must be valid strict JSON") from exc
    if not isinstance(value, dict):
        raise ModelCardValidationError(f"{label} must be a JSON object")
    return value


def _require_exact_fields(value: dict[str, Any], expected: set[str], label: str) -> None:
    extra = sorted(set(value) - expected)
    if extra:
        raise ModelCardValidationError(f"{label} contains unsupported fields: {', '.join(extra)}")
    missing = sorted(expected - set(value))
    if missing:
        raise ModelCardValidationError(f"{label} missing required fields: {', '.join(missing)}")


def _freeze_json(value: Any) -> Any:
    if isinstance(value, dict):
        return MappingProxyType({key: _freeze_json(nested) for key, nested in value.items()})
    if isinstance(value, list):
        return tuple(_freeze_json(item) for item in value)
    return value
