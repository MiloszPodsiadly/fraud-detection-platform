from __future__ import annotations

import os
import shutil
import tempfile
from contextlib import contextmanager
from pathlib import Path
from typing import Any

from offline_evaluation.feedback_dataset_evaluation.artifact_integrity import (
    BoundedArtifactReadError,
    reject_symlink_path,
)
from offline_evaluation.feedback_dataset_evaluation.exact_model_shadow_performance.artifact_set import (
    MANIFEST_FILENAME,
    SUMMARY_FILENAME,
    build_manifest,
    canonical_summary_json,
    read_exact_model_shadow_artifact_set,
)
from offline_evaluation.feedback_dataset_evaluation.exact_model_shadow_performance.schema import (
    ExactModelShadowPerformanceValidationError,
    exact_report_selection_key,
    validate_exact_model_shadow_performance,
)
from offline_evaluation.feedback_dataset_evaluation.exact_model_shadow_performance.reader_access import (
    apply_artifact_reader_access,
    apply_selection_reader_access,
    verify_artifact_reader_access,
    verify_artifact_root_access,
    verify_selection_reader_access,
)
from offline_evaluation.feedback_dataset_evaluation.model_card.atomic_directory_publish import (
    AtomicDirectoryPublicationUnsupported,
    publish_directory_without_replacement,
)


class ExactModelShadowPublicationDurabilityUnconfirmed(ExactModelShadowPerformanceValidationError):
    """The publisher cannot confirm the required directory durability boundary."""


def publish_exact_model_shadow_artifact_set(
        summary: dict[str, Any],
        artifact_root: str | Path,
) -> dict[str, Path]:
    validated = validate_exact_model_shadow_performance(summary)
    root = Path(artifact_root)
    _validate_root(root)
    selection_key = exact_report_selection_key(
        validated["evaluationSummary"]["evaluationSubject"],
        validated["evaluationSummary"]["evaluationWindow"],
    )
    destination = root / selection_key
    summary_payload = canonical_summary_json(validated)
    summary_bytes = summary_payload.encode("utf-8")
    manifest_payload = build_manifest(summary_bytes, validated, selection_key)
    if destination.exists() or destination.is_symlink():
        return _idempotent_existing(destination, summary_bytes)

    staging = Path(tempfile.mkdtemp(prefix=f".{selection_key}.staging-", dir=root))
    identity = os.stat(staging, follow_symlinks=False)
    published = False
    try:
        _write_and_sync(staging / SUMMARY_FILENAME, summary_payload)
        _write_and_sync(staging / MANIFEST_FILENAME, manifest_payload)
        read_exact_model_shadow_artifact_set(staging)
        apply_artifact_reader_access(staging / SUMMARY_FILENAME)
        apply_artifact_reader_access(staging / MANIFEST_FILENAME)
        apply_selection_reader_access(staging)
        _confirm_directory_durability(staging)
        try:
            publish_directory_without_replacement(staging, destination)
            published = True
        except FileExistsError:
            return _idempotent_existing(destination, summary_bytes)
        except AtomicDirectoryPublicationUnsupported as exc:
            raise ExactModelShadowPerformanceValidationError(
                "atomic no-replace exact-model shadow publication is unsupported"
            ) from exc
        _confirm_parent_durability(destination)
    finally:
        if not published and staging.exists() and _same_identity(staging, identity):
            shutil.rmtree(staging)
    return _paths(destination)


def _idempotent_existing(destination: Path, expected_summary: bytes) -> dict[str, Path]:
    verify_selection_reader_access(destination)
    verify_artifact_reader_access(destination / SUMMARY_FILENAME)
    verify_artifact_reader_access(destination / MANIFEST_FILENAME)
    evidence = read_exact_model_shadow_artifact_set(destination)
    actual = canonical_summary_json(_thaw(evidence.summary)).encode("utf-8")
    if actual != expected_summary:
        raise ExactModelShadowPerformanceValidationError(
            "immutable exact-model shadow selection already contains different evidence"
        )
    _confirm_parent_durability(destination)
    return _paths(destination)


def _confirm_parent_durability(destination: Path) -> None:
    _confirm_directory_durability(destination.parent)


def _confirm_directory_durability(directory: Path) -> None:
    try:
        _sync_directory(directory)
    except OSError as exc:
        raise ExactModelShadowPublicationDurabilityUnconfirmed(
            "exact-model shadow publication durability is unconfirmed"
        ) from exc


def _thaw(value: Any) -> Any:
    if isinstance(value, dict) or hasattr(value, "items"):
        return {key: _thaw(nested) for key, nested in value.items()}
    if isinstance(value, (tuple, list)):
        return [_thaw(item) for item in value]
    return value


def _validate_root(root: Path) -> None:
    try:
        reject_symlink_path(root, "exact-model shadow artifact root")
    except BoundedArtifactReadError as exc:
        raise ExactModelShadowPerformanceValidationError(str(exc)) from exc
    if not root.is_dir():
        raise ExactModelShadowPerformanceValidationError(
            "exact-model shadow artifact root must be pre-provisioned"
        )
    verify_artifact_root_access(root)


def _write_and_sync(path: Path, payload: str) -> None:
    with path.open("w", encoding="utf-8", newline="\n") as handle:
        handle.write(payload)
        handle.flush()
        os.fsync(handle.fileno())


def _sync_directory(path: Path) -> None:
    if os.name == "nt":
        raise OSError("directory durability confirmation is unavailable on Windows")
    flags = os.O_RDONLY | getattr(os, "O_DIRECTORY", 0) | getattr(os, "O_CLOEXEC", 0)
    with _directory_descriptor(path, flags) as descriptor:
        os.fsync(descriptor)


@contextmanager
def _directory_descriptor(path: Path, flags: int):
    descriptor = os.open(path, flags)
    try:
        yield descriptor
    finally:
        os.close(descriptor)


def _same_identity(path: Path, expected: os.stat_result) -> bool:
    try:
        return os.path.samestat(expected, os.stat(path, follow_symlinks=False))
    except FileNotFoundError:
        return False


def _paths(destination: Path) -> dict[str, Path]:
    return {
        "directory": destination,
        "summary": destination / SUMMARY_FILENAME,
        "manifest": destination / MANIFEST_FILENAME,
    }

