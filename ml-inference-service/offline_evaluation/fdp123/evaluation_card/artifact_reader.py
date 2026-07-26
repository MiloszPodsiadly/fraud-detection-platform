from __future__ import annotations

import hashlib
import json
from pathlib import Path
from typing import Any

from offline_evaluation.json_contract import JsonContractError, loads_strict_json
from offline_evaluation.fdp123.evaluation_card.schema import (
    ARTIFACT_SET_VERSION,
    PLATFORM_RECOMMENDATION_EVALUATION_CARD_REPORT_TYPE,
    Fdp123EvaluationCardValidationError,
    validate_evaluation_card,
)
from offline_evaluation.fdp123.timestamp_contract import (
    TimestampContractError,
    normalize_rfc3339_timestamp,
)


EXPECTED_EVALUATION_CARD_FILENAME = "platform_recommendation_evaluation_card.json"
EXPECTED_EVALUATION_CARD_MARKDOWN_FILENAME = "platform_recommendation_evaluation_card.md"
EXPECTED_EVALUATION_CARD_MANIFEST_FILENAME = "manifest.json"
MAX_EVALUATION_CARD_BYTES = 262_144
MAX_EVALUATION_CARD_MARKDOWN_BYTES = 262_144
MAX_EVALUATION_CARD_MANIFEST_BYTES = 65_536
MANIFEST_FIELDS = {"artifactSetVersion", "files", "generatedAt", "reportType"}
MANIFEST_FILE_FIELDS = {"name", "sha256", "sizeBytes"}
EXPECTED_ARTIFACT_FILENAMES = {
    EXPECTED_EVALUATION_CARD_FILENAME,
    EXPECTED_EVALUATION_CARD_MARKDOWN_FILENAME,
}


def read_validated_evaluation_card_artifact_set(
        evaluation_card_path: Path,
        manifest_path: Path,
) -> tuple[dict[str, Any], str]:
    card_path = Path(evaluation_card_path)
    card_manifest_path = Path(manifest_path)
    _require_canonical_filename(card_path, EXPECTED_EVALUATION_CARD_FILENAME, "evaluation card")
    _require_canonical_filename(card_manifest_path, EXPECTED_EVALUATION_CARD_MANIFEST_FILENAME, "evaluation card manifest")
    markdown_path = card_path.with_name(EXPECTED_EVALUATION_CARD_MARKDOWN_FILENAME)
    manifest_bytes = _read_required_bytes(card_manifest_path, "evaluation card manifest", MAX_EVALUATION_CARD_MANIFEST_BYTES)
    card_bytes = _read_required_bytes(card_path, "evaluation card", MAX_EVALUATION_CARD_BYTES)
    markdown_bytes = _read_required_bytes(markdown_path, "evaluation card markdown", MAX_EVALUATION_CARD_MARKDOWN_BYTES)
    manifest = _load_json_bytes(manifest_bytes, "evaluation card manifest")
    card = _load_json_bytes(card_bytes, "evaluation card")
    validated_card = validate_evaluation_card(card)
    _validate_manifest(manifest, validated_card, {
        EXPECTED_EVALUATION_CARD_FILENAME: card_bytes,
        EXPECTED_EVALUATION_CARD_MARKDOWN_FILENAME: markdown_bytes,
    })
    return validated_card, hashlib.sha256(manifest_bytes).hexdigest()


