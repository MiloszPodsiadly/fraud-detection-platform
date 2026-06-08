from __future__ import annotations

import re
from dataclasses import dataclass
from typing import Any


class DatasetFormatError(ValueError):
    """Raised when JSONL framing does not match the FDP-102 export contract."""


class FailedExportError(ValueError):
    """Raised when FDP-102 metadata declares a failed export."""


class DatasetValidationError(ValueError):
    """Raised when a known FDP-102 field is invalid or unsafe."""


MAX_DATASET_RECORDS = 500
MAX_JSONL_NON_EMPTY_LINES = 501
MAX_JSONL_LINE_LENGTH = 64_000

EVALUATION_RECORD_ID_PATTERN = re.compile(r"^eval-[a-f0-9]{32}$")
TRANSACTION_REFERENCE_PATTERN = re.compile(r"^txnref-[a-f0-9]{32}$")
MACHINE_CODE_PATTERN = re.compile(r"^[A-Z][A-Z0-9_]{0,63}$")

ALLOWED_EVALUATION_LABELS = {
    "ANALYST_CONFIRMED_FRAUD",
    "ANALYST_MARKED_LEGITIMATE",
    "NOT_EVALUATION_ELIGIBLE",
}
ALLOWED_PROJECTION_STATUSES = {"AVAILABLE", "MISSING"}
ALLOWED_LABEL_SOURCES = {
    "ALERT_ANALYST_DECISION",
    "MISSING_ALERT_DECISION",
    "UNKNOWN_ALERT_DECISION",
}
ALLOWED_FEEDBACK_TYPES = {
    "ENGINE_INTELLIGENCE_USEFULNESS",
    "ENGINE_DISAGREEMENT_REVIEW",
    "OPERATIONAL_STATUS_REVIEW",
    "MISSING_INTELLIGENCE_REVIEW",
}
ALLOWED_USEFULNESS = {"HELPFUL", "SOMEWHAT_HELPFUL", "NOT_HELPFUL", "NOT_SURE"}
ALLOWED_ACCURACY_ASSESSMENTS = {
    "SIGNALS_LOOK_CORRECT",
    "SIGNALS_LOOK_PARTIALLY_CORRECT",
    "SIGNALS_LOOK_INCORRECT",
    "NOT_ENOUGH_INFORMATION",
    "OPERATIONAL_ISSUE_AFFECTED_REVIEW",
}
ALLOWED_RISK_LEVELS = {"LOW", "MEDIUM", "HIGH", "CRITICAL"}
ALLOWED_SCORE_BUCKETS = {"NONE", "LOW", "MEDIUM", "HIGH", "VERY_HIGH", "UNAVAILABLE"}
ALLOWED_ENGINE_STATUSES = {
    "AVAILABLE",
    "UNAVAILABLE",
    "DEGRADED",
    "TIMEOUT",
    "FALLBACK_USED",
    "SKIPPED",
}

HIGH_VALUES = {"HIGH", "VERY_HIGH", "CRITICAL"}
LOW_OR_MEDIUM_VALUES = {"LOW", "MEDIUM"}
MISSING_VALUES = {"NONE", "UNAVAILABLE"}

