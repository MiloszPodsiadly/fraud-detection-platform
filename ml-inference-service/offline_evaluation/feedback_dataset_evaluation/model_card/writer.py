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
        f"- Model name: `{subject['modelName']}`",
        f"- Model version: `{subject['modelVersion']}`",
        f"- Feature contract: `{subject['featureContractVersion']}`",
        f"- Artifact SHA-256: `{subject['modelArtifactSha256']}`",
        f"- Artifact verification: `{validated['modelArtifactVerification']['status']}`",
        "",
        "## Evaluation evidence",
        "",
        f"- Status: `{evidence['status']}`",
    ]
    if evidence["status"] == EVALUATION_AVAILABLE:
        summary = validated["evaluationSummary"]
        lines.extend([
            f"- Report type: `{evidence['reportType']}`",
            f"- Report generated at: `{evidence['reportGeneratedAt']}`",
            f"- Report and manifest integrity: `{evidence['integrityVerification']}`",
            f"- Source dataset verification: `{evidence['sourceDatasetVerificationLevel']}`",
            f"- Source dataset SHA-256: `{summary['sourceDataset']['sha256']}`",
            "",
            "### Population",
            "",
        ])
        for name, value in sorted(summary["population"].items()):
            lines.append(f"- {name}: `{value}`")
        lines.extend([
            "",
            "### Class balance",
            "",
            f"- Positive class count: `{summary['classBalance']['positiveClassCount']}`",
            f"- Negative class count: `{summary['classBalance']['negativeClassCount']}`",
            "",
            "### Metric availability",
            "",
        ])
        for name, metric in sorted(summary["supportedMetrics"].items()):
            reason = metric["reason"] if metric["reason"] is not None else "NONE"
            lines.append(f"- {name}: available=`{str(metric['available']).lower()}`, reason=`{reason}`")
        lines.extend(_machine_code_section("Warnings", summary["warnings"]))
        lines.extend(_machine_code_section("Limitations", summary["limitations"]))
    else:
        lines.append(f"- Reason: `{evidence['reason']}`")
    lines.extend([
        "",
        "## Verified usage boundary",
        "",
        f"- Allowed modes: `{', '.join(validated['verifiedUsageBoundary']['allowedModes'])}`",
        f"- Promotion state: `{validated['promotionState']}`",
        "- Runtime decision authority: `NONE`",
        "- Promotion authority: `NONE`",
        "- Threshold change authority: `NONE`",
        "- Payment authorization authority: `NONE`",
        "- Workflow authority: `NONE`",
        "",
    ])
    return "\n".join(lines)


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
    staging_dir = Path(tempfile.mkdtemp(prefix=f".{destination.name}.staging-", dir=destination.parent))
    try:
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
        if destination.exists():
            destination.rmdir()
        os.replace(staging_dir, destination)
    except Exception:
        _cleanup_staging_directory(staging_dir)
        raise
    return {
        "modelCardJson": destination / MODEL_CARD_FILENAME,
        "modelCardMarkdown": destination / MODEL_CARD_MARKDOWN_FILENAME,
        "manifest": destination / MODEL_CARD_MANIFEST_FILENAME,
    }


def _validate_destination(output_dir: Path, allow_output_root: Path | None) -> None:
    try:
        reject_symlink_path(output_dir, "model card output directory")
    except BoundedArtifactReadError as exc:
        raise ModelCardValidationError(str(exc)) from exc
    if output_dir.exists():
        if not output_dir.is_dir():
            raise ModelCardValidationError("model card output path must be a directory")
        if any(output_dir.iterdir()):
            raise ModelCardValidationError("model card output directory must be empty")
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


def _cleanup_staging_directory(staging_dir: Path) -> None:
    if staging_dir.is_symlink():
        raise RuntimeError("model card staging cleanup refused a symlink")
    if staging_dir.exists():
        shutil.rmtree(staging_dir)


def _machine_code_section(title: str, values: list[str]) -> list[str]:
    lines = ["", f"### {title}", ""]
    lines.extend(f"- `{value}`" for value in values)
    if not values:
        lines.append("- `NONE`")
    return lines
