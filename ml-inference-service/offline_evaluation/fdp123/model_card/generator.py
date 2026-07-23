from __future__ import annotations

import hashlib
import json
from pathlib import Path
from typing import Any

from offline_evaluation.fdp123.model_card.schema import (
    EXPECTED_DATASET_TIME_BASIS,
    EXPECTED_DATASET_VERSION,
    EXPECTED_EVALUATION_MANIFEST_FILENAME,
    EXPECTED_EVALUATION_REPORT_TYPE,
    EXPECTED_EVALUATION_SUMMARY_FILENAME,
    EXPECTED_SOURCE_ARTIFACT_SET_VERSION,
    MAX_EVALUATION_MANIFEST_BYTES,
    MAX_EVALUATION_SUMMARY_BYTES,
    MODEL_CARD_REPORT_TYPE,
    MODEL_CARD_VERSION,
    PRODUCTION_APPROVAL,
    PROMOTION_STATUS,
    SAFE_CONTRACT_VALUES,
    SAFE_NEGATED_MACHINE_CODES,
    Fdp123ModelCardValidationError,
    normalize_rfc3339_timestamp,
    validate_class_count_integrity,
    validate_model_card,
)


REQUIRED_MODEL_METADATA_FIELDS = {
    "modelName",
    "modelVersion",
    "modelFamily",
    "trainingMode",
    "featureContractVersion",
    "referenceQuality",
    "allowedUsageModes",
    "intendedUse",
    "notIntendedUse",
    "limitations",
    "governanceBoundary",
}
ALLOWED_MODEL_METADATA_FIELDS = set(REQUIRED_MODEL_METADATA_FIELDS)

REQUIRED_SUMMARY_FIELDS = {
    "datasetMetadata",
    "generatedAt",
    "qualityMetrics",
    "reportType",
    "warnings",
}
REQUIRED_QUALITY_METRIC_FIELDS = {
    "datasetSummary",
    "classBalance",
    "alertRecommendedConfusionMatrix",
}
REQUIRED_ALERT_MATRIX_FIELDS = {
    "precision",
    "recall",
    "falsePositiveRate",
    "falseNegativeRate",
}
FORBIDDEN_INPUT_COMPACT_TERMS = {
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
    "decisionreasoncodes",
}


def generate_model_card_from_fdp124_artifacts(
        evaluation_summary_path: Path,
        evaluation_manifest_path: Path,
        model_metadata: dict[str, Any],
        generated_at: str,
) -> dict[str, Any]:
    summary_path = Path(evaluation_summary_path)
    manifest_path = Path(evaluation_manifest_path)
    _require_canonical_filename(summary_path, EXPECTED_EVALUATION_SUMMARY_FILENAME, "evaluation summary")
    _require_canonical_filename(manifest_path, EXPECTED_EVALUATION_MANIFEST_FILENAME, "manifest")
    manifest_bytes = _read_required_bytes(manifest_path, "manifest", MAX_EVALUATION_MANIFEST_BYTES)
    summary_bytes = _read_required_bytes(summary_path, "evaluation_summary", MAX_EVALUATION_SUMMARY_BYTES)
    manifest = _load_json_bytes(manifest_bytes, "manifest")
    _validate_manifest(manifest, summary_bytes)
    summary = _load_json_bytes(summary_bytes, "evaluation_summary")
    _reject_forbidden_input(summary)
    _validate_summary(summary)
    metadata = _model_metadata(model_metadata)

    quality_metrics = summary["qualityMetrics"]
    dataset_summary = quality_metrics["datasetSummary"]
    class_balance = quality_metrics["classBalance"]
    dataset_metadata = summary["datasetMetadata"]
    alert_matrix = quality_metrics["alertRecommendedConfusionMatrix"]

    warnings = _warnings(summary)
    model_card = {
        "modelCardVersion": MODEL_CARD_VERSION,
        "cardType": MODEL_CARD_REPORT_TYPE,
        "generatedAt": generated_at,
        "modelName": metadata["modelName"],
        "modelVersion": metadata["modelVersion"],
        "modelFamily": metadata["modelFamily"],
        "trainingMode": metadata["trainingMode"],
        "featureContractVersion": metadata["featureContractVersion"],
        "referenceQuality": metadata["referenceQuality"],
        "allowedUsageModes": metadata["allowedUsageModes"],
        "productionApproval": PRODUCTION_APPROVAL,
        "promotionStatus": PROMOTION_STATUS,
        "intendedUse": metadata["intendedUse"],
        "notIntendedUse": metadata["notIntendedUse"],
        "evaluationEvidence": {
            "evaluationReportType": summary["reportType"],
            "evaluationGeneratedAt": normalize_rfc3339_timestamp(summary["generatedAt"], "evaluation summary generatedAt"),
            "evaluationArtifactSetVersion": manifest["artifactSetVersion"],
            "datasetVersion": dataset_metadata["datasetVersion"],
            "datasetTimeBasis": dataset_metadata["timeBasis"],
            "recordsEvaluated": dataset_summary["recordsEvaluated"],
            "positiveClassCount": class_balance["positiveClassCount"],
            "negativeClassCount": class_balance["negativeClassCount"],
            "warnings": warnings,
            "sourceManifestSha256": hashlib.sha256(manifest_bytes).hexdigest(),
        },
        "metricsSummary": {
            "alertRecommendedPrecision": alert_matrix["precision"],
            "alertRecommendedRecall": alert_matrix["recall"],
            "falsePositiveRate": alert_matrix["falsePositiveRate"],
            "falseNegativeRate": alert_matrix["falseNegativeRate"],
        },
        "warnings": warnings,
        "limitations": metadata["limitations"],
        "governanceBoundary": metadata["governanceBoundary"],
    }
    return validate_model_card(model_card)