REQUIRED_METADATA_FIELDS = {
    "fromInclusive",
    "toInclusive",
    "exportedAt",
    "maxRecords",
    "rawRowsRead",
    "recordsReturned",
    "truncated",
    "timeBasis",
    "deduplicationPolicy",
    "failureReason",
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
FORBIDDEN_VALUE_COMPACT_TERMS = {
    "customerid",
    "accountid",
    "cardid",
    "deviceid",
    "merchantid",
    "email",
    "endpoint",
    "token",
    "secret",
    "stacktrace",
    "groundtruth",
    "traininglabel",
    "modeltraininglabel",
    "finaldecision",
    "paymentauthorization",
}


@dataclass(frozen=True)
class DatasetMetadata:
    from_inclusive: str
    to_inclusive: str
    exported_at: str
    max_records: int
    raw_rows_read: int
    records_returned: int
    truncated: bool
    time_basis: str
    deduplication_policy: str

    def as_report_dict(self) -> dict[str, Any]:
        return {
            "deduplicationPolicy": "FDP102_BOUNDED_DEDUPLICATION",
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
    ml_engine_status: str | None
    rules_engine_status: str | None
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
        return self.ml_engine_status != "AVAILABLE" or (
            self.ml_risk_level is None and self.ml_score_bucket in {None, "NONE", "UNAVAILABLE"}
        )

    @property
    def rules_signal_missing(self) -> bool:
        return self.rules_engine_status != "AVAILABLE" or (
            self.rules_risk_level is None and self.rules_score_bucket in {None, "NONE", "UNAVAILABLE"}
        )

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
    missing = sorted(REQUIRED_METADATA_FIELDS - raw.keys())
    if missing:
        raise DatasetValidationError(f"metadata missing required fields: {', '.join(missing)}")
    if raw.get("failureReason") is not None:
        raise FailedExportError(str(raw.get("failureReason")))
    time_basis = _required_string(raw, "timeBasis")
    if time_basis != "FEEDBACK_SUBMITTED_AT":
        raise DatasetValidationError("timeBasis must be FEEDBACK_SUBMITTED_AT")
    deduplication_policy = _required_string(raw, "deduplicationPolicy")
    if deduplication_policy != "TRANSACTION_REFERENCE_NEWEST_SUBMITTED_AT_FEEDBACK_ID_ASC":
        raise DatasetValidationError("deduplicationPolicy has unsupported value")
    max_records = _required_int(raw, "maxRecords", minimum=1, maximum=MAX_DATASET_RECORDS)
    raw_rows_read = _required_int(raw, "rawRowsRead", minimum=0)
    records_returned = _required_int(raw, "recordsReturned", minimum=0)
    if records_returned > max_records:
        raise DatasetValidationError("recordsReturned must not exceed maxRecords")
    if raw_rows_read < records_returned:
        raise DatasetValidationError("rawRowsRead must be >= recordsReturned")
    truncated = raw.get("truncated")
    if not isinstance(truncated, bool):
        raise DatasetValidationError("truncated must be boolean")
    return DatasetMetadata(
        from_inclusive=_required_string(raw, "fromInclusive"),
        to_inclusive=_required_string(raw, "toInclusive"),
        exported_at=_required_string(raw, "exportedAt"),
        max_records=max_records,
        raw_rows_read=raw_rows_read,
        records_returned=records_returned,
        truncated=truncated,
        time_basis=time_basis,
        deduplication_policy=deduplication_policy,
    )


def validate_record(raw: dict[str, Any]) -> DatasetRecord:
    if not isinstance(raw, dict):
        raise DatasetValidationError("record must be an object")
    _reject_forbidden_keys(raw)
    missing = sorted(REQUIRED_RECORD_FIELDS - raw.keys())
    if missing:
        raise DatasetValidationError(f"record missing required fields: {', '.join(missing)}")
    _validate_record_semantics(raw)
    ml_engine_status = _optional_enum(raw, "mlEngineStatus", ALLOWED_ENGINE_STATUSES)
    rules_engine_status = _optional_enum(raw, "rulesEngineStatus", ALLOWED_ENGINE_STATUSES)
    ml_risk_level = _optional_enum(raw, "mlRiskLevel", ALLOWED_RISK_LEVELS)
    ml_score_bucket = _optional_enum(raw, "mlScoreBucket", ALLOWED_SCORE_BUCKETS)
    rules_risk_level = _optional_enum(raw, "rulesRiskLevel", ALLOWED_RISK_LEVELS)
    rules_score_bucket = _optional_enum(raw, "rulesScoreBucket", ALLOWED_SCORE_BUCKETS)
    _validate_engine_signal("ml", ml_engine_status, ml_risk_level, ml_score_bucket)
    _validate_engine_signal("rules", rules_engine_status, rules_risk_level, rules_score_bucket)
    return DatasetRecord(
        evaluation_record_id=_required_pattern(raw, "evaluationRecordId", EVALUATION_RECORD_ID_PATTERN),
        transaction_reference=_required_pattern(raw, "transactionReference", TRANSACTION_REFERENCE_PATTERN),
        feedback_submitted_at=_required_string(raw, "feedbackSubmittedAt"),
        evaluation_label=_required_enum(raw, "evaluationLabel", ALLOWED_EVALUATION_LABELS),
        projection_status=_required_enum(raw, "projectionStatus", ALLOWED_PROJECTION_STATUSES),
        ml_engine_status=ml_engine_status,
        rules_engine_status=rules_engine_status,
        ml_risk_level=ml_risk_level,
        ml_score_bucket=ml_score_bucket,
        rules_risk_level=rules_risk_level,
        rules_score_bucket=rules_score_bucket,
        reason_codes=_optional_machine_code_tuple(raw, "reasonCodes"),
        diagnostic_signals=_optional_machine_code_tuple(raw, "diagnosticSignals"),
    )


def validate_record_count(metadata: DatasetMetadata, dataset_record_lines: int) -> None:
    if dataset_record_lines != metadata.records_returned:
        raise DatasetValidationError("dataset record count does not match metadata recordsReturned")
    if dataset_record_lines > metadata.max_records:
        raise DatasetValidationError("dataset record count exceeds metadata maxRecords")
    if dataset_record_lines > MAX_DATASET_RECORDS:
        raise DatasetValidationError("FDP-102 JSONL exceeds maximum dataset records")


def evaluation_label_value(record: DatasetRecord) -> int | None:
    if record.evaluation_label == "ANALYST_CONFIRMED_FRAUD":
        return 1
    if record.evaluation_label == "ANALYST_MARKED_LEGITIMATE":
        return 0
    return None


def risk_category(risk_level: str | None, score_bucket: str | None) -> str:
    source = risk_level or score_bucket
    if source is None or source in MISSING_VALUES:
        return "missing"
    if source in HIGH_VALUES:
        return "high"
    if source in LOW_OR_MEDIUM_VALUES:
        return "low_or_medium"
    raise DatasetValidationError("unsupported risk or score bucket")


def ml_ranking_score(record: DatasetRecord) -> int:
    if record.ml_engine_status != "AVAILABLE":
        return -1
    source = record.ml_risk_level or record.ml_score_bucket
    if source is None or source in MISSING_VALUES:
        return -1
    if source == "CRITICAL":
        return 4
    if source in {"VERY_HIGH", "HIGH"}:
        return 3
    if source == "MEDIUM":
        return 2
    if source == "LOW":
        return 1
    raise DatasetValidationError("unsupported ML risk or score bucket")


def _validate_record_semantics(raw: dict[str, Any]) -> None:
    _required_enum(raw, "labelSource", ALLOWED_LABEL_SOURCES)
    _required_enum(raw, "feedbackType", ALLOWED_FEEDBACK_TYPES)
    _required_enum(raw, "usefulness", ALLOWED_USEFULNESS)
    _required_enum(raw, "accuracyAssessment", ALLOWED_ACCURACY_ASSESSMENTS)


def _validate_engine_signal(
        prefix: str,
        status: str | None,
        risk_level: str | None,
        score_bucket: str | None,
) -> None:
    if status is None:
        return
    if status != "AVAILABLE" and (risk_level is not None or score_bucket is not None):
        raise DatasetValidationError(f"{prefix}EngineStatus {status} must not include risk or score")


def _reject_forbidden_keys(value: Any) -> None:
    if isinstance(value, dict):
        for key, nested in value.items():
            if _compact(str(key)) in FORBIDDEN_FIELD_NAMES:
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


def _required_pattern(raw: dict[str, Any], field: str, pattern: re.Pattern[str]) -> str:
    value = _required_string(raw, field)
    if pattern.fullmatch(value) is None:
        raise DatasetValidationError(f"{field} has unsupported value")
    return value


def _required_enum(raw: dict[str, Any], field: str, allowed: set[str]) -> str:
    value = _required_string(raw, field)
    if value not in allowed:
        raise DatasetValidationError(f"{field} has unsupported value")
    return value


def _optional_enum(raw: dict[str, Any], field: str, allowed: set[str]) -> str | None:
    value = raw.get(field)
    if value is None:
        return None
    if not isinstance(value, str) or not value:
        raise DatasetValidationError(f"{field} must be a non-empty string when present")
    if value not in allowed:
        raise DatasetValidationError(f"{field} has unsupported value")
    return value


def _required_int(raw: dict[str, Any], field: str, minimum: int, maximum: int | None = None) -> int:
    value = raw.get(field)
    if not isinstance(value, int) or isinstance(value, bool) or value < minimum:
        raise DatasetValidationError(f"{field} must be an integer >= {minimum}")
    if maximum is not None and value > maximum:
        raise DatasetValidationError(f"{field} must be <= {maximum}")
    return value


def _optional_machine_code_tuple(raw: dict[str, Any], field: str) -> tuple[str, ...]:
    value = raw.get(field)
    if value is None:
        return ()
    if not isinstance(value, list):
        raise DatasetValidationError(f"{field} must be a list of machine-code strings")
    if len(value) > 10:
        raise DatasetValidationError(f"{field} exceeds maximum item count")
    for item in value:
        _validate_machine_code(item, field)
    return tuple(value)


def _validate_machine_code(value: Any, field: str) -> None:
    if not isinstance(value, str) or not value:
        raise DatasetValidationError(f"{field} must contain non-empty strings")
    if len(value) > 64:
        raise DatasetValidationError(f"{field} contains oversized machine code")
    compact = _compact(value)
    if MACHINE_CODE_PATTERN.fullmatch(value) is None:
        raise DatasetValidationError(f"{field} contains non-machine-code value")
    if ":" in value or "@" in value or "HTTP" in value or "WWW" in value:
        raise DatasetValidationError(f"{field} contains unsafe value")
    if re.search(r"\d{13,19}", value) or re.search(r"[A-Z]{2}\d{2}[A-Z0-9]{11,30}", value):
        raise DatasetValidationError(f"{field} contains raw financial identifier")
    if re.search(r"(CUSTOMER|ACCOUNT|CARD|DEVICE|MERCHANT)_?ID", value):
        raise DatasetValidationError(f"{field} contains raw identifier pattern")
    if any(term in compact for term in FORBIDDEN_VALUE_COMPACT_TERMS):
        raise DatasetValidationError(f"{field} contains forbidden value")


def _compact(value: str) -> str:
    return "".join(character for character in value.lower() if character.isalnum())
