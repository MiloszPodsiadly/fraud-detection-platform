from __future__ import annotations

import hashlib
import json
import os
import stat
from pathlib import Path
from typing import Any

from offline_evaluation.fdp123.timestamp_contract import (
    TimestampContractError,
    normalize_rfc3339_timestamp,
)
from offline_evaluation.json_contract import JsonContractError, dumps_strict_json, loads_strict_json
from offline_evaluation.promotion_review_readiness_schema import (
    PromotionReviewReadinessValidationError,
    validate_promotion_review_readiness_report,
)


ARTIFACT_SET_REPORT_TYPE = "PROMOTION_REVIEW_READINESS_ARTIFACT_SET_V1"
ARTIFACT_SET_VERSION = "promotion-review-readiness-artifact-set-v1"
REPORT_FILENAME = "promotion-review-readiness-report.json"
MANIFEST_FILENAME = "manifest.json"
MAX_REPORT_BYTES = 262_144
MAX_MANIFEST_BYTES = 65_536

MANIFEST_FIELDS = {"reportType", "artifactSetVersion", "generatedAt", "files"}
FILE_ENTRY_FIELDS = {"path", "sha256", "sizeBytes"}
SHA256_HEX_LENGTH = 64


class PromotionReviewReadinessArtifactSetError(ValueError):
    """Raised when the local Promotion Readiness artifact set is incomplete or tampered."""


def build_promotion_review_readiness_manifest(report_payload: str) -> str:
    try:
        raw_report = loads_strict_json(report_payload)
        validate_promotion_review_readiness_report(raw_report)
    except (JsonContractError, PromotionReviewReadinessValidationError) as exc:
        raise PromotionReviewReadinessArtifactSetError(str(exc)) from exc
    report_bytes = report_payload.encode("utf-8")
    manifest = {
        "artifactSetVersion": ARTIFACT_SET_VERSION,
        "files": [
            {
                "path": REPORT_FILENAME,
                "sha256": hashlib.sha256(report_bytes).hexdigest(),
                "sizeBytes": len(report_bytes),
            }
        ],
        "generatedAt": raw_report["generatedAt"],
        "reportType": ARTIFACT_SET_REPORT_TYPE,
    }
    return dumps_strict_json(manifest, sort_keys=True, separators=(",", ":")) + "\n"


def publish_promotion_review_readiness_artifact_set(report_payload: str, report_path: Path) -> Path:
    final_report = Path(report_path)
    if final_report.name != REPORT_FILENAME:
        raise PromotionReviewReadinessArtifactSetError(f"report path must end with {REPORT_FILENAME}")
    final_manifest = final_report.with_name(MANIFEST_FILENAME)
    report_tmp = final_report.with_name(f"{final_report.name}.tmp")
    manifest_tmp = final_manifest.with_name(f"{final_manifest.name}.tmp")
    temporary_paths = [report_tmp, manifest_tmp]
    try:
        for path in (final_report, final_manifest, report_tmp, manifest_tmp):
            if path.is_symlink():
                raise PromotionReviewReadinessArtifactSetError(f"artifact path must not be a symlink: {path.name}")
        report_tmp.write_text(report_payload, encoding="utf-8", newline="\n")
        manifest_tmp.write_text(
            build_promotion_review_readiness_manifest(report_payload),
            encoding="utf-8",
            newline="\n",
        )
        read_validated_promotion_review_readiness_artifact_set(
            report_tmp,
            manifest_tmp,
            allow_temporary_names=True,
        )
        if final_manifest.exists() or final_manifest.is_symlink():
            final_manifest.unlink()
        os.replace(report_tmp, final_report)
        os.replace(manifest_tmp, final_manifest)
    except Exception:
        for path in temporary_paths:
            if path.exists() or path.is_symlink():
                path.unlink()
        raise
    return final_report


def read_validated_promotion_review_readiness_artifact_set(
        report_path: Path,
        manifest_path: Path,
        *,
        allow_temporary_names: bool = False,
) -> dict[str, Any]:
    report_file = Path(report_path)
    manifest_file = Path(manifest_path)
    _require_canonical_filename(report_file, REPORT_FILENAME, "promotion readiness report", allow_temporary_names)
    _require_canonical_filename(manifest_file, MANIFEST_FILENAME, "promotion readiness manifest", allow_temporary_names)
    manifest_bytes = _read_required_bytes(manifest_file, "promotion readiness manifest", MAX_MANIFEST_BYTES)
    report_bytes = _read_required_bytes(report_file, "promotion readiness report", MAX_REPORT_BYTES)
    try:
        manifest = loads_strict_json(manifest_bytes)
        report = loads_strict_json(report_bytes)
    except (UnicodeDecodeError, json.JSONDecodeError, JsonContractError) as exc:
        raise PromotionReviewReadinessArtifactSetError("promotion readiness artifact set contains malformed JSON") from exc
    raw_report_generated_at = report.get("generatedAt") if isinstance(report, dict) else None
    try:
        validated_report = validate_promotion_review_readiness_report(report)
    except PromotionReviewReadinessValidationError as exc:
        raise PromotionReviewReadinessArtifactSetError(str(exc)) from exc
    _validate_manifest(manifest, raw_report_generated_at, report_bytes)
    return validated_report


