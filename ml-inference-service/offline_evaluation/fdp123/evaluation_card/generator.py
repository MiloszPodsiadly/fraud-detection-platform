from __future__ import annotations

import hashlib
import json
import os
import re
import stat
from pathlib import Path
from typing import Any

from offline_evaluation.json_contract import JsonContractError, loads_strict_json, require_finite_number
from offline_evaluation.fdp123.evaluation_card.schema import (
    EVALUATION_SUBJECT,
    EXPECTED_DATASET_TIME_BASIS,
    EXPECTED_DATASET_VERSION,
    EXPECTED_EVALUATION_MANIFEST_FILENAME,
    EXPECTED_EVALUATION_REPORT_TYPE,
    EXPECTED_EVALUATION_SUMMARY_FILENAME,
    EXPECTED_SOURCE_ARTIFACT_SET_VERSION,
    MAX_EVALUATION_MANIFEST_BYTES,
    MAX_EVALUATION_SUMMARY_BYTES,
    EVALUATION_PURPOSE,
    NO_AUTHORITY,
    PLATFORM_RECOMMENDATION_EVALUATION_CARD_REPORT_TYPE,
    PLATFORM_RECOMMENDATION_EVALUATION_CARD_VERSION,
    METRIC_BASIS,
    METRICS_SUBJECT,
    SAFE_CONTRACT_VALUES,
    SAFE_NEGATED_MACHINE_CODES,
    Fdp123EvaluationCardValidationError,
    normalize_rfc3339_timestamp,
    validate_class_count_integrity,
    validate_evaluation_card,
)
from offline_evaluation.fdp123.evaluation_card.safety_policy import FORBIDDEN_INPUT_COMPACT_TERMS


REQUIRED_GOVERNANCE_METADATA_FIELDS = {
    "allowedUsageModes",
    "intendedUse",
    "notIntendedUse",
    "limitations",
    "governanceBoundary",
}
ALLOWED_GOVERNANCE_METADATA_FIELDS = set(REQUIRED_GOVERNANCE_METADATA_FIELDS)

