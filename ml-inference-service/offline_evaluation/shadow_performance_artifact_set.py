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
from offline_evaluation.shadow_performance_schema import (
    ShadowPerformanceValidationError,
    validate_shadow_performance_summary,
)


ARTIFACT_SET_REPORT_TYPE = "SHADOW_PERFORMANCE_ARTIFACT_SET_V1"
ARTIFACT_SET_VERSION = "shadow-performance-artifact-set-v1"
SUMMARY_FILENAME = "current-summary.json"
MANIFEST_FILENAME = "manifest.json"
MAX_SUMMARY_BYTES = 262_144
MAX_MANIFEST_BYTES = 65_536

MANIFEST_FIELDS = {"reportType", "artifactSetVersion", "generatedAt", "files"}
FILE_ENTRY_FIELDS = {"path", "sha256", "sizeBytes"}
SHA256_HEX_LENGTH = 64


class ShadowPerformanceArtifactSetError(ValueError):
    """Raised when the local Shadow Performance artifact set is incomplete or tampered."""


def build_shadow_performance_manifest(summary_payload: str) -> str:
    summary = validate_shadow_performance_summary(json.loads(summary_payload))
    summary_bytes = summary_payload.encode("utf-8")
    manifest = {
        "artifactSetVersion": ARTIFACT_SET_VERSION,
        "files": [
            {
                "path": SUMMARY_FILENAME,
                "sha256": hashlib.sha256(summary_bytes).hexdigest(),
                "sizeBytes": len(summary_bytes),
            }
        ],
        "generatedAt": summary["generatedAt"],
        "reportType": ARTIFACT_SET_REPORT_TYPE,
    }
    return json.dumps(manifest, sort_keys=True, separators=(",", ":")) + "\n"


def publish_shadow_performance_artifact_set(summary_payload: str, summary_path: Path) -> Path:
    final_summary = Path(summary_path)
    if final_summary.name != SUMMARY_FILENAME:
        raise ShadowPerformanceArtifactSetError("summary path must end with current-summary.json")
    final_manifest = final_summary.with_name(MANIFEST_FILENAME)
    summary_tmp = final_summary.with_name(f"{final_summary.name}.tmp")
    manifest_tmp = final_manifest.with_name(f"{final_manifest.name}.tmp")
    temporary_paths = [summary_tmp, manifest_tmp]
    try:
        for path in (final_summary, final_manifest, summary_tmp, manifest_tmp):
            if path.is_symlink():
                raise ShadowPerformanceArtifactSetError(f"artifact path must not be a symlink: {path.name}")
        summary_tmp.write_text(summary_payload, encoding="utf-8", newline="\n")
        manifest_tmp.write_text(build_shadow_performance_manifest(summary_payload), encoding="utf-8", newline="\n")
        read_validated_shadow_performance_artifact_set(summary_tmp, manifest_tmp, allow_temporary_names=True)
        if final_manifest.exists() or final_manifest.is_symlink():
            final_manifest.unlink()
        os.replace(summary_tmp, final_summary)
        os.replace(manifest_tmp, final_manifest)
    except Exception:
        for path in temporary_paths:
            if path.exists() or path.is_symlink():
                path.unlink()
        raise
    return final_summary


