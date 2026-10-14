from __future__ import annotations

import hashlib
import os
import shutil
import socket
import tempfile
import uuid
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from offline_evaluation.feedback_dataset_evaluation.artifact_integrity import (
    BoundedArtifactReadError,
    reject_symlink_path,
)
from offline_evaluation.feedback_dataset_evaluation.model_card.atomic_directory_publish import (
    AtomicDirectoryPublicationUnsupported,
    publish_directory_without_replacement,
)
from offline_evaluation.feedback_dataset_evaluation.model_card.schema import (
    EVALUATION_AVAILABLE,
    MODEL_CARD_TYPE,
    ModelCardValidationError,
    validate_model_card,
)
from offline_evaluation.json_contract import dumps_strict_json


MODEL_CARD_ARTIFACT_SET_VERSION = "ml-model-card-artifact-set-v1"
MODEL_CARD_FILENAME = "ml_model_card.json"
MODEL_CARD_MARKDOWN_FILENAME = "ml_model_card.md"
MODEL_CARD_MANIFEST_FILENAME = "manifest.json"
MAX_MODEL_CARD_MARKDOWN_BYTES = 262_144


@dataclass(frozen=True)
class _PublicationLock:
    path: Path
    descriptor: int
    identity: os.stat_result


def model_card_json(card: dict[str, Any]) -> str:
    validated = validate_model_card(card)
    return dumps_strict_json(validated, sort_keys=True, separators=(",", ":")) + "\n"


