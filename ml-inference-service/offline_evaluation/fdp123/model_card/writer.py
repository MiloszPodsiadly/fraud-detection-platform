from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
from typing import Any

from offline_evaluation.fdp123.model_card.schema import (
    ARTIFACT_SET_VERSION,
    MODEL_CARD_REPORT_TYPE,
    SAFE_CONTRACT_VALUES,
    SAFE_NEGATED_MACHINE_CODES,
    Fdp123ModelCardValidationError,
    validate_model_card,
)


FORBIDDEN_OUTPUT_TERMS = {
    "evaluationrecordid",
    "transactionreference",
    "transactionid",
    "feedbackid",
    "customerid",
    "correlationid",
    "createdby",
    "notes",
    "rawnotes",
    "rawpayload",
    "rawmlrequest",
    "rawmlresponse",
    "rawfeaturevector",
    "rawevidence",
    "decisionreasoncodes",
    "groundtruth",
    "traininglabel",
    "finaldecision",
    "paymentdecision",
    "paymentauthorization",
    "promotionrecommended",
    "thresholdrecommendation",
    "productionready",
    "certifiedforproduction",
    "token",
    "secret",
    "password",
}


def model_card_json(model_card: dict[str, Any]) -> str:
    safe_model_card = validate_model_card(model_card)
    payload = json.dumps(safe_model_card, sort_keys=True, separators=(",", ":"))
    _reject_forbidden_output(payload)
    return payload + "\n"


def model_card_markdown(model_card: dict[str, Any]) -> str:
    safe = validate_model_card(model_card)
    evidence = safe["evaluationEvidence"]
    metrics = safe["metricsSummary"]
    lines = [
        f"# Model Card - {safe['modelName']}",
        "",
        "## Model identity",
        "",
        f"- modelVersion: {safe['modelVersion']}",
        f"- modelFamily: {safe['modelFamily']}",
        f"- trainingMode: {safe['trainingMode']}",
        f"- featureContractVersion: {safe['featureContractVersion']}",
        f"- referenceQuality: {safe['referenceQuality']}",
        f"- productionApproval: {safe['productionApproval']}",
        f"- promotionStatus: {safe['promotionStatus']}",
        "",
        "## Allowed usage modes",
        "",
        _bullets(safe["allowedUsageModes"]),
        "",
        "## Intended use",
        "",
        _bullets(safe["intendedUse"]),
        "",
        "## Not intended use",
        "",
        _bullets(safe["notIntendedUse"]),
        "",
        "## Evaluation evidence",
        "",
        f"- evaluationReportType: {evidence['evaluationReportType']}",
        f"- evaluationGeneratedAt: {evidence['evaluationGeneratedAt']}",
        f"- evaluationArtifactSetVersion: {evidence['evaluationArtifactSetVersion']}",
        f"- datasetVersion: {evidence['datasetVersion']}",
        f"- datasetTimeBasis: {evidence['datasetTimeBasis']}",
        f"- recordsEvaluated: {evidence['recordsEvaluated']}",
        f"- positiveClassCount: {evidence['positiveClassCount']}",
        f"- negativeClassCount: {evidence['negativeClassCount']}",
        f"- sourceManifestSha256: {evidence['sourceManifestSha256']}",
        "",
        "## Metrics summary",
        "",
        f"- alertRecommendedPrecision: {_metric_text(metrics['alertRecommendedPrecision'])}",
        f"- alertRecommendedRecall: {_metric_text(metrics['alertRecommendedRecall'])}",
        f"- falsePositiveRate: {_metric_text(metrics['falsePositiveRate'])}",
        f"- falseNegativeRate: {_metric_text(metrics['falseNegativeRate'])}",
        "",
        "## Warnings",
        "",
        _bullets(safe["warnings"]) if safe["warnings"] else "- none",
        "",
        "## Limitations",
        "",
        _bullets(safe["limitations"]),
        "",
        "## Governance boundary",
        "",
        _bullets(safe["governanceBoundary"]),
        "",
        "This model card does not approve model promotion.",
        "This model card does not approve production decisioning.",
        "This model card does not recommend threshold changes.",
        "This model card does not authorize payments.",
        "This model card does not trigger workflow or case automation.",
        "",
    ]
    payload = "\n".join(lines)
    _reject_forbidden_output(payload)
    return payload