def _validate_manifest(manifest: Any, report_generated_at: Any, report_bytes: bytes) -> None:
    if not isinstance(manifest, dict):
        raise PromotionReviewReadinessArtifactSetError("promotion readiness manifest must be an object")
    _reject_unknown_or_missing(manifest, MANIFEST_FIELDS, "promotion readiness manifest")
    if manifest["reportType"] != ARTIFACT_SET_REPORT_TYPE:
        raise PromotionReviewReadinessArtifactSetError("promotion readiness manifest reportType unsupported")
    if manifest["artifactSetVersion"] != ARTIFACT_SET_VERSION:
        raise PromotionReviewReadinessArtifactSetError("promotion readiness manifest artifactSetVersion unsupported")
    try:
        normalize_rfc3339_timestamp(manifest["generatedAt"], "promotion readiness manifest generatedAt")
    except TimestampContractError as exc:
        raise PromotionReviewReadinessArtifactSetError(str(exc)) from exc
    if manifest["generatedAt"] != report_generated_at:
        raise PromotionReviewReadinessArtifactSetError("promotion readiness manifest generatedAt must match report generatedAt")
    files = manifest["files"]
    if not isinstance(files, list) or len(files) != 1:
        raise PromotionReviewReadinessArtifactSetError("promotion readiness manifest must list exactly the current report")
    file_entry = files[0]
    if not isinstance(file_entry, dict):
        raise PromotionReviewReadinessArtifactSetError("promotion readiness manifest file entry must be an object")
    _reject_unknown_or_missing(file_entry, FILE_ENTRY_FIELDS, "promotion readiness manifest file entry")
    if file_entry["path"] != REPORT_FILENAME:
        raise PromotionReviewReadinessArtifactSetError("promotion readiness manifest lists unsupported artifact")
    size_bytes = file_entry["sizeBytes"]
    if isinstance(size_bytes, bool) or not isinstance(size_bytes, int) or size_bytes < 0:
        raise PromotionReviewReadinessArtifactSetError("promotion readiness manifest sizeBytes must be a non-negative integer")
    sha256 = file_entry["sha256"]
    if not isinstance(sha256, str) or len(sha256) != SHA256_HEX_LENGTH or not all(c in "0123456789abcdef" for c in sha256):
        raise PromotionReviewReadinessArtifactSetError("promotion readiness manifest sha256 must be lowercase hex")
    if size_bytes != len(report_bytes):
        raise PromotionReviewReadinessArtifactSetError("promotion readiness report size does not match manifest")
    if sha256 != hashlib.sha256(report_bytes).hexdigest():
        raise PromotionReviewReadinessArtifactSetError("promotion readiness report sha256 does not match manifest")


def _read_required_bytes(path: Path, label: str, max_bytes: int) -> bytes:
    _reject_symlink_path(path)
    if not path.is_file():
        raise PromotionReviewReadinessArtifactSetError(f"{label} is missing")
    flags = os.O_RDONLY | getattr(os, "O_BINARY", 0) | getattr(os, "O_NOFOLLOW", 0)
    try:
        fd = os.open(path, flags)
    except OSError as exc:
        raise PromotionReviewReadinessArtifactSetError(f"{label} is missing") from exc
    try:
        if not stat.S_ISREG(os.fstat(fd).st_mode):
            raise PromotionReviewReadinessArtifactSetError(f"{label} must be a file")
        with os.fdopen(fd, "rb") as handle:
            fd = -1
            payload = handle.read(max_bytes + 1)
    finally:
        if fd >= 0:
            os.close(fd)
    if len(payload) > max_bytes:
        raise PromotionReviewReadinessArtifactSetError(f"{label} exceeds maximum byte size")
    return payload


def _reject_symlink_path(path: Path) -> None:
    if path.is_symlink():
        raise PromotionReviewReadinessArtifactSetError("artifact must not be a symlink")
    parent = path.parent
    while parent != parent.parent:
        if parent.is_symlink():
            raise PromotionReviewReadinessArtifactSetError("artifact parent must not be a symlink")
        parent = parent.parent


def _require_canonical_filename(path: Path, expected: str, label: str, allow_temporary_names: bool = False) -> None:
    if path.name != expected and not (allow_temporary_names and path.name == f"{expected}.tmp"):
        raise PromotionReviewReadinessArtifactSetError(f"{label} filename must be {expected}")


def _reject_unknown_or_missing(raw: dict[str, Any], allowed: set[str], label: str) -> None:
    extra = sorted(set(raw) - allowed)
    if extra:
        raise PromotionReviewReadinessArtifactSetError(f"{label} contains unsupported fields: {', '.join(extra)}")
    missing = sorted(allowed - set(raw))
    if missing:
        raise PromotionReviewReadinessArtifactSetError(f"{label} missing required fields: {', '.join(missing)}")