def model_card_markdown(card: dict[str, Any]) -> str:
    validated = validate_model_card(card)
    subject = validated["subject"]
    evidence = validated["evaluationEvidence"]
    lines = [
        "# ML Model Card",
        "",
        "Exact-artifact offline diagnostic evidence. This card grants no production decision or promotion authority.",
        "",
        "## Model identity",
        "",
        f"- Model name: {_code(subject['modelName'])}",
        f"- Model version: {_code(subject['modelVersion'])}",
        f"- Feature contract: {_code(subject['featureContractVersion'])}",
        f"- Artifact SHA-256: {_code(subject['modelArtifactSha256'])}",
        f"- Artifact verification: {_code(validated['modelArtifactVerification']['status'])}",
        "",
        "## Evaluation evidence",
        "",
        f"- Status: {_code(evidence['status'])}",
    ]
    if evidence["status"] == EVALUATION_AVAILABLE:
        summary = validated["evaluationSummary"]
        lines.extend([
            f"- Report type: {_code(evidence['reportType'])}",
            f"- Report generated at: {_code(evidence['reportGeneratedAt'])}",
            f"- Report and manifest integrity: {_code(evidence['integrityVerification'])}",
            f"- Source dataset verification: {_code(evidence['sourceDatasetVerificationLevel'])}",
            "",
            "### Source dataset",
            "",
        ])
        source = summary["sourceDataset"]
        for label, field in (
            ("Dataset version", "datasetVersion"),
            ("SHA-256", "sha256"),
            ("Raw rows read", "rawRowsRead"),
            ("Records returned", "recordsReturned"),
            ("Unresolved rows excluded", "excludedUnresolvedCount"),
            ("Governance-review rows excluded", "excludedGovernanceReviewCount"),
            ("Missing-required-field rows skipped", "skippedMissingRequiredFieldCount"),
            ("Invalid source rows skipped", "skippedInvalidSourceRecordCount"),
            ("Truncated", "truncated"),
        ):
            lines.append(f"- {label}: {_code(source[field])}")
        lines.extend([
            "",
            "### Evaluation population",
            "",
        ])
        for name, value in sorted(summary["population"].items()):
            lines.append(f"- {name}: {_code(value)}")
        lines.extend([
            "",
            "### Class balance",
            "",
            f"- Positive class count: {_code(summary['classBalance']['positiveClassCount'])}",
            f"- Negative class count: {_code(summary['classBalance']['negativeClassCount'])}",
            "",
            "### Metric availability",
            "",
        ])
        for name, metric in sorted(summary["supportedMetrics"].items()):
            reason = metric["reason"] if metric["reason"] is not None else "NONE"
            lines.append(
                f"- {name}: available={_code(metric['available'])}, reason={_code(reason)}"
            )
        prediction = summary["supportedMetrics"]["mlPredictionMetrics"]
        lines.extend([
            "",
            "### Confusion matrix",
            "",
            f"- True positive: {_code(prediction['truePositive'])}",
            f"- False positive: {_code(prediction['falsePositive'])}",
            f"- True negative: {_code(prediction['trueNegative'])}",
            f"- False negative: {_code(prediction['falseNegative'])}",
            "",
            "### Prediction metrics",
            "",
        ])
        for label, field in (
            ("Precision", "precision"),
            ("Recall", "recall"),
            ("True-positive rate", "truePositiveRate"),
            ("False-positive rate", "falsePositiveRate"),
            ("False-negative rate", "falseNegativeRate"),
        ):
            lines.append(_metric_line(label, prediction[field]))
        lines.extend(_ranking_section("Precision at K", prediction["precisionAtK"]))
        lines.extend(_ranking_section("Recall at K", prediction["recallAtK"]))
        disagreement = summary["supportedMetrics"]["rulesVsMlDisagreement"]
        lines.extend([
            "",
            "### Rules-vs-ML disagreement",
            "",
            f"- Available: {_code(disagreement['available'])}",
            f"- Reason: {_code(disagreement['reason'] if disagreement['reason'] is not None else 'NONE')}",
            f"- Records with exact ML evidence: {_code(disagreement['recordsWithExactMlEvidence'])}",
            f"- Records compared: {_code(disagreement['recordsCompared'])}",
            f"- Records excluded because Rules evidence was unavailable: {_code(disagreement['recordsExcludedRulesEvidenceUnavailable'])}",
        ])
        for name, value in sorted(disagreement["categoryCounts"].items()):
            lines.append(f"- {name}: {_code(value)}")
        lines.extend(_machine_code_section("Warnings", summary["warnings"]))
        lines.extend(_machine_code_section("Limitations", summary["limitations"]))
    else:
        lines.append(f"- Reason: {_code(evidence['reason'])}")
    boundary = validated["verifiedUsageBoundary"]
    lines.extend([
        "",
        "## Verified usage boundary",
        "",
        f"- Allowed modes: {_code(', '.join(boundary['allowedModes']))}",
        f"- Promotion state: {_code(validated['promotionState'])}",
    ])
    lines.extend(_machine_code_section("Intended use", boundary["intendedUse"]))
    lines.extend(_machine_code_section("Not intended use", boundary["notIntendedUse"]))
    lines.extend(_machine_code_section("Use limitations", boundary["useLimitations"]))
    lines.extend([
        "",
        "- Runtime decision authority: `NONE`",
        "- Promotion authority: `NONE`",
        "- Threshold change authority: `NONE`",
        "- Payment authorization authority: `NONE`",
        "- Workflow authority: `NONE`",
        "",
    ])
    markdown = "\n".join(lines)
    if len(markdown.encode("utf-8")) > MAX_MODEL_CARD_MARKDOWN_BYTES:
        raise ModelCardValidationError("model card markdown exceeds maximum payload size")
    return markdown


def build_model_card_manifest(payloads: dict[Path, str], generated_at: str) -> str:
    files = []
    for path, payload in sorted(payloads.items(), key=lambda item: item[0].name):
        encoded = payload.encode("utf-8")
        files.append({
            "name": path.name,
            "sha256": hashlib.sha256(encoded).hexdigest(),
            "sizeBytes": len(encoded),
        })
    manifest = {
        "artifactSetVersion": MODEL_CARD_ARTIFACT_SET_VERSION,
        "files": files,
        "generatedAt": generated_at,
        "reportType": MODEL_CARD_TYPE,
    }
    return dumps_strict_json(manifest, sort_keys=True, separators=(",", ":")) + "\n"