def _validate_manifest(manifest: dict[str, Any], summary_bytes: bytes) -> None:
    _reject_forbidden_input(manifest)
    if manifest.get("reportType") != EXPECTED_EVALUATION_REPORT_TYPE:
        raise Fdp123ModelCardValidationError("manifest reportType unsupported")
    if manifest.get("artifactSetVersion") != EXPECTED_SOURCE_ARTIFACT_SET_VERSION:
        raise Fdp123ModelCardValidationError("manifest artifactSetVersion unsupported")
    files = manifest.get("files")
    if not isinstance(files, list) or not files:
        raise Fdp123ModelCardValidationError("manifest files must be a non-empty list")
    matching = [item for item in files if isinstance(item, dict) and item.get("name") == EXPECTED_EVALUATION_SUMMARY_FILENAME]
    if len(matching) != 1:
        raise Fdp123ModelCardValidationError("manifest must list evaluation_summary.json")
    summary_entry = matching[0]
    expected_sha = hashlib.sha256(summary_bytes).hexdigest()
    if summary_entry.get("sha256") != expected_sha:
        raise Fdp123ModelCardValidationError("evaluation_summary.json hash mismatch")
    if summary_entry.get("sizeBytes") != len(summary_bytes):
        raise Fdp123ModelCardValidationError("evaluation_summary.json size mismatch")


def _validate_summary(summary: dict[str, Any]) -> None:
    extra_missing = sorted(REQUIRED_SUMMARY_FIELDS - set(summary))
    if extra_missing:
        raise Fdp123ModelCardValidationError(f"evaluation summary missing required fields: {', '.join(extra_missing)}")
    if summary.get("reportType") != EXPECTED_EVALUATION_REPORT_TYPE:
        raise Fdp123ModelCardValidationError("evaluation summary reportType unsupported")
    normalize_rfc3339_timestamp(summary.get("generatedAt"), "evaluation summary generatedAt")
    dataset_metadata = _required_object(summary, "datasetMetadata")
    if dataset_metadata.get("datasetVersion") != EXPECTED_DATASET_VERSION:
        raise Fdp123ModelCardValidationError("datasetVersion unsupported")
    if dataset_metadata.get("timeBasis") != EXPECTED_DATASET_TIME_BASIS:
        raise Fdp123ModelCardValidationError("dataset time basis unsupported")
    quality_metrics = _required_object(summary, "qualityMetrics")
    missing_metrics = sorted(REQUIRED_QUALITY_METRIC_FIELDS - set(quality_metrics))
    if missing_metrics:
        raise Fdp123ModelCardValidationError(f"qualityMetrics missing required fields: {', '.join(missing_metrics)}")
    _required_object(quality_metrics, "datasetSummary")
    class_balance = _required_object(quality_metrics, "classBalance")
    _required_non_negative_int(class_balance, "positiveClassCount")
    _required_non_negative_int(class_balance, "negativeClassCount")
    matrix = _required_object(quality_metrics, "alertRecommendedConfusionMatrix")
    missing_matrix = sorted(REQUIRED_ALERT_MATRIX_FIELDS - set(matrix))
    if missing_matrix:
        raise Fdp123ModelCardValidationError(
            f"alertRecommendedConfusionMatrix missing required fields: {', '.join(missing_matrix)}"
        )
    for field in sorted(REQUIRED_ALERT_MATRIX_FIELDS):
        _metric_object(matrix, field)
    dataset_summary = _required_object(quality_metrics, "datasetSummary")
    records_evaluated = _required_non_negative_int(dataset_summary, "recordsEvaluated")
    validate_class_count_integrity(
        _required_non_negative_int(class_balance, "positiveClassCount"),
        _required_non_negative_int(class_balance, "negativeClassCount"),
        records_evaluated,
    )
    if not isinstance(summary.get("warnings"), list):
        raise Fdp123ModelCardValidationError("warnings must be a list")


