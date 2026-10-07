from __future__ import annotations

import math
import re
from decimal import Decimal
from typing import Any

from offline_evaluation.feedback_dataset_evaluation.classification_policy import SUPPORTED_RISK_LEVELS
from offline_evaluation.feedback_dataset_evaluation.models import FeedbackDatasetMetadata, FeedbackDatasetRecord
from offline_evaluation.feedback_dataset_evaluation.timestamp_contract import (
    TimestampContractError,
    normalize_rfc3339_timestamp,
    validate_optional_timestamp_range,
)
from app.model_identity_policy import (
    validate_feature_contract_version,
    validate_model_name,
    validate_model_version,
)


class FeedbackDatasetFormatError(ValueError):
    """Raised when feedback dataset JSONL framing is invalid."""


class FeedbackDatasetValidationError(ValueError):
    """Raised when feedback dataset fields are invalid or unsafe."""


class FeedbackDatasetFailedDatasetError(ValueError):
    """Raised when feedback dataset metadata declares an unsuccessful build."""


DATASET_VERSION = "feedback-dataset-v2"
DATASET_TIME_BASIS = "FEEDBACK_CREATED_AT"
MAX_DATASET_RECORDS = 1000
MAX_JSONL_LINE_LENGTH = 64_000
MAX_JSONL_NON_EMPTY_LINES = MAX_DATASET_RECORDS + 1
MAX_FEEDBACK_DATASET_BYTES = MAX_JSONL_NON_EMPTY_LINES * (MAX_JSONL_LINE_LENGTH + 2)

EVALUATION_RECORD_ID_PATTERN = re.compile(r"^eval_[a-f0-9]{32}$")
TRANSACTION_REFERENCE_PATTERN = re.compile(r"^txnref_[a-f0-9]{32}$")
MACHINE_CODE_PATTERN = re.compile(r"^[A-Z0-9_]{1,64}$")
ML_MODEL_IDENTITY_FIELDS = ("mlModelName", "mlModelVersion", "mlFeatureContractVersion")
ML_PREDICTION_EVIDENCE_FIELDS = (
    "mlPredictionEvidenceStatus",
    "mlPredictionEvidenceOmissionReason",
    "mlPredictionScore",
    "mlPredictionRiskLevel",
    "mlPredictionExecutedAt",
)
ALLOWED_ML_PREDICTION_EVIDENCE_STATUSES = {
    "AVAILABLE",
    "LEGITIMATELY_ABSENT",
    "MISSING_UNEXPECTEDLY",
    "MALFORMED",
    "IDENTITY_MISMATCH",
}
ML_PREDICTION_OMISSION_STATUS = {
    "DIAGNOSTIC_EMISSION_DISABLED": "LEGITIMATELY_ABSENT",
    "DIAGNOSTIC_ENRICHMENT_UNAVAILABLE": "MISSING_UNEXPECTEDLY",
    "ML_ENGINE_UNAVAILABLE": "MISSING_UNEXPECTEDLY",
    "SOURCE_TIMESTAMP_MISSING": "MALFORMED",
    "INVALID_SCORE": "MALFORMED",
    "IDENTITY_VALIDATION_FAILURE": "MALFORMED",
    "EVIDENCE_SOURCE_INTEGRITY_FAILURE": "MALFORMED",
    "LEGITIMATE_ABSENCE": "LEGITIMATELY_ABSENT",
    "PREDICTION_NOT_ACCEPTED": "MALFORMED",
}
ALLOWED_RISK_LEVELS = set(SUPPORTED_RISK_LEVELS)
ALLOWED_RULES_EVIDENCE_STATUSES = {"AVAILABLE", "UNAVAILABLE"}
ALLOWED_ENGINE_INTELLIGENCE_STATUSES = {"AVAILABLE", "ABSENT", "UNAVAILABLE", "DEGRADED"}
ALLOWED_AGREEMENT_STATUSES = {
    "AGREEMENT",
    "ADJACENT_RISK_VARIANCE",
    "DISAGREEMENT",
    "PARTIAL",
    "INSUFFICIENT_DATA",
    "REQUIRED_ENGINE_NOT_COMPARABLE",
}
ALLOWED_RISK_MISMATCH_STATUSES = {
    "SAME_RISK_LEVEL",
    "ADJACENT_RISK_LEVEL",
    "MATERIAL_RISK_MISMATCH",
    "NOT_COMPARABLE",
}
ALLOWED_SCORE_DELTA_BUCKETS = {"NONE", "SMALL", "MEDIUM", "LARGE", "UNAVAILABLE"}
ALLOWED_ANALYST_RECOMMENDATION_STATUSES = {
    "AVAILABLE",
    "ABSENT",
    "NOT_APPLICABLE",
    "INSUFFICIENT_DATA",
    "UNAVAILABLE",
    "DEGRADED",
}
ALLOWED_ANALYST_RECOMMENDATIONS = {
    "RECOMMEND_REVIEW",
    "RECOMMEND_CASE_CREATION",
    "RECOMMEND_STEP_UP_REVIEW",
    "RECOMMEND_MONITOR",
    "RECOMMEND_NO_ACTION",
}