def write_model_card_artifacts(
        card: dict[str, Any],
        output_dir: Path,
        allow_output_root: Path | None = None,
) -> dict[str, Path]:
    validated = validate_model_card(card)
    destination = Path(output_dir)
    _validate_destination(destination, allow_output_root)
    publication_lock = _acquire_publication_lock(destination)
    staging_dir: Path | None = None
    staging_identity: os.stat_result | None = None
    publication_error: Exception | None = None
    try:
        if destination.exists() or destination.is_symlink():
            raise ModelCardValidationError("model card artifact version already exists and is immutable")
        staging_dir = Path(tempfile.mkdtemp(prefix=f".{destination.name}.staging-", dir=destination.parent))
        staging_identity = os.stat(staging_dir, follow_symlinks=False)
        card_path = staging_dir / MODEL_CARD_FILENAME
        markdown_path = staging_dir / MODEL_CARD_MARKDOWN_FILENAME
        manifest_path = staging_dir / MODEL_CARD_MANIFEST_FILENAME
        payloads = {
            card_path: model_card_json(validated),
            markdown_path: model_card_markdown(validated),
        }
        for path, payload in payloads.items():
            path.write_text(payload, encoding="utf-8", newline="\n")
        manifest_path.write_text(
            build_model_card_manifest(payloads, validated["generatedAt"]),
            encoding="utf-8",
            newline="\n",
        )
        _validate_staging_directory(staging_dir)
        _publish_new_directory(staging_dir, destination)
        staging_dir = None
    except Exception as exc:
        publication_error = exc
        if staging_dir is not None:
            try:
                _cleanup_staging_directory(staging_dir, staging_identity)
            except Exception as cleanup_error:
                exc.add_note(f"owned staging cleanup also failed: {cleanup_error}")
        raise
    finally:
        try:
            _release_publication_lock(publication_lock)
        except Exception as lock_error:
            if publication_error is None:
                raise
            publication_error.add_note(f"owned publication lock release also failed: {lock_error}")
    return {
        "modelCardJson": destination / MODEL_CARD_FILENAME,
        "modelCardMarkdown": destination / MODEL_CARD_MARKDOWN_FILENAME,
        "manifest": destination / MODEL_CARD_MANIFEST_FILENAME,
    }


def _validate_destination(output_dir: Path, allow_output_root: Path | None) -> None:
    if any(part == ".." for part in output_dir.parts):
        raise ModelCardValidationError("model card output directory must not contain parent traversal")
    try:
        reject_symlink_path(output_dir, "model card output directory")
    except BoundedArtifactReadError as exc:
        raise ModelCardValidationError(str(exc)) from exc
    if output_dir.exists():
        raise ModelCardValidationError("model card artifact version already exists and is immutable")
    if allow_output_root is not None:
        resolved_output = output_dir.resolve()
        resolved_root = Path(allow_output_root).resolve()
        if resolved_output == resolved_root or resolved_root not in resolved_output.parents:
            raise ModelCardValidationError("model card output directory is outside the allowed output root")
    output_dir.parent.mkdir(parents=True, exist_ok=True)
    try:
        reject_symlink_path(output_dir.parent, "model card output parent")
    except BoundedArtifactReadError as exc:
        raise ModelCardValidationError(str(exc)) from exc


def _acquire_publication_lock(destination: Path) -> _PublicationLock:
    lock_path = destination.parent / f".{destination.name}.publish.lock"
    flags = os.O_CREAT | os.O_EXCL | os.O_WRONLY | getattr(os, "O_BINARY", 0) | getattr(os, "O_NOFOLLOW", 0)
    try:
        descriptor = os.open(lock_path, flags, 0o600)
    except FileExistsError as exc:
        raise ModelCardValidationError("model card artifact version publication is already in progress") from exc
    except OSError as exc:
        raise ModelCardValidationError("model card publication lock cannot be created") from exc
    try:
        identity = os.fstat(descriptor)
    except OSError as exc:
        os.close(descriptor)
        raise ModelCardValidationError(
            "model card publication lock identity cannot be established"
        ) from exc
    publication_lock = _PublicationLock(lock_path, descriptor, identity)
    payload = (
        dumps_strict_json(
            {
                "createdAt": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
                "format": "model-card-publication-lock-v1",
                "host": socket.gethostname(),
                "ownerToken": uuid.uuid4().hex,
                "pid": os.getpid(),
            },
            sort_keys=True,
            separators=(",", ":"),
        )
        + "\n"
    ).encode("utf-8")
    try:
        written = os.write(descriptor, payload)
        if written != len(payload):
            raise OSError("publication lock metadata was only partially written")
    except OSError as exc:
        try:
            _release_publication_lock(publication_lock)
        except Exception as cleanup_error:
            exc.add_note(f"failed publication lock cleanup also failed: {cleanup_error}")
        raise ModelCardValidationError("model card publication lock cannot be written") from exc
    return publication_lock