def _metric_object(raw: dict[str, Any], field: str) -> None:
    value = raw.get(field)
    if not isinstance(value, dict):
        raise Fdp123ModelCardValidationError(f"{field} must be a metric object")
    if set(value) != {"available", "reason", "value"}:
        raise Fdp123ModelCardValidationError(f"{field} metric shape unsupported")
    available = value["available"]
    metric_value = value["value"]
    reason = value["reason"]
    if not isinstance(available, bool):
        raise Fdp123ModelCardValidationError(f"{field}.available must be boolean")
    if available:
        if isinstance(metric_value, bool) or not isinstance(metric_value, (int, float)):
            raise Fdp123ModelCardValidationError(f"{field}.value must be numeric")
        if metric_value < 0.0 or metric_value > 1.0:
            raise Fdp123ModelCardValidationError(f"{field}.value out of range")
        if reason is not None:
            raise Fdp123ModelCardValidationError(f"{field}.reason must be null when available")
    else:
        if metric_value is not None:
            raise Fdp123ModelCardValidationError(f"{field}.value must be null when unavailable")
        if not isinstance(reason, str) or not reason:
            raise Fdp123ModelCardValidationError(f"{field}.reason required when unavailable")


def _model_metadata(raw: dict[str, Any]) -> dict[str, Any]:
    if not isinstance(raw, dict):
        raise Fdp123ModelCardValidationError("model metadata must be an object")
    _reject_forbidden_input(raw)
    extra = sorted(set(raw) - ALLOWED_MODEL_METADATA_FIELDS)
    if extra:
        raise Fdp123ModelCardValidationError(f"model metadata contains unsupported fields: {', '.join(extra)}")
    missing = sorted(REQUIRED_MODEL_METADATA_FIELDS - set(raw))
    if missing:
        raise Fdp123ModelCardValidationError(f"model metadata missing required fields: {', '.join(missing)}")
    return dict(raw)


def _warnings(summary: dict[str, Any]) -> list[str]:
    value = summary.get("warnings", [])
    if value is None:
        return []
    if not isinstance(value, list):
        raise Fdp123ModelCardValidationError("warnings must be a list")
    if not all(isinstance(item, str) for item in value):
        raise Fdp123ModelCardValidationError("warnings must contain strings")
    return sorted(value)


def _required_object(raw: dict[str, Any], field: str) -> dict[str, Any]:
    value = raw.get(field)
    if not isinstance(value, dict):
        raise Fdp123ModelCardValidationError(f"{field} must be an object")
    return value


def _required_non_negative_int(raw: dict[str, Any], field: str) -> int:
    value = raw.get(field)
    if isinstance(value, bool) or not isinstance(value, int) or value < 0:
        raise Fdp123ModelCardValidationError(f"{field} must be a non-negative integer")
    return value


def _require_canonical_filename(path: Path, expected_name: str, label: str) -> None:
    if path.name != expected_name:
        raise Fdp123ModelCardValidationError(f"{label} filename must be {expected_name}")


def _read_required_bytes(path: Path, label: str, max_bytes: int) -> bytes:
    if not path.exists():
        raise Fdp123ModelCardValidationError(f"{label} missing")
    if not path.is_file():
        raise Fdp123ModelCardValidationError(f"{label} must be a file")
    size = path.stat().st_size
    if size > max_bytes:
        raise Fdp123ModelCardValidationError(f"{label} exceeds maximum byte size")
    return path.read_bytes()


def _load_json_bytes(payload: bytes, label: str) -> dict[str, Any]:
    try:
        value = json.loads(payload.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as exception:
        raise Fdp123ModelCardValidationError(f"{label} must be valid JSON") from exception
    if not isinstance(value, dict):
        raise Fdp123ModelCardValidationError(f"{label} must be a JSON object")
    return value


def _reject_forbidden_input(value: Any) -> None:
    if isinstance(value, dict):
        for key, nested in value.items():
            compact_key = _compact(str(key))
            if compact_key in FORBIDDEN_INPUT_COMPACT_TERMS:
                raise Fdp123ModelCardValidationError(f"forbidden input field: {key}")
            _reject_forbidden_input(nested)
    elif isinstance(value, list):
        for item in value:
            _reject_forbidden_input(item)
    elif isinstance(value, str):
        if value in SAFE_CONTRACT_VALUES or value in SAFE_NEGATED_MACHINE_CODES:
            return
        compact = _compact(value)
        for term in FORBIDDEN_INPUT_COMPACT_TERMS:
            if term in compact:
                raise Fdp123ModelCardValidationError(f"forbidden input value: {value}")


def _compact(value: str) -> str:
    return "".join(character for character in value.lower() if character.isalnum())
