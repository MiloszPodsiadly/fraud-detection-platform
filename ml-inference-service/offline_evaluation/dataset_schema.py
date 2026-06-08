from __future__ import annotations

from dataclasses import dataclass
from typing import Any


class DatasetFormatError(ValueError):
    """Raised when JSONL framing does not match the FDP-102 export contract."""


class FailedExportError(ValueError):
    """Raised when FDP-102 metadata declares a failed export."""


class DatasetValidationError(ValueError):
    """Raised when a known FDP-102 field is invalid or unsafe."""


ALLOWED_LABELS = {
    "ANALYST_CONFIRMED_FRAUD",
    "ANALYST_MARKED_LEGITIMATE",
    "NOT_EVALUATION_ELIGIBLE",
}

FORBIDDEN_FIELD_NAMES = {
    "rawtransactionid",
    "transactionid",
    "customerid",
    "accountid",
    "cardid",
    "deviceid",
    "merchantid",
    "analystid",
    "submittedby",
    "correlationid",
    "idempotencykey",
    "requestpayloadhash",
    "rawpayload",
    "rawfeaturevector",
    "rawevidence",
    "rawcontribution",
    "rawmlrequest",
    "rawmlresponse",
    "endpoint",
    "token",
    "secret",
    "stacktrace",
    "exceptionmessage",
    "groundtruth",
    "traininglabel",
    "modeltraininglabel",
    "finaldecision",
    "paymentdecision",
    "truefraud",
    "modelapproved",
    "promotionready",
    "thresholdrecommendation",
    "paymentauthorization",
}

REQUIRED_RECORD_FIELDS = {
    "evaluationRecordId",
    "transactionReference",
    "feedbackSubmittedAt",
    "evaluationLabel",
    "labelSource",
    "feedbackType",
    "usefulness",
    "accuracyAssessment",
    "projectionStatus",
}


@dataclass(frozen=True)
class DatasetMetadata:
    from_inclusive: str | None
    to_inclusive: str | None
    exported_at: str | None
    max_records: int | None
    raw_rows_read: int | None
    records_returned: int | None
    truncated: bool | None
    time_basis: str | None
    deduplication_policy: str | None

    def as_report_dict(self) -> dict[str, Any]:
        return {
            "deduplicationPolicy": self.deduplication_policy,
            "exportedAt": self.exported_at,
            "fromInclusive": self.from_inclusive,
            "maxRecords": self.max_records,
            "rawRowsRead": self.raw_rows_read,
            "recordsReturned": self.records_returned,
            "timeBasis": self.time_basis,
            "toInclusive": self.to_inclusive,
            "truncated": self.truncated,
        }


@dataclass(frozen=True)
class DatasetRecord:
    evaluation_record_id: str
    transaction_reference: str
    feedback_submitted_at: str
    evaluation_label: str
    projection_status: str
    ml_risk_level: str | None
    ml_score_bucket: str | None
    rules_risk_level: str | None
    rules_score_bucket: str | None
    reason_codes: tuple[str, ...]
    diagnostic_signals: tuple[str, ...]

    @property
    def is_evaluation_positive(self) -> bool:
        return self.evaluation_label == "ANALYST_CONFIRMED_FRAUD"

    @property
    def is_evaluation_negative(self) -> bool:
        return self.evaluation_label == "ANALYST_MARKED_LEGITIMATE"

    @property
    def is_evaluation_eligible(self) -> bool:
        return self.evaluation_label in {"ANALYST_CONFIRMED_FRAUD", "ANALYST_MARKED_LEGITIMATE"}

    @property
    def ml_signal_missing(self) -> bool:
        return self.ml_risk_level is None and self.ml_score_bucket is None

    @property
    def rules_signal_missing(self) -> bool:
        return self.rules_risk_level is None and self.rules_score_bucket is None

    @property
    def projection_missing(self) -> bool:
        return self.projection_status == "MISSING"


@dataclass(frozen=True)
class ParsedDataset:
    metadata: DatasetMetadata
    records: tuple[DatasetRecord, ...]
    total_lines_read: int
    metadata_lines_read: int
    dataset_records_read: int

    @property
    def evaluation_records(self) -> tuple[DatasetRecord, ...]:
        return tuple(record for record in self.records if record.is_evaluation_eligible)


