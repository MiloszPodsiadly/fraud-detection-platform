from __future__ import annotations

import hashlib
import os
import shutil
import tempfile
from pathlib import Path
from typing import Any

from offline_evaluation.feedback_dataset_evaluation.artifact_integrity import (
    BoundedArtifactReadError,
    reject_symlink_path,
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
    lock_path, lock_descriptor = _acquire_publication_lock(destination)
    staging_dir: Path | None = None
    try:
        if destination.exists() or destination.is_symlink():
            raise ModelCardValidationError("model card artifact version already exists and is immutable")
        staging_dir = Path(tempfile.mkdtemp(prefix=f".{destination.name}.staging-", dir=destination.parent))
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
    except Exception:
        if staging_dir is not None:
            _cleanup_staging_directory(staging_dir)
        raise
    finally:
        _release_publication_lock(lock_path, lock_descriptor)
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


def _acquire_publication_lock(destination: Path) -> tuple[Path, int]:
    lock_path = destination.parent / f".{destination.name}.publish.lock"
    flags = os.O_CREAT | os.O_EXCL | os.O_WRONLY | getattr(os, "O_BINARY", 0) | getattr(os, "O_NOFOLLOW", 0)
    try:
        descriptor = os.open(lock_path, flags, 0o600)
    except FileExistsError as exc:
        raise ModelCardValidationError("model card artifact version publication is already in progress") from exc
    except OSError as exc:
        raise ModelCardValidationError("model card publication lock cannot be created") from exc
    try:
        os.write(descriptor, b"model-card-publication-v1\n")
    except OSError as exc:
        os.close(descriptor)
        lock_path.unlink(missing_ok=True)
        raise ModelCardValidationError("model card publication lock cannot be written") from exc
    return lock_path, descriptor


def _release_publication_lock(lock_path: Path, descriptor: int) -> None:
    os.close(descriptor)
    try:
        lock_path.unlink()
    except FileNotFoundError:
        pass


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
        os.rename(staging_dir, destination)
    except FileExistsError as exc:
        raise ModelCardValidationError("model card artifact version already exists and is immutable") from exc
    except OSError as exc:
        raise ModelCardValidationError("model card artifact publication failed") from exc


def _cleanup_staging_directory(staging_dir: Path) -> None:
    if staging_dir.is_symlink():
        raise RuntimeError("model card staging cleanup refused a symlink")
    if staging_dir.exists():
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