def _release_publication_lock(publication_lock: _PublicationLock) -> None:
    lock_path = publication_lock.path
    descriptor = publication_lock.descriptor
    if not _same_file_identity(lock_path, publication_lock.identity):
        os.close(descriptor)
        raise ModelCardValidationError(
            "model card publication lock ownership changed; refusing to remove foreign lock"
        )

    if os.name == "nt":
        os.close(descriptor)
        if not _same_file_identity(lock_path, publication_lock.identity):
            raise ModelCardValidationError(
                "model card publication lock ownership changed; refusing to remove foreign lock"
            )
        lock_path.unlink()
        return

    try:
        lock_path.unlink()
    finally:
        os.close(descriptor)


def _same_file_identity(path: Path, expected: os.stat_result) -> bool:
    try:
        current = os.stat(path, follow_symlinks=False)
    except FileNotFoundError:
        return False
    return os.path.samestat(expected, current)


def _validate_staging_directory(staging_dir: Path) -> None:
    # Local import avoids making the canonical reader depend on a second writer implementation.
    from offline_evaluation.feedback_dataset_evaluation.model_card.artifact_reader import (
        read_validated_model_card_artifact_set,
    )

    read_validated_model_card_artifact_set(staging_dir)


def _publish_new_directory(staging_dir: Path, destination: Path) -> None:
    if destination.exists() or destination.is_symlink():
        raise ModelCardValidationError("model card artifact version already exists and is immutable")
    try:
        publish_directory_without_replacement(staging_dir, destination)
    except FileExistsError as exc:
        raise ModelCardValidationError("model card artifact version already exists and is immutable") from exc
    except AtomicDirectoryPublicationUnsupported as exc:
        raise ModelCardValidationError(
            "atomic no-replace model card publication is unsupported on this filesystem"
        ) from exc
    except OSError as exc:
        raise ModelCardValidationError("model card artifact publication failed") from exc


def _cleanup_staging_directory(
        staging_dir: Path,
        expected_identity: os.stat_result | None,
) -> None:
    if expected_identity is None or not _same_file_identity(staging_dir, expected_identity):
        raise RuntimeError("model card staging cleanup refused a directory with changed ownership")
    shutil.rmtree(staging_dir)


def _machine_code_section(title: str, values: list[str]) -> list[str]:
    lines = ["", f"### {title}", ""]
    lines.extend(f"- {_code(value)}" for value in values)
    if not values:
        lines.append("- `NONE`")
    return lines


def _metric_line(label: str, metric: dict[str, Any]) -> str:
    reason = metric["reason"] if metric["reason"] is not None else "NONE"
    value = metric["value"] if metric["available"] else "UNAVAILABLE"
    return (
        f"- {label}: available={_code(metric['available'])}, "
        f"value={_code(value)}, reason={_code(reason)}"
    )


def _ranking_section(title: str, rows: dict[str, Any]) -> list[str]:
    lines = ["", f"### {title}", ""]
    for key in sorted(rows, key=int):
        row = rows[key]
        metric = row["value"]
        reason = metric["reason"] if metric["reason"] is not None else "NONE"
        value = metric["value"] if metric["available"] else "UNAVAILABLE"
        lines.append(
            f"- K {key}: requestedK={_code(row['requestedK'])}, actualK={_code(row['actualK'])}, "
            f"available={_code(metric['available'])}, value={_code(value)}, reason={_code(reason)}"
        )
    return lines


def _code(value: Any) -> str:
    if isinstance(value, bool):
        text = str(value).lower()
    else:
        text = str(value)
    if any(character in text for character in ("`", "\r", "\n")):
        raise ModelCardValidationError("model card markdown value contains unsupported control characters")
    return f"`{text}`"