ALLOWED_METADATA_FIELDS = {
    "type",
    "datasetVersion",
    "builtAt",
    "timeBasis",
    "fromInclusive",
    "toInclusive",
    "rawRowsRead",
    "recordsReturned",
    "excludedUnresolvedCount",
    "excludedGovernanceReviewCount",
    "skippedMissingRequiredFieldCount",
    "skippedInvalidSourceRecordCount",
    "truncated",
    "failureReason",
}
REQUIRED_METADATA_FIELDS = set(ALLOWED_METADATA_FIELDS) - {"fromInclusive", "toInclusive"}
ALLOWED_FAILURE_REASONS = {
    "NONE",
    "INVALID_REQUEST",
    "FEEDBACK_STORE_UNAVAILABLE",
    "ML_PREDICTION_EVIDENCE_STORE_UNAVAILABLE",
    "ML_PREDICTION_EVIDENCE_INTEGRITY_FAILURE",
    "DATASET_SERIALIZATION_FAILED",
}

ALLOWED_RECORD_LINE_FIELDS = {"type", "record"}
ALLOWED_RECORD_FIELDS = {
    "datasetVersion",
    "evaluationRecordId",
    "transactionReference",
    "feedbackLabel",
    "evaluationLabel",
    "decisionReasonCodes",
    "feedbackCreatedAt",
    "fraudScore",
    "riskLevel",
    "alertRecommended",
    "engineIntelligenceStatus",
    "agreementStatus",
    "riskMismatchStatus",
    "scoreDeltaBucket",
    "rulesEvidenceStatus",
    "rulesRiskLevel",
    "mlPredictionEvidenceStatus",
    "mlPredictionEvidenceOmissionReason",
    "mlPredictionScore",
    "mlPredictionRiskLevel",
    "mlPredictionExecutedAt",
    "mlModelName",
    "mlModelVersion",
    "mlFeatureContractVersion",
    "analystRecommendationStatus",
    "analystRecommendation",
    "analystRecommendationVersion",
    "analystRecommendationGeneratedAt",
    "analystRecommendationReasonCodes",
    "scoredAt",
    "transactionTimestamp",
}
REQUIRED_RECORD_FIELDS = {
    "datasetVersion",
    "evaluationRecordId",
    "transactionReference",
    "feedbackLabel",
    "evaluationLabel",
    "decisionReasonCodes",
    "feedbackCreatedAt",
    "rulesEvidenceStatus",
    "rulesRiskLevel",
    *ML_PREDICTION_EVIDENCE_FIELDS,
    *ML_MODEL_IDENTITY_FIELDS,
}
ALLOWED_FEEDBACK_LABELS = {"CONFIRMED_FRAUD", "CONFIRMED_LEGITIMATE"}
ALLOWED_EVALUATION_LABELS = {"POSITIVE_FRAUD", "NEGATIVE_LEGITIMATE"}