def read_validated_shadow_performance_artifact_set(
        summary_path: Path,
        manifest_path: Path,
        *,
        allow_temporary_names: bool = False,
) -> tuple[dict[str, Any], str]:
    summary_file = Path(summary_path)
    manifest_file = Path(manifest_path)
    _require_canonical_filename(summary_file, SUMMARY_FILENAME, "shadow summary", allow_temporary_names)
    _require_canonical_filename(manifest_file, MANIFEST_FILENAME, "shadow manifest", allow_temporary_names)
    manifest_bytes = _read_required_bytes(manifest_file, "shadow manifest", MAX_MANIFEST_BYTES)
    summary_bytes = _read_required_bytes(summary_file, "shadow summary", MAX_SUMMARY_BYTES)
    try:
        manifest = json.loads(manifest_bytes.decode("utf-8"))
        summary = json.loads(summary_bytes.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise ShadowPerformanceArtifactSetError("shadow artifact set contains malformed JSON") from exc
    validated_summary = validate_shadow_performance_summary(summary)
    _validate_manifest(manifest, validated_summary, summary_bytes)
    return validated_summary, hashlib.sha256(manifest_bytes).hexdigest()


def _validate_manifest(manifest: Any, summary: dict[str, Any], summary_bytes: bytes) -> None:
    if not isinstance(manifest, dict):
        raise ShadowPerformanceArtifactSetError("shadow manifest must be an object")
    _reject_unknown_or_missing(manifest, MANIFEST_FIELDS, "shadow manifest")
    if manifest["reportType"] != ARTIFACT_SET_REPORT_TYPE:
        raise ShadowPerformanceArtifactSetError("shadow manifest reportType unsupported")
    if manifest["artifactSetVersion"] != ARTIFACT_SET_VERSION:
        raise ShadowPerformanceArtifactSetError("shadow manifest artifactSetVersion unsupported")
    try:
        generated_at = normalize_rfc3339_timestamp(manifest["generatedAt"], "shadow manifest generatedAt")
    except TimestampContractError as exc:
        raise ShadowPerformanceArtifactSetError(str(exc)) from exc
    if generated_at != summary["generatedAt"]:
        raise ShadowPerformanceArtifactSetError("shadow manifest generatedAt must match summary generatedAt")
    files = manifest["files"]
    if not isinstance(files, list) or len(files) != 1:
        raise ShadowPerformanceArtifactSetError("shadow manifest must list exactly the current summary")
    file_entry = files[0]
    if not isinstance(file_entry, dict):
        raise ShadowPerformanceArtifactSetError("shadow manifest file entry must be an object")
    _reject_unknown_or_missing(file_entry, FILE_ENTRY_FIELDS, "shadow manifest file entry")
    if file_entry["path"] != SUMMARY_FILENAME:
        raise ShadowPerformanceArtifactSetError("shadow manifest lists unsupported artifact")
    size_bytes = file_entry["sizeBytes"]
    if isinstance(size_bytes, bool) or not isinstance(size_bytes, int) or size_bytes < 0:
        raise ShadowPerformanceArtifactSetError("shadow manifest sizeBytes must be a non-negative integer")
    sha256 = file_entry["sha256"]
    if not isinstance(sha256, str) or len(sha256) != SHA256_HEX_LENGTH or not all(c in "0123456789abcdef" for c in sha256):
        raise ShadowPerformanceArtifactSetError("shadow manifest sha256 must be lowercase hex")
    if size_bytes != len(summary_bytes):
        raise ShadowPerformanceArtifactSetError("shadow summary size does not match manifest")
    if sha256 != hashlib.sha256(summary_bytes).hexdigest():
        raise ShadowPerformanceArtifactSetError("shadow summary sha256 does not match manifest")


def _read_required_bytes(path: Path, label: str, max_bytes: int) -> bytes:
    _reject_symlink_path(path)
    if not path.is_file():
        raise ShadowPerformanceArtifactSetError(f"{label} is missing")
    flags = os.O_RDONLY | getattr(os, "O_BINARY", 0) | getattr(os, "O_NOFOLLOW", 0)
    try:
        fd = os.open(path, flags)
    except OSError as exc:
        raise ShadowPerformanceArtifactSetError(f"{label} is missing") from exc
    try:
        if not stat.S_ISREG(os.fstat(fd).st_mode):
            raise ShadowPerformanceArtifactSetError(f"{label} must be a file")
        with os.fdopen(fd, "rb") as handle:
            fd = -1
            payload = handle.read(max_bytes + 1)
    finally:
        if fd >= 0:
            os.close(fd)
    if len(payload) > max_bytes:
        raise ShadowPerformanceArtifactSetError(f"{label} exceeds maximum byte size")
    return payload


def _reject_symlink_path(path: Path) -> None:
    if path.is_symlink():
        raise ShadowPerformanceArtifactSetError("artifact must not be a symlink")
    parent = path.parent
    while parent != parent.parent:
        if parent.is_symlink():
            raise ShadowPerformanceArtifactSetError("artifact parent must not be a symlink")
        parent = parent.parent


def _require_canonical_filename(path: Path, expected: str, label: str, allow_temporary_names: bool = False) -> None:
    if path.name != expected and not (allow_temporary_names and path.name == f"{expected}.tmp"):
        raise ShadowPerformanceArtifactSetError(f"{label} filename must be {expected}")


def _reject_unknown_or_missing(raw: dict[str, Any], allowed: set[str], label: str) -> None:
    extra = sorted(set(raw) - allowed)
    if extra:
        raise ShadowPerformanceArtifactSetError(f"{label} contains unsupported fields: {', '.join(extra)}")
    missing = sorted(allowed - set(raw))
    if missing:
        raise ShadowPerformanceArtifactSetError(f"{label} missing required fields: {', '.join(missing)}")
