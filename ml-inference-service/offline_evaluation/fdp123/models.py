from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True)
class Fdp123DatasetMetadata:
    dataset_version: str
    built_at: str
    time_basis: str
    from_inclusive: str | None
    to_inclusive: str | None
    raw_rows_read: int
    records_returned: int
    excluded_unresolved_count: int
    excluded_governance_review_count: int
    skipped_missing_required_field_count: int
    skipped_invalid_source_record_count: int
    truncated: bool
    failure_reason: str

    def as_report_dict(self) -> dict[str, object]:
        return {
            "builtAt": self.built_at,
            "datasetVersion": self.dataset_version,
            "excludedGovernanceReviewCount": self.excluded_governance_review_count,
            "excludedUnresolvedCount": self.excluded_unresolved_count,
            "failureReason": self.failure_reason,
            "fromInclusive": self.from_inclusive,
            "rawRowsRead": self.raw_rows_read,
            "recordsReturned": self.records_returned,
            "skippedInvalidSourceRecordCount": self.skipped_invalid_source_record_count,
            "skippedMissingRequiredFieldCount": self.skipped_missing_required_field_count,
            "timeBasis": self.time_basis,
            "toInclusive": self.to_inclusive,
            "truncated": self.truncated,
        }


@dataclass(frozen=True)
class Fdp123DatasetRecord:
    dataset_version: str
    evaluation_record_id: str
    transaction_reference: str
    feedback_label: str
    evaluation_label: str
    decision_reason_codes: tuple[str, ...]
    feedback_created_at: str
    fraud_score: float | None
    risk_level: str | None
    alert_recommended: bool | None
    engine_intelligence_status: str | None
    agreement_status: str | None
    risk_mismatch_status: str | None
    score_delta_bucket: str | None
    analyst_recommendation_status: str | None
    analyst_recommendation: str | None
    analyst_recommendation_version: str | None
    analyst_recommendation_generated_at: str | None
    analyst_recommendation_reason_codes: tuple[str, ...]
    scored_at: str | None
    transaction_timestamp: str | None

    @property
    def is_positive_class(self) -> bool:
        return self.evaluation_label == "POSITIVE_FRAUD"

    @property
    def is_negative_class(self) -> bool:
        return self.evaluation_label == "NEGATIVE_LEGITIMATE"

    @property
    def bounded_feedback_outcome(self) -> str:
        return "positive_class" if self.is_positive_class else "negative_class"


@dataclass(frozen=True)
class Fdp123Dataset:
    metadata: Fdp123DatasetMetadata
    records: tuple[Fdp123DatasetRecord, ...]