FORBIDDEN_FIELD_NAMES = {
    "rawfeedbackid",
    "feedbackid",
    "rawtransactionid",
    "transactionid",
    "customerid",
    "accountid",
    "cardid",
    "deviceid",
    "merchantid",
    "analystid",
    "createdby",
    "submittedby",
    "correlationid",
    "idempotencykey",
    "requestpayloadhash",
    "notes",
    "rawnotes",
    "rawpayload",
    "rawfeaturevector",
    "rawevidence",
    "rawmlrequest",
    "rawmlresponse",
    "endpoint",
    "token",
    "secret",
    "password",
    "stacktrace",
    "exceptionmessage",
    "groundtruth",
    "traininglabel",
    "modeltraininglabel",
    "finaldecision",
    "paymentdecision",
    "paymentauthorization",
    "truefraud",
    "modelapproved",
    "promotionready",
    "thresholdrecommendation",
}
FORBIDDEN_VALUE_COMPACT_TERMS = {
    "customerid",
    "accountid",
    "cardid",
    "deviceid",
    "merchantid",
    "feedbackid",
    "transactionid",
    "email",
    "endpoint",
    "token",
    "secret",
    "password",
    "stacktrace",
    "groundtruth",
    "traininglabel",
    "modeltraininglabel",
    "finaldecision",
    "paymentauthorization",
}


def validate_metadata(raw: dict[str, Any]) -> FeedbackDatasetMetadata:
    _reject_forbidden_keys(raw)
    _reject_unknown_fields(raw, ALLOWED_METADATA_FIELDS, "metadata")
    missing = sorted(REQUIRED_METADATA_FIELDS - raw.keys())
    if missing:
        raise FeedbackDatasetValidationError(f"metadata missing required fields: {', '.join(missing)}")
    if raw.get("type") != "DATASET_METADATA":
        raise FeedbackDatasetValidationError("metadata type must be DATASET_METADATA")
    if raw.get("datasetVersion") != DATASET_VERSION:
        raise FeedbackDatasetValidationError("datasetVersion has unsupported value")
    if raw.get("timeBasis") != DATASET_TIME_BASIS:
        raise FeedbackDatasetValidationError("timeBasis must be FEEDBACK_CREATED_AT")
    failure_reason = _required_enum(raw, "failureReason", ALLOWED_FAILURE_REASONS)
    if failure_reason != "NONE":
        raise FeedbackDatasetFailedDatasetError(failure_reason)
    records_returned = _required_int(raw, "recordsReturned", minimum=0, maximum=MAX_DATASET_RECORDS)
    raw_rows_read = _required_int(raw, "rawRowsRead", minimum=0, maximum=MAX_DATASET_RECORDS + 1)
    if raw_rows_read < records_returned:
        raise FeedbackDatasetValidationError("rawRowsRead must be >= recordsReturned")
    truncated = raw.get("truncated")
    if not isinstance(truncated, bool):
        raise FeedbackDatasetValidationError("truncated must be boolean")
    try:
        from_inclusive, to_inclusive = validate_optional_timestamp_range(
            raw.get("fromInclusive"),
            raw.get("toInclusive"),
        )
    except TimestampContractError as exception:
        raise FeedbackDatasetValidationError(str(exception)) from exception
    excluded_unresolved_count = _required_int(
        raw, "excludedUnresolvedCount", minimum=0, maximum=MAX_DATASET_RECORDS
    )
    excluded_governance_review_count = _required_int(
        raw, "excludedGovernanceReviewCount", minimum=0, maximum=MAX_DATASET_RECORDS
    )
    skipped_missing_required_field_count = _required_int(
        raw, "skippedMissingRequiredFieldCount", minimum=0, maximum=MAX_DATASET_RECORDS
    )
    skipped_invalid_source_record_count = _required_int(
        raw, "skippedInvalidSourceRecordCount", minimum=0, maximum=MAX_DATASET_RECORDS
    )
    accounted_rows = (
        records_returned
        + excluded_unresolved_count
        + excluded_governance_review_count
        + skipped_missing_required_field_count
        + skipped_invalid_source_record_count
    )
    if accounted_rows > MAX_DATASET_RECORDS:
        raise FeedbackDatasetValidationError("dataset population exceeds maximum dataset records")
    if raw_rows_read != accounted_rows + (1 if truncated else 0):
        raise FeedbackDatasetValidationError("dataset population counts must reconcile")
    return FeedbackDatasetMetadata(
        dataset_version=DATASET_VERSION,
        built_at=_required_datetime_string(raw, "builtAt"),
        time_basis=DATASET_TIME_BASIS,
        from_inclusive=from_inclusive,
        to_inclusive=to_inclusive,
        raw_rows_read=raw_rows_read,
        records_returned=records_returned,
        excluded_unresolved_count=excluded_unresolved_count,
        excluded_governance_review_count=excluded_governance_review_count,
        skipped_missing_required_field_count=skipped_missing_required_field_count,
        skipped_invalid_source_record_count=skipped_invalid_source_record_count,
        truncated=truncated,
        failure_reason=failure_reason,
    )