REQUIRED_SUMMARY_FIELDS = {
    "datasetMetadata",
    "evaluationSubject",
    "generatedAt",
    "metricBasis",
    "metricsSubject",
    "qualityMetrics",
    "reportType",
    "warnings",
}
ALLOWED_SUMMARY_FIELDS = set(REQUIRED_SUMMARY_FIELDS)
ALLOWED_SUMMARY_FIELDS.add("disagreementSummary")
REQUIRED_QUALITY_METRIC_FIELDS = {
    "datasetSummary",
    "classBalance",
    "alertRecommendedConfusionMatrix",
}
ALLOWED_QUALITY_METRIC_FIELDS = REQUIRED_QUALITY_METRIC_FIELDS | {
    "metricBasis",
    "riskLevelBreakdown",
    "fraudScoreBucketAnalysis",
    "precisionAtK",
    "recallAtK",
    "missingFraudScoreCount",
    "missingAlertRecommendedCount",
    "missingRiskLevelCount",
    "warnings",
}
DATASET_METADATA_FIELDS = {
    "builtAt",
    "datasetVersion",
    "excludedGovernanceReviewCount",
    "excludedUnresolvedCount",
    "failureReason",
    "fromInclusive",
    "rawRowsRead",
    "recordsReturned",
    "skippedInvalidSourceRecordCount",
    "skippedMissingRequiredFieldCount",
    "timeBasis",
    "toInclusive",
    "truncated",
}
DATASET_SUMMARY_FIELDS = {
    "datasetVersion",
    "recordsReturned",
    "recordsEvaluated",
    "rawRowsRead",
    "truncated",
    "excludedUnresolvedCount",
    "excludedGovernanceReviewCount",
    "skippedMissingRequiredFieldCount",
    "skippedInvalidSourceRecordCount",
}
CLASS_BALANCE_FIELDS = {
    "positiveClassCount",
    "negativeClassCount",
    "positiveClassShare",
    "negativeClassShare",
}
REQUIRED_ALERT_MATRIX_FIELDS = {
    "precision",
    "recall",
    "falsePositiveRate",
    "falseNegativeRate",
}
EXPECTED_EVALUATION_ARTIFACT_FILENAMES = {
    "disagreement_report.jsonl",
    "evaluation_run.md",
    EXPECTED_EVALUATION_SUMMARY_FILENAME,
    "risk_level_report.json",
    "score_bucket_report.json",
}
MANIFEST_FIELDS = {"artifactSetVersion", "files", "generatedAt", "reportType"}
MANIFEST_FILE_FIELDS = {"name", "sha256", "sizeBytes"}
def generate_evaluation_card_from_fdp124_artifacts(
        evaluation_summary_path: Path,
        evaluation_manifest_path: Path,
        governance_metadata: dict[str, Any],
        generated_at: str,
) -> dict[str, Any]:
    summary_path = Path(evaluation_summary_path)
    manifest_path = Path(evaluation_manifest_path)
    _require_canonical_filename(summary_path, EXPECTED_EVALUATION_SUMMARY_FILENAME, "evaluation summary")
    _require_canonical_filename(manifest_path, EXPECTED_EVALUATION_MANIFEST_FILENAME, "manifest")
    manifest_bytes = _read_required_bytes(manifest_path, "manifest", MAX_EVALUATION_MANIFEST_BYTES)
    summary_bytes = _read_required_bytes(summary_path, "evaluation_summary", MAX_EVALUATION_SUMMARY_BYTES)
    artifact_bytes = {
        name: (
            summary_bytes
            if name == EXPECTED_EVALUATION_SUMMARY_FILENAME
            else _read_required_bytes(summary_path.with_name(name), name, MAX_EVALUATION_SUMMARY_BYTES)
        )
        for name in EXPECTED_EVALUATION_ARTIFACT_FILENAMES
    }
    manifest = _load_json_bytes(manifest_bytes, "manifest")
    summary = _load_json_bytes(summary_bytes, "evaluation_summary")
    _reject_forbidden_input(summary)
    _validate_summary(summary)
    _validate_manifest(manifest, summary, artifact_bytes)
    metadata = _governance_metadata(governance_metadata)

    quality_metrics = summary["qualityMetrics"]
    dataset_summary = quality_metrics["datasetSummary"]
    class_balance = quality_metrics["classBalance"]
    dataset_metadata = summary["datasetMetadata"]
    alert_matrix = quality_metrics["alertRecommendedConfusionMatrix"]

    warnings = _warnings(summary)
    evaluation_card = {
        "cardVersion": PLATFORM_RECOMMENDATION_EVALUATION_CARD_VERSION,
        "cardType": PLATFORM_RECOMMENDATION_EVALUATION_CARD_REPORT_TYPE,
        "generatedAt": generated_at,
        "evaluationSubject": dict(summary["evaluationSubject"]),
        "metricsSubject": summary["metricsSubject"],
        "metricBasis": summary["metricBasis"],
        "allowedUsageModes": metadata["allowedUsageModes"],
        "evaluationPurpose": EVALUATION_PURPOSE,
        "runtimeDecisionAuthority": NO_AUTHORITY,
        "promotionAuthority": NO_AUTHORITY,
        "thresholdChangeAuthority": NO_AUTHORITY,
        "paymentAuthorizationAuthority": NO_AUTHORITY,
        "workflowAuthority": NO_AUTHORITY,
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
    return validate_evaluation_card(evaluation_card)


def _validate_manifest(manifest: dict[str, Any], summary: dict[str, Any], artifact_bytes: dict[str, bytes]) -> None:
    _reject_forbidden_input(manifest)
    _reject_unknown(manifest, MANIFEST_FIELDS, "manifest")
    missing = sorted(MANIFEST_FIELDS - set(manifest))
    if missing:
        raise Fdp123EvaluationCardValidationError(f"manifest missing required fields: {', '.join(missing)}")
    if manifest.get("reportType") != EXPECTED_EVALUATION_REPORT_TYPE:
        raise Fdp123EvaluationCardValidationError("manifest reportType unsupported")
    if manifest.get("artifactSetVersion") != EXPECTED_SOURCE_ARTIFACT_SET_VERSION:
        raise Fdp123EvaluationCardValidationError("manifest artifactSetVersion unsupported")
    if normalize_rfc3339_timestamp(manifest.get("generatedAt"), "manifest generatedAt") != normalize_rfc3339_timestamp(
            summary.get("generatedAt"), "evaluation summary generatedAt"
    ):
        raise Fdp123EvaluationCardValidationError("manifest generatedAt must match evaluation summary generatedAt")
    files = manifest.get("files")
    if not isinstance(files, list) or len(files) != len(EXPECTED_EVALUATION_ARTIFACT_FILENAMES):
        raise Fdp123EvaluationCardValidationError("manifest files must list canonical FDP-124 artifacts")
    seen_names: set[str] = set()
    for item in files:
        if not isinstance(item, dict):
            raise Fdp123EvaluationCardValidationError("manifest file entries must be objects")
        _reject_unknown(item, MANIFEST_FILE_FIELDS, "manifest file entry")
        missing_file_fields = sorted(MANIFEST_FILE_FIELDS - set(item))
        if missing_file_fields:
            raise Fdp123EvaluationCardValidationError(
                f"manifest file entry missing required fields: {', '.join(missing_file_fields)}"
            )
        name = item["name"]
        if name not in EXPECTED_EVALUATION_ARTIFACT_FILENAMES:
            raise Fdp123EvaluationCardValidationError("manifest lists unsupported artifact")
        if name in seen_names:
            raise Fdp123EvaluationCardValidationError("manifest contains duplicate artifact")
        seen_names.add(name)
        expected_bytes = artifact_bytes[name]
        sha256 = item["sha256"]
        size_bytes = item["sizeBytes"]
        if not isinstance(sha256, str) or re.fullmatch(r"[a-f0-9]{64}", sha256) is None:
            raise Fdp123EvaluationCardValidationError("manifest file sha256 must be lowercase hex")
        if isinstance(size_bytes, bool) or not isinstance(size_bytes, int) or size_bytes < 0:
            raise Fdp123EvaluationCardValidationError("manifest file sizeBytes must be a non-negative integer")
        if sha256 != hashlib.sha256(expected_bytes).hexdigest():
            raise Fdp123EvaluationCardValidationError(f"{name} hash mismatch")
        if size_bytes != len(expected_bytes):
            raise Fdp123EvaluationCardValidationError(f"{name} size mismatch")
    if seen_names != EXPECTED_EVALUATION_ARTIFACT_FILENAMES:
        raise Fdp123EvaluationCardValidationError("manifest files must list canonical FDP-124 artifacts")


def _validate_summary(summary: dict[str, Any]) -> None:
    _reject_unknown(summary, ALLOWED_SUMMARY_FIELDS, "evaluation summary")
    extra_missing = sorted(REQUIRED_SUMMARY_FIELDS - set(summary))
    if extra_missing:
        raise Fdp123EvaluationCardValidationError(f"evaluation summary missing required fields: {', '.join(extra_missing)}")
    if summary.get("reportType") != EXPECTED_EVALUATION_REPORT_TYPE:
        raise Fdp123EvaluationCardValidationError("evaluation summary reportType unsupported")
    _validate_evaluation_subject(summary.get("evaluationSubject"))
    if summary.get("metricsSubject") != METRICS_SUBJECT:
        raise Fdp123EvaluationCardValidationError("evaluation summary metricsSubject unsupported")
    if summary.get("metricBasis") != METRIC_BASIS:
        raise Fdp123EvaluationCardValidationError("evaluation summary metricBasis unsupported")
    normalize_rfc3339_timestamp(summary.get("generatedAt"), "evaluation summary generatedAt")
    dataset_metadata = _required_object(summary, "datasetMetadata")
    _reject_unknown(dataset_metadata, DATASET_METADATA_FIELDS, "datasetMetadata")
    if dataset_metadata.get("datasetVersion") != EXPECTED_DATASET_VERSION:
        raise Fdp123EvaluationCardValidationError("datasetVersion unsupported")
    if dataset_metadata.get("timeBasis") != EXPECTED_DATASET_TIME_BASIS:
        raise Fdp123EvaluationCardValidationError("dataset time basis unsupported")
    quality_metrics = _required_object(summary, "qualityMetrics")
    _reject_unknown(quality_metrics, ALLOWED_QUALITY_METRIC_FIELDS, "qualityMetrics")
    missing_metrics = sorted(REQUIRED_QUALITY_METRIC_FIELDS - set(quality_metrics))
    if missing_metrics:
        raise Fdp123EvaluationCardValidationError(f"qualityMetrics missing required fields: {', '.join(missing_metrics)}")
    dataset_summary = _required_object(quality_metrics, "datasetSummary")
    _reject_unknown(dataset_summary, DATASET_SUMMARY_FIELDS, "qualityMetrics.datasetSummary")
    class_balance = _required_object(quality_metrics, "classBalance")
    _reject_unknown(class_balance, CLASS_BALANCE_FIELDS, "qualityMetrics.classBalance")
    _required_non_negative_int(class_balance, "positiveClassCount")
    _required_non_negative_int(class_balance, "negativeClassCount")
    matrix = _required_object(quality_metrics, "alertRecommendedConfusionMatrix")
    _reject_unknown(
        matrix,
        REQUIRED_ALERT_MATRIX_FIELDS | {
            "recordsWithSignal",
            "truePositive",
            "falsePositive",
            "trueNegative",
            "falseNegative",
            "missingAlertRecommendedCount",
        },
        "qualityMetrics.alertRecommendedConfusionMatrix",
    )
    missing_matrix = sorted(REQUIRED_ALERT_MATRIX_FIELDS - set(matrix))
    if missing_matrix:
        raise Fdp123EvaluationCardValidationError(
            f"alertRecommendedConfusionMatrix missing required fields: {', '.join(missing_matrix)}"
        )
    for field in sorted(REQUIRED_ALERT_MATRIX_FIELDS):
        _metric_object(matrix, field)
    if quality_metrics.get("metricBasis") != METRIC_BASIS:
        raise Fdp123EvaluationCardValidationError("qualityMetrics metricBasis unsupported")
    records_evaluated = _required_non_negative_int(dataset_summary, "recordsEvaluated")
    records_returned = _required_non_negative_int(dataset_summary, "recordsReturned")
    metadata_records_returned = _required_non_negative_int(dataset_metadata, "recordsReturned")
    if records_returned != metadata_records_returned:
        raise Fdp123EvaluationCardValidationError("datasetSummary.recordsReturned must match datasetMetadata.recordsReturned")
    if records_returned != records_evaluated:
        raise Fdp123EvaluationCardValidationError("recordsReturned must equal recordsEvaluated")
    validate_class_count_integrity(
        _required_non_negative_int(class_balance, "positiveClassCount"),
        _required_non_negative_int(class_balance, "negativeClassCount"),
        records_evaluated,
    )
    if not isinstance(summary.get("warnings"), list):
        raise Fdp123EvaluationCardValidationError("warnings must be a list")


def _validate_evaluation_subject(raw: Any) -> None:
    if raw != EVALUATION_SUBJECT:
        raise Fdp123EvaluationCardValidationError("evaluation summary evaluationSubject unsupported")


def _metric_object(raw: dict[str, Any], field: str) -> None:
    value = raw.get(field)
    if not isinstance(value, dict):
        raise Fdp123EvaluationCardValidationError(f"{field} must be a metric object")
    if set(value) != {"available", "reason", "value"}:
        raise Fdp123EvaluationCardValidationError(f"{field} metric shape unsupported")
    available = value["available"]
    metric_value = value["value"]
    reason = value["reason"]
    if not isinstance(available, bool):
        raise Fdp123EvaluationCardValidationError(f"{field}.available must be boolean")
    if available:
        try:
            metric_value = require_finite_number(metric_value, f"{field}.value")
        except JsonContractError as exc:
            raise Fdp123EvaluationCardValidationError(f"{field}.value must be numeric") from exc
        if metric_value < 0.0 or metric_value > 1.0:
            raise Fdp123EvaluationCardValidationError(f"{field}.value out of range")
        if reason is not None:
            raise Fdp123EvaluationCardValidationError(f"{field}.reason must be null when available")
    else:
        if metric_value is not None:
            raise Fdp123EvaluationCardValidationError(f"{field}.value must be null when unavailable")
        if not isinstance(reason, str) or not reason:
            raise Fdp123EvaluationCardValidationError(f"{field}.reason required when unavailable")


def _governance_metadata(raw: dict[str, Any]) -> dict[str, Any]:
    if not isinstance(raw, dict):
        raise Fdp123EvaluationCardValidationError("governance metadata must be an object")
    _reject_forbidden_input(raw)
    extra = sorted(set(raw) - ALLOWED_GOVERNANCE_METADATA_FIELDS)
    if extra:
        raise Fdp123EvaluationCardValidationError(f"governance metadata contains unsupported fields: {', '.join(extra)}")
    missing = sorted(REQUIRED_GOVERNANCE_METADATA_FIELDS - set(raw))
    if missing:
        raise Fdp123EvaluationCardValidationError(f"governance metadata missing required fields: {', '.join(missing)}")
    return dict(raw)


def _warnings(summary: dict[str, Any]) -> list[str]:
    value = summary.get("warnings", [])
    if value is None:
        return []
    if not isinstance(value, list):
        raise Fdp123EvaluationCardValidationError("warnings must be a list")
    if not all(isinstance(item, str) for item in value):
        raise Fdp123EvaluationCardValidationError("warnings must contain strings")
    if len(set(value)) != len(value):
        raise Fdp123EvaluationCardValidationError("warnings contains duplicate values")
    return sorted(value)


def _required_object(raw: dict[str, Any], field: str) -> dict[str, Any]:
    value = raw.get(field)
    if not isinstance(value, dict):
        raise Fdp123EvaluationCardValidationError(f"{field} must be an object")
    return value


def _reject_unknown(raw: dict[str, Any], allowed: set[str], label: str) -> None:
    extra = sorted(set(raw) - allowed)
    if extra:
        raise Fdp123EvaluationCardValidationError(f"{label} contains unsupported fields: {', '.join(extra)}")


def _required_non_negative_int(raw: dict[str, Any], field: str) -> int:
    value = raw.get(field)
    if isinstance(value, bool) or not isinstance(value, int) or value < 0:
        raise Fdp123EvaluationCardValidationError(f"{field} must be a non-negative integer")
    return value


def _require_canonical_filename(path: Path, expected_name: str, label: str) -> None:
    if path.name != expected_name:
        raise Fdp123EvaluationCardValidationError(f"{label} filename must be {expected_name}")


def _read_required_bytes(path: Path, label: str, max_bytes: int) -> bytes:
    _reject_symlink_path(path, label)
    if not path.is_file():
        raise Fdp123EvaluationCardValidationError(f"{label} missing")
    flags = os.O_RDONLY | getattr(os, "O_BINARY", 0) | getattr(os, "O_NOFOLLOW", 0)
    try:
        fd = os.open(path, flags)
    except OSError as exc:
        raise Fdp123EvaluationCardValidationError(f"{label} missing") from exc
    try:
        if not stat.S_ISREG(os.fstat(fd).st_mode):
            raise Fdp123EvaluationCardValidationError(f"{label} must be a file")
        with os.fdopen(fd, "rb") as handle:
            fd = -1
            payload = handle.read(max_bytes + 1)
    finally:
        if fd >= 0:
            os.close(fd)
    if len(payload) > max_bytes:
        raise Fdp123EvaluationCardValidationError(f"{label} exceeds maximum byte size")
    return payload


def _reject_symlink_path(path: Path, label: str) -> None:
    if path.is_symlink():
        raise Fdp123EvaluationCardValidationError(f"{label} must not be a symlink")
    parent = path.parent
    while parent != parent.parent:
        if parent.is_symlink():
            raise Fdp123EvaluationCardValidationError(f"{label} parent must not be a symlink")
        parent = parent.parent


def _load_json_bytes(payload: bytes, label: str) -> dict[str, Any]:
    try:
        value = loads_strict_json(payload)
    except (UnicodeDecodeError, json.JSONDecodeError, JsonContractError) as exception:
        raise Fdp123EvaluationCardValidationError(f"{label} must be valid JSON") from exception
    if not isinstance(value, dict):
        raise Fdp123EvaluationCardValidationError(f"{label} must be a JSON object")
    return value


def _reject_forbidden_input(value: Any) -> None:
    if isinstance(value, dict):
        for key, nested in value.items():
            compact_key = _compact(str(key))
            if compact_key in FORBIDDEN_INPUT_COMPACT_TERMS:
                raise Fdp123EvaluationCardValidationError(f"forbidden input field: {key}")
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
                raise Fdp123EvaluationCardValidationError(f"forbidden input value: {value}")


def _compact(value: str) -> str:
    return "".join(character for character in value.lower() if character.isalnum())