def _validate_manifest(
        manifest: dict[str, Any],
        validated_card: dict[str, Any],
        artifact_bytes: dict[str, bytes],
) -> None:
    _reject_unknown_or_missing(manifest, MANIFEST_FIELDS, "evaluation card manifest")
    if manifest.get("reportType") != PLATFORM_RECOMMENDATION_EVALUATION_CARD_REPORT_TYPE:
        raise Fdp123EvaluationCardValidationError("evaluation card manifest reportType unsupported")
    if manifest.get("artifactSetVersion") != ARTIFACT_SET_VERSION:
        raise Fdp123EvaluationCardValidationError("evaluation card manifest artifactSetVersion unsupported")
    try:
        manifest_generated_at = normalize_rfc3339_timestamp(
            manifest.get("generatedAt"), "evaluation card manifest generatedAt"
        )
    except TimestampContractError as exc:
        raise Fdp123EvaluationCardValidationError(str(exc)) from exc
    if manifest_generated_at != validated_card["generatedAt"]:
        raise Fdp123EvaluationCardValidationError("evaluation card manifest generatedAt must match card generatedAt")
    files = manifest.get("files")
    if not isinstance(files, list) or len(files) != len(EXPECTED_ARTIFACT_FILENAMES):
        raise Fdp123EvaluationCardValidationError("evaluation card manifest files must list the canonical artifacts")
    seen_names: set[str] = set()
    for item in files:
        if not isinstance(item, dict):
            raise Fdp123EvaluationCardValidationError("evaluation card manifest file entries must be objects")
        _reject_unknown_or_missing(item, MANIFEST_FILE_FIELDS, "evaluation card manifest file entry")
        name = item["name"]
        if name not in EXPECTED_ARTIFACT_FILENAMES:
            raise Fdp123EvaluationCardValidationError("evaluation card manifest lists unsupported artifact")
        if name in seen_names:
            raise Fdp123EvaluationCardValidationError("evaluation card manifest contains duplicate artifact")
        seen_names.add(name)
        expected_bytes = artifact_bytes[name]
        sha256 = item["sha256"]
        size_bytes = item["sizeBytes"]
        if not isinstance(sha256, str) or len(sha256) != 64 or not all(c in "0123456789abcdef" for c in sha256):
            raise Fdp123EvaluationCardValidationError("evaluation card manifest sha256 must be lowercase hex")
        if isinstance(size_bytes, bool) or not isinstance(size_bytes, int) or size_bytes < 0:
            raise Fdp123EvaluationCardValidationError("evaluation card manifest sizeBytes must be a non-negative integer")
        if sha256 != hashlib.sha256(expected_bytes).hexdigest():
            raise Fdp123EvaluationCardValidationError("evaluation card artifact hash mismatch")
        if size_bytes != len(expected_bytes):
            raise Fdp123EvaluationCardValidationError("evaluation card artifact size mismatch")
    if seen_names != EXPECTED_ARTIFACT_FILENAMES:
        raise Fdp123EvaluationCardValidationError("evaluation card manifest files must list the canonical artifacts")


def _require_canonical_filename(path: Path, expected_name: str, label: str) -> None:
    if path.name != expected_name:
        raise Fdp123EvaluationCardValidationError(f"{label} filename must be {expected_name}")


def _read_required_bytes(path: Path, label: str, max_bytes: int) -> bytes:
    if not path.exists():
        raise Fdp123EvaluationCardValidationError(f"{label} missing")
    if path.is_symlink():
        raise Fdp123EvaluationCardValidationError(f"{label} must not be a symlink")
    _reject_symlink_parent(path, label)
    if not path.is_file():
        raise Fdp123EvaluationCardValidationError(f"{label} must be a file")
    with path.open("rb") as handle:
        payload = handle.read(max_bytes + 1)
    if len(payload) > max_bytes:
        raise Fdp123EvaluationCardValidationError(f"{label} exceeds maximum byte size")
    return payload


def _reject_symlink_parent(path: Path, label: str) -> None:
    parent = path.parent
    while parent != parent.parent:
        if parent.is_symlink():
            raise Fdp123EvaluationCardValidationError(f"{label} parent must not be a symlink")
        parent = parent.parent


def _reject_unknown_or_missing(raw: dict[str, Any], allowed: set[str], label: str) -> None:
    extra = sorted(set(raw) - allowed)
    if extra:
        raise Fdp123EvaluationCardValidationError(f"{label} contains unsupported fields: {', '.join(extra)}")
    missing = sorted(allowed - set(raw))
    if missing:
        raise Fdp123EvaluationCardValidationError(f"{label} missing required fields: {', '.join(missing)}")


def _load_json_bytes(payload: bytes, label: str) -> dict[str, Any]:
    try:
        value = loads_strict_json(payload)
    except (UnicodeDecodeError, json.JSONDecodeError, JsonContractError) as exception:
        raise Fdp123EvaluationCardValidationError(f"{label} must be valid JSON") from exception
    if not isinstance(value, dict):
        raise Fdp123EvaluationCardValidationError(f"{label} must be a JSON object")
    return value