def validate_record_line(raw: dict[str, Any]) -> FeedbackDatasetRecord:
    _reject_forbidden_keys(raw)
    _reject_unknown_fields(raw, ALLOWED_RECORD_LINE_FIELDS, "record line")
    if raw.get("type") != "DATASET_RECORD":
        raise FeedbackDatasetValidationError("record line type must be DATASET_RECORD")
    record = raw.get("record")
    if not isinstance(record, dict):
        raise FeedbackDatasetValidationError("DATASET_RECORD line requires a record object")
    return validate_record(record)


def validate_record(raw: dict[str, Any]) -> FeedbackDatasetRecord:
    _reject_forbidden_keys(raw)
    _reject_unknown_fields(raw, ALLOWED_RECORD_FIELDS, "record")
    missing = sorted(REQUIRED_RECORD_FIELDS - raw.keys())
    if missing:
        raise FeedbackDatasetValidationError(f"record missing required fields: {', '.join(missing)}")
    if raw.get("datasetVersion") != DATASET_VERSION:
        raise FeedbackDatasetValidationError("record datasetVersion has unsupported value")
    feedback_label = _required_enum(raw, "feedbackLabel", ALLOWED_FEEDBACK_LABELS)
    evaluation_label = _required_enum(raw, "evaluationLabel", ALLOWED_EVALUATION_LABELS)
    if feedback_label == "CONFIRMED_FRAUD" and evaluation_label != "POSITIVE_FRAUD":
        raise FeedbackDatasetValidationError("CONFIRMED_FRAUD requires POSITIVE_FRAUD")
    if feedback_label == "CONFIRMED_LEGITIMATE" and evaluation_label != "NEGATIVE_LEGITIMATE":
        raise FeedbackDatasetValidationError("CONFIRMED_LEGITIMATE requires NEGATIVE_LEGITIMATE")
    ml_model_name = _optional_model_identity_part(raw, "mlModelName")
    ml_model_version = _optional_model_identity_part(raw, "mlModelVersion")
    ml_feature_contract_version = _optional_model_identity_part(raw, "mlFeatureContractVersion")
    _validate_ml_model_identity(ml_model_name, ml_model_version, ml_feature_contract_version)
    ml_prediction_evidence_status = _required_enum(
        raw,
        "mlPredictionEvidenceStatus",
        ALLOWED_ML_PREDICTION_EVIDENCE_STATUSES,
    )
    ml_prediction_evidence_omission_reason = _optional_enum(
        raw,
        "mlPredictionEvidenceOmissionReason",
        set(ML_PREDICTION_OMISSION_STATUS),
    )
    ml_prediction_score = _optional_ml_prediction_score(raw, "mlPredictionScore")
    ml_prediction_risk_level = _optional_enum(
        raw,
        "mlPredictionRiskLevel",
        ALLOWED_RISK_LEVELS,
    )
    ml_prediction_executed_at = _optional_datetime_string(raw, "mlPredictionExecutedAt")
    _validate_ml_prediction_evidence(
        ml_prediction_evidence_status,
        ml_prediction_evidence_omission_reason,
        ml_prediction_score,
        ml_prediction_risk_level,
        ml_prediction_executed_at,
        ml_model_name,
        ml_model_version,
        ml_feature_contract_version,
    )
    rules_evidence_status = _required_enum(raw, "rulesEvidenceStatus", ALLOWED_RULES_EVIDENCE_STATUSES)
    rules_risk_level = _optional_enum(raw, "rulesRiskLevel", ALLOWED_RISK_LEVELS)
    if (rules_evidence_status == "AVAILABLE") != (rules_risk_level is not None):
        raise FeedbackDatasetValidationError("Rules evidence availability must match rulesRiskLevel")
    return FeedbackDatasetRecord(
        dataset_version=DATASET_VERSION,
        evaluation_record_id=_required_pattern(raw, "evaluationRecordId", EVALUATION_RECORD_ID_PATTERN),
        transaction_reference=_required_pattern(raw, "transactionReference", TRANSACTION_REFERENCE_PATTERN),
        feedback_label=feedback_label,
        evaluation_label=evaluation_label,
        decision_reason_codes=_machine_code_tuple(raw, "decisionReasonCodes", minimum_items=1, maximum_items=10),
        feedback_created_at=_required_datetime_string(raw, "feedbackCreatedAt"),
        fraud_score=_optional_bounded_score(raw, "fraudScore"),
        risk_level=_optional_enum(raw, "riskLevel", ALLOWED_RISK_LEVELS),
        alert_recommended=_optional_bool(raw, "alertRecommended"),
        engine_intelligence_status=_optional_enum(
            raw, "engineIntelligenceStatus", ALLOWED_ENGINE_INTELLIGENCE_STATUSES
        ),
        agreement_status=_optional_enum(raw, "agreementStatus", ALLOWED_AGREEMENT_STATUSES),
        risk_mismatch_status=_optional_enum(
            raw, "riskMismatchStatus", ALLOWED_RISK_MISMATCH_STATUSES
        ),
        score_delta_bucket=_optional_enum(raw, "scoreDeltaBucket", ALLOWED_SCORE_DELTA_BUCKETS),
        rules_evidence_status=rules_evidence_status,
        rules_risk_level=rules_risk_level,
        ml_prediction_evidence_status=ml_prediction_evidence_status,
        ml_prediction_evidence_omission_reason=ml_prediction_evidence_omission_reason,
        ml_prediction_score=ml_prediction_score,
        ml_prediction_risk_level=ml_prediction_risk_level,
        ml_prediction_executed_at=ml_prediction_executed_at,
        ml_model_name=ml_model_name,
        ml_model_version=ml_model_version,
        ml_feature_contract_version=ml_feature_contract_version,
        analyst_recommendation_status=_optional_enum(
            raw, "analystRecommendationStatus", ALLOWED_ANALYST_RECOMMENDATION_STATUSES
        ),
        analyst_recommendation=_optional_enum(
            raw, "analystRecommendation", ALLOWED_ANALYST_RECOMMENDATIONS
        ),
        analyst_recommendation_version=_optional_safe_identifier(raw, "analystRecommendationVersion"),
        analyst_recommendation_generated_at=_optional_datetime_string(raw, "analystRecommendationGeneratedAt"),
        analyst_recommendation_reason_codes=_machine_code_tuple(
            raw,
            "analystRecommendationReasonCodes",
            minimum_items=0,
            maximum_items=20,
        ),
        scored_at=_optional_datetime_string(raw, "scoredAt"),
        transaction_timestamp=_optional_datetime_string(raw, "transactionTimestamp"),
    )