def write_model_card_artifacts(
        model_card: dict[str, Any],
        output_dir: Path,
        allow_output_root: Path | None = None,
) -> dict[str, Path]:
    output_dir = Path(output_dir)
    _prepare_output_dir(output_dir, allow_output_root)
    paths = {
        "modelCardJson": output_dir / "model_card.json",
        "modelCardMarkdown": output_dir / "model_card.md",
    }
    payloads = {
        paths["modelCardJson"]: model_card_json(model_card),
        paths["modelCardMarkdown"]: model_card_markdown(model_card),
    }
    manifest_path = output_dir / "manifest.json"
    manifest_payload = build_model_card_manifest(payloads, validate_model_card(model_card)["generatedAt"])
    _write_artifacts_atomically(payloads, manifest_path, manifest_payload)
    paths["manifest"] = manifest_path
    return paths


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
        "artifactSetVersion": ARTIFACT_SET_VERSION,
        "files": files,
        "generatedAt": generated_at,
        "reportType": MODEL_CARD_REPORT_TYPE,
    }
    payload = json.dumps(manifest, sort_keys=True, separators=(",", ":"))
    _reject_forbidden_output(payload)
    return payload + "\n"


def _prepare_output_dir(output_dir: Path, allow_output_root: Path | None) -> None:
    if output_dir.exists():
        if output_dir.is_symlink():
            raise Fdp123ModelCardValidationError("output directory must not be a symlink")
        if not output_dir.is_dir():
            raise Fdp123ModelCardValidationError("output path exists and is not a directory")
    if allow_output_root is not None:
        resolved_output = output_dir.resolve()
        resolved_root = Path(allow_output_root).resolve()
        if resolved_output != resolved_root and resolved_root not in resolved_output.parents:
            raise Fdp123ModelCardValidationError("output directory is outside allowed output root")
    output_dir.mkdir(parents=True, exist_ok=True)
    if output_dir.is_symlink():
        raise Fdp123ModelCardValidationError("output directory must not be a symlink")


def _write_artifacts_atomically(payloads: dict[Path, str], manifest_path: Path, manifest_payload: str) -> None:
    temporary_paths = [path.with_name(path.name + ".tmp") for path in payloads]
    manifest_tmp_path = manifest_path.with_name(manifest_path.name + ".tmp")
    temporary_paths.append(manifest_tmp_path)
    try:
        for final_path in tuple(payloads) + (manifest_path,):
            if final_path.is_symlink():
                raise Fdp123ModelCardValidationError(f"final artifact path must not be a symlink: {final_path.name}")
        for final_path, payload in payloads.items():
            tmp_path = final_path.with_name(final_path.name + ".tmp")
            if tmp_path.exists() or tmp_path.is_symlink():
                tmp_path.unlink()
            tmp_path.write_text(payload, encoding="utf-8", newline="\n")
        if manifest_tmp_path.exists() or manifest_tmp_path.is_symlink():
            manifest_tmp_path.unlink()
        manifest_tmp_path.write_text(manifest_payload, encoding="utf-8", newline="\n")
        for final_path in payloads:
            tmp_path = final_path.with_name(final_path.name + ".tmp")
            os.replace(tmp_path, final_path)
        os.replace(manifest_tmp_path, manifest_path)
    except Exception:
        for tmp_path in temporary_paths:
            if tmp_path.exists() or tmp_path.is_symlink():
                tmp_path.unlink()
        raise


def _metric_text(metric: dict[str, Any]) -> str:
    if metric["available"]:
        return f"{metric['value']:.6f}".rstrip("0").rstrip(".")
    return f"unavailable ({metric['reason']})"


def _bullets(values: list[str]) -> str:
    return "\n".join(f"- {value}" for value in values)


def _reject_forbidden_output(payload: str) -> None:
    lowered = payload.lower()
    if "eval_" in lowered or "txnref_" in lowered or "eval-" in lowered or "txnref-" in lowered:
        raise Fdp123ModelCardValidationError("model card contains forbidden pseudonymous identifier prefix")
    masked = payload
    for safe_value in sorted(SAFE_CONTRACT_VALUES | SAFE_NEGATED_MACHINE_CODES, key=len, reverse=True):
        masked = masked.replace(safe_value, "")
    compact_payload = _compact(masked)
    for forbidden in FORBIDDEN_OUTPUT_TERMS:
        if forbidden in compact_payload:
            raise Fdp123ModelCardValidationError(f"model card contains forbidden term: {forbidden}")


def _compact(value: str) -> str:
    return "".join(character for character in value.lower() if character.isalnum())

