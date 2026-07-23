from __future__ import annotations

import hashlib
import json
from pathlib import Path
from typing import Any

from offline_evaluation.fdp123.evaluation_card.schema import (
    ARTIFACT_SET_VERSION,
    PLATFORM_RECOMMENDATION_EVALUATION_CARD_REPORT_TYPE,
    Fdp123EvaluationCardValidationError,
    validate_evaluation_card,
)


EXPECTED_EVALUATION_CARD_FILENAME = "platform_recommendation_evaluation_card.json"
EXPECTED_EVALUATION_CARD_MANIFEST_FILENAME = "manifest.json"
MAX_EVALUATION_CARD_BYTES = 262_144
MAX_EVALUATION_CARD_MANIFEST_BYTES = 65_536


def read_validated_evaluation_card_artifact_set(
        evaluation_card_path: Path,
        manifest_path: Path,
) -> tuple[dict[str, Any], str]:
    card_path = Path(evaluation_card_path)
    card_manifest_path = Path(manifest_path)
    _require_canonical_filename(card_path, EXPECTED_EVALUATION_CARD_FILENAME, "evaluation card")
    _require_canonical_filename(card_manifest_path, EXPECTED_EVALUATION_CARD_MANIFEST_FILENAME, "evaluation card manifest")
    manifest_bytes = _read_required_bytes(card_manifest_path, "evaluation card manifest", MAX_EVALUATION_CARD_MANIFEST_BYTES)
    card_bytes = _read_required_bytes(card_path, "evaluation card", MAX_EVALUATION_CARD_BYTES)
    manifest = _load_json_bytes(manifest_bytes, "evaluation card manifest")
    _validate_manifest(manifest, card_bytes)
    card = _load_json_bytes(card_bytes, "evaluation card")
    return validate_evaluation_card(card), hashlib.sha256(manifest_bytes).hexdigest()


def _validate_manifest(manifest: dict[str, Any], card_bytes: bytes) -> None:
    if manifest.get("reportType") != PLATFORM_RECOMMENDATION_EVALUATION_CARD_REPORT_TYPE:
        raise Fdp123EvaluationCardValidationError("evaluation card manifest reportType unsupported")
    if manifest.get("artifactSetVersion") != ARTIFACT_SET_VERSION:
        raise Fdp123EvaluationCardValidationError("evaluation card manifest artifactSetVersion unsupported")
    files = manifest.get("files")
    if not isinstance(files, list) or not files:
        raise Fdp123EvaluationCardValidationError("evaluation card manifest files must be a non-empty list")
    matching = [item for item in files if isinstance(item, dict) and item.get("name") == EXPECTED_EVALUATION_CARD_FILENAME]
    if len(matching) != 1:
        raise Fdp123EvaluationCardValidationError("evaluation card manifest must list exactly one card JSON entry")
    card_entry = matching[0]
    if card_entry.get("sha256") != hashlib.sha256(card_bytes).hexdigest():
        raise Fdp123EvaluationCardValidationError("evaluation card hash mismatch")
    if card_entry.get("sizeBytes") != len(card_bytes):
        raise Fdp123EvaluationCardValidationError("evaluation card size mismatch")


def _require_canonical_filename(path: Path, expected_name: str, label: str) -> None:
    if path.name != expected_name:
        raise Fdp123EvaluationCardValidationError(f"{label} filename must be {expected_name}")


def _read_required_bytes(path: Path, label: str, max_bytes: int) -> bytes:
    if not path.exists():
        raise Fdp123EvaluationCardValidationError(f"{label} missing")
    if path.is_symlink():
        raise Fdp123EvaluationCardValidationError(f"{label} must not be a symlink")
    if not path.is_file():
        raise Fdp123EvaluationCardValidationError(f"{label} must be a file")
    with path.open("rb") as handle:
        payload = handle.read(max_bytes + 1)
    if len(payload) > max_bytes:
        raise Fdp123EvaluationCardValidationError(f"{label} exceeds maximum byte size")
    return payload


def _load_json_bytes(payload: bytes, label: str) -> dict[str, Any]:
    try:
        value = json.loads(payload.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as exception:
        raise Fdp123EvaluationCardValidationError(f"{label} must be valid JSON") from exception
    if not isinstance(value, dict):
        raise Fdp123EvaluationCardValidationError(f"{label} must be a JSON object")
    return value