def validate_record_count(metadata: FeedbackDatasetMetadata, dataset_record_lines: int) -> None:
    if dataset_record_lines != metadata.records_returned:
        raise FeedbackDatasetValidationError("dataset record count does not match metadata recordsReturned")
    if dataset_record_lines > MAX_DATASET_RECORDS:
        raise FeedbackDatasetValidationError("feedback dataset JSONL exceeds maximum dataset records")


def evaluation_label_value(record: FeedbackDatasetRecord) -> int:
    if record.evaluation_label == "POSITIVE_FRAUD":
        return 1
    if record.evaluation_label == "NEGATIVE_LEGITIMATE":
        return 0
    raise FeedbackDatasetValidationError("evaluationLabel has unsupported value")


def _reject_unknown_fields(raw: dict[str, Any], allowed: set[str], location: str) -> None:
    unknown = sorted(set(raw.keys()) - allowed)
    if unknown:
        raise FeedbackDatasetValidationError(f"{location} contains unknown fields: {', '.join(unknown)}")


def _reject_forbidden_keys(value: Any) -> None:
    if isinstance(value, dict):
        for key, nested in value.items():
            if _compact(str(key)) in FORBIDDEN_FIELD_NAMES:
                raise FeedbackDatasetValidationError(f"forbidden field: {key}")
            _reject_forbidden_keys(nested)
    elif isinstance(value, list):
        for item in value:
            _reject_forbidden_keys(item)