def validate_metadata(raw: dict[str, Any]) -> DatasetMetadata:
    _reject_forbidden_keys(raw)
    failure_reason = raw.get("failureReason")
    if failure_reason is not None:
        raise FailedExportError(str(failure_reason))
    if raw.get("timeBasis") not in {None, "FEEDBACK_SUBMITTED_AT"}:
        raise DatasetValidationError("timeBasis must be FEEDBACK_SUBMITTED_AT")
    max_records = _optional_int(raw, "maxRecords", minimum=1, maximum=500)
    raw_rows_read = _optional_int(raw, "rawRowsRead", minimum=0)
    records_returned = _optional_int(raw, "recordsReturned", minimum=0)
    truncated = raw.get("truncated")
    if truncated is not None and not isinstance(truncated, bool):
        raise DatasetValidationError("truncated must be boolean")
    return DatasetMetadata(
        from_inclusive=_optional_string(raw, "fromInclusive"),
        to_inclusive=_optional_string(raw, "toInclusive"),
        exported_at=_optional_string(raw, "exportedAt"),
        max_records=max_records,
        raw_rows_read=raw_rows_read,
        records_returned=records_returned,
        truncated=truncated,
        time_basis=raw.get("timeBasis"),
        deduplication_policy=_optional_string(raw, "deduplicationPolicy"),
    )


def validate_record(raw: dict[str, Any]) -> DatasetRecord:
    if not isinstance(raw, dict):
        raise DatasetValidationError("record must be an object")
    _reject_forbidden_keys(raw)
    missing = sorted(REQUIRED_RECORD_FIELDS - raw.keys())
    if missing:
        raise DatasetValidationError(f"record missing required fields: {', '.join(missing)}")
    label = _required_string(raw, "evaluationLabel")
    if label not in ALLOWED_LABELS:
        raise DatasetValidationError(f"unknown evaluationLabel: {label}")
    return DatasetRecord(
        evaluation_record_id=_required_string(raw, "evaluationRecordId"),
        transaction_reference=_required_string(raw, "transactionReference"),
        feedback_submitted_at=_required_string(raw, "feedbackSubmittedAt"),
        evaluation_label=label,
        projection_status=_required_string(raw, "projectionStatus"),
        ml_risk_level=_optional_string(raw, "mlRiskLevel"),
        ml_score_bucket=_optional_string(raw, "mlScoreBucket"),
        rules_risk_level=_optional_string(raw, "rulesRiskLevel"),
        rules_score_bucket=_optional_string(raw, "rulesScoreBucket"),
        reason_codes=_optional_string_tuple(raw, "reasonCodes"),
        diagnostic_signals=_optional_string_tuple(raw, "diagnosticSignals"),
    )


def evaluation_label_value(record: DatasetRecord) -> int | None:
    if record.evaluation_label == "ANALYST_CONFIRMED_FRAUD":
        return 1
    if record.evaluation_label == "ANALYST_MARKED_LEGITIMATE":
        return 0
    return None


def risk_category(risk_level: str | None, score_bucket: str | None) -> str:
    source = risk_level or score_bucket
    if source is None:
        return "missing"
    normalized = source.upper()
    if normalized in {"CRITICAL", "VERY_HIGH", "HIGH"} or normalized.endswith("_HIGH"):
        return "high"
    return "low_or_medium"


def ml_ranking_score(record: DatasetRecord) -> int:
    source = record.ml_risk_level or record.ml_score_bucket
    if source is None:
        return -1
    normalized = source.upper()
    if normalized == "CRITICAL":
        return 4
    if normalized in {"VERY_HIGH", "HIGH"} or normalized.endswith("_HIGH"):
        return 3
    if "MEDIUM" in normalized:
        return 2
    if "LOW" in normalized:
        return 1
    return 0


def _reject_forbidden_keys(value: Any) -> None:
    if isinstance(value, dict):
        for key, nested in value.items():
            if str(key).replace("_", "").replace("-", "").lower() in FORBIDDEN_FIELD_NAMES:
                raise DatasetValidationError(f"forbidden field: {key}")
            _reject_forbidden_keys(nested)
    elif isinstance(value, list):
        for item in value:
            _reject_forbidden_keys(item)


def _required_string(raw: dict[str, Any], field: str) -> str:
    value = raw.get(field)
    if not isinstance(value, str) or not value:
        raise DatasetValidationError(f"{field} must be a non-empty string")
    return value


def _optional_string(raw: dict[str, Any], field: str) -> str | None:
    value = raw.get(field)
    if value is None:
        return None
    if not isinstance(value, str) or not value:
        raise DatasetValidationError(f"{field} must be a non-empty string when present")
    return value


def _optional_int(raw: dict[str, Any], field: str, minimum: int, maximum: int | None = None) -> int | None:
    value = raw.get(field)
    if value is None:
        return None
    if not isinstance(value, int) or isinstance(value, bool) or value < minimum:
        raise DatasetValidationError(f"{field} must be an integer >= {minimum}")
    if maximum is not None and value > maximum:
        raise DatasetValidationError(f"{field} must be <= {maximum}")
    return value


def _optional_string_tuple(raw: dict[str, Any], field: str) -> tuple[str, ...]:
    value = raw.get(field)
    if value is None:
        return ()
    if not isinstance(value, list) or any(not isinstance(item, str) or not item for item in value):
        raise DatasetValidationError(f"{field} must be a list of non-empty strings")
    return tuple(value)