def _required_int(raw: dict[str, Any], field: str, minimum: int, maximum: int | None = None) -> int:
    value = raw.get(field)
    if not isinstance(value, int) or isinstance(value, bool) or value < minimum:
        raise FeedbackDatasetValidationError(f"{field} must be an integer >= {minimum}")
    if maximum is not None and value > maximum:
        raise FeedbackDatasetValidationError(f"{field} must be <= {maximum}")
    return value


def _required_string(raw: dict[str, Any], field: str) -> str:
    value = raw.get(field)
    if not isinstance(value, str) or not value:
        raise FeedbackDatasetValidationError(f"{field} must be a non-empty string")
    return value


def _optional_string(raw: dict[str, Any], field: str) -> str | None:
    value = raw.get(field)
    if value is None:
        return None
    if not isinstance(value, str) or not value:
        raise FeedbackDatasetValidationError(f"{field} must be a non-empty string when present")
    _validate_safe_string_value(value, field)
    return value


def _optional_model_identity_part(raw: dict[str, Any], field: str) -> str | None:
    value = raw.get(field)
    if value is None:
        return None
    try:
        if field == "mlModelName":
            return validate_model_name(value, field)
        if field == "mlModelVersion":
            return validate_model_version(value, field)
        if field == "mlFeatureContractVersion":
            return validate_feature_contract_version(value, field)
    except ValueError as exception:
        raise FeedbackDatasetValidationError(str(exception)) from exception
    raise FeedbackDatasetValidationError(f"{field} is not a supported ML model identity field")


def _validate_ml_model_identity(
        ml_model_name: str | None,
        ml_model_version: str | None,
        ml_feature_contract_version: str | None,
) -> None:
    present = sum(value is not None for value in (ml_model_name, ml_model_version, ml_feature_contract_version))
    if present not in (0, len(ML_MODEL_IDENTITY_FIELDS)):
        raise FeedbackDatasetValidationError(
            "ML model identity must be entirely absent or complete: "
            + ", ".join(ML_MODEL_IDENTITY_FIELDS)
        )


def _required_enum(raw: dict[str, Any], field: str, allowed: set[str]) -> str:
    value = _required_string(raw, field)
    if value not in allowed:
        raise FeedbackDatasetValidationError(f"{field} has unsupported value")
    return value


def _required_pattern(raw: dict[str, Any], field: str, pattern: re.Pattern[str]) -> str:
    value = _required_string(raw, field)
    if pattern.fullmatch(value) is None:
        raise FeedbackDatasetValidationError(f"{field} has unsupported value")
    return value


def _optional_bool(raw: dict[str, Any], field: str) -> bool | None:
    value = raw.get(field)
    if value is None:
        return None
    if not isinstance(value, bool):
        raise FeedbackDatasetValidationError(f"{field} must be boolean or null")
    return value


def _optional_enum(raw: dict[str, Any], field: str, allowed: set[str]) -> str | None:
    value = raw.get(field)
    if value is None:
        return None
    if not isinstance(value, str) or value not in allowed:
        raise FeedbackDatasetValidationError(f"{field} has unsupported value")
    return value


def validate_bounded_score(value: Any, field: str) -> float | None:
    if value is None:
        return None
    if not isinstance(value, (int, float)) or isinstance(value, bool):
        raise FeedbackDatasetValidationError(f"{field} must be numeric or null")
    result = float(value)
    if not math.isfinite(result) or result < 0.0 or result > 1.0:
        raise FeedbackDatasetValidationError(f"{field} must be between 0.0 and 1.0")
    if max(0, -Decimal(str(result)).normalize().as_tuple().exponent) > 4:
        raise FeedbackDatasetValidationError(f"{field} scale must be <= 4")
    return result


def _optional_bounded_score(raw: dict[str, Any], field: str) -> float | None:
    return validate_bounded_score(raw.get(field), field)


def _optional_ml_prediction_score(raw: dict[str, Any], field: str) -> float | None:
    return _optional_bounded_score(raw, field)


def _optional_safe_identifier(raw: dict[str, Any], field: str) -> str | None:
    value = _optional_string(raw, field)
    if value is None:
        return None
    if len(value) > 64 or "/" in value or "\\" in value:
        raise FeedbackDatasetValidationError(f"{field} must be a bounded identifier without paths")
    return value


def _validate_ml_prediction_evidence(
        status: str,
        omission_reason: str | None,
        score: float | None,
        risk_level: str | None,
        executed_at: str | None,
        model_name: str | None,
        model_version: str | None,
        feature_contract_version: str | None,
) -> None:
    values_complete = score is not None and risk_level is not None and executed_at is not None
    identity_complete = all(
        value is not None
        for value in (model_name, model_version, feature_contract_version)
    )
    if status == "AVAILABLE":
        if not values_complete or not identity_complete or omission_reason is not None:
            raise FeedbackDatasetValidationError("available ML prediction evidence must be complete")
        return
    if any(value is not None for value in (
        score,
        risk_level,
        executed_at,
        model_name,
        model_version,
        feature_contract_version,
    )):
        raise FeedbackDatasetValidationError("unavailable ML prediction evidence must not carry prediction values")
    if status == "LEGITIMATELY_ABSENT" and omission_reason is None:
        raise FeedbackDatasetValidationError("legitimate absence requires authoritative omission proof")
    if omission_reason is not None and ML_PREDICTION_OMISSION_STATUS[omission_reason] != status:
        raise FeedbackDatasetValidationError("ML prediction omission reason contradicts evidence status")


def _required_datetime_string(raw: dict[str, Any], field: str) -> str:
    value = _required_string(raw, field)
    return _canonical_timestamp(value, field)


def _optional_datetime_string(raw: dict[str, Any], field: str) -> str | None:
    value = raw.get(field)
    if value is None:
        return None
    if not isinstance(value, str) or not value:
        raise FeedbackDatasetValidationError(f"{field} must be a non-empty date-time string or null")
    return _canonical_timestamp(value, field)


def _canonical_timestamp(value: str, field: str) -> str:
    try:
        return normalize_rfc3339_timestamp(value, field)
    except TimestampContractError as exception:
        raise FeedbackDatasetValidationError(str(exception)) from exception


def _machine_code_tuple(
        raw: dict[str, Any],
        field: str,
        minimum_items: int,
        maximum_items: int,
) -> tuple[str, ...]:
    value = raw.get(field)
    if value is None:
        if minimum_items > 0:
            raise FeedbackDatasetValidationError(f"{field} must be present")
        return ()
    if not isinstance(value, list):
        raise FeedbackDatasetValidationError(f"{field} must be a list of machine-code strings")
    if len(value) < minimum_items:
        raise FeedbackDatasetValidationError(f"{field} must contain at least {minimum_items} item(s)")
    if len(value) > maximum_items:
        raise FeedbackDatasetValidationError(f"{field} exceeds maximum item count")
    for item in value:
        _validate_machine_code(item, field)
    return tuple(value)


def _validate_machine_code(value: Any, field: str) -> None:
    if not isinstance(value, str) or MACHINE_CODE_PATTERN.fullmatch(value) is None:
        raise FeedbackDatasetValidationError(f"{field} contains non-machine-code value")
    _validate_safe_string_value(value, field)


def _validate_safe_string_value(value: str, field: str) -> None:
    compact = _compact(value)
    if ":" in value or "@" in value or "HTTP" in value or "WWW" in value:
        raise FeedbackDatasetValidationError(f"{field} contains unsafe value")
    if re.search(r"\d{13,19}", value) or re.search(r"[A-Z]{2}\d{2}[A-Z0-9]{11,30}", value):
        raise FeedbackDatasetValidationError(f"{field} contains raw financial identifier")
    if any(term in compact for term in FORBIDDEN_VALUE_COMPACT_TERMS):
        raise FeedbackDatasetValidationError(f"{field} contains forbidden value")


def _compact(value: str) -> str:
    return "".join(character for character in value.lower() if character.isalnum())

