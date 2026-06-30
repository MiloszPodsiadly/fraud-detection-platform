from __future__ import annotations

from offline_evaluation.fdp123.models import Fdp123DatasetRecord


DEFAULT_MAX_DISAGREEMENT_ROWS = 100
HIGH_RISK_LEVELS = {"HIGH", "CRITICAL"}
LOW_RISK_LEVELS = {"LOW", "MEDIUM"}


def build_fdp123_disagreement_report(
        records: tuple[Fdp123DatasetRecord, ...] | list[Fdp123DatasetRecord],
        max_rows: int = DEFAULT_MAX_DISAGREEMENT_ROWS,
) -> dict[str, object]:
    if max_rows < 1:
        raise ValueError("maxRows must be positive")
    rows = []
    total_matches = 0
    for record in sorted(records, key=lambda item: item.evaluation_record_id):
        types = _disagreement_types(record)
        if not types:
            continue
        total_matches += 1
        if len(rows) >= max_rows:
            continue
        rows.append({
            "alertRecommended": record.alert_recommended,
            "decisionReasonCodes": list(record.decision_reason_codes),
            "evaluationLabel": record.evaluation_label,
            "evaluationRecordId": record.evaluation_record_id,
            "fraudScore": record.fraud_score,
            "riskLevel": record.risk_level,
            "transactionReference": record.transaction_reference,
            "type": types[0] if len(types) == 1 else "MULTIPLE_DISAGREEMENT_SIGNALS",
            "types": types,
        })
    return {
        "rows": rows,
        "summary": {
            "maxRows": max_rows,
            "reportedRows": len(rows),
            "totalDisagreementRows": total_matches,
            "truncated": total_matches > len(rows),
            "typeCounts": _type_counts(records),
        },
    }


def _disagreement_types(record: Fdp123DatasetRecord) -> list[str]:
    types = []
    if record.fraud_score is not None:
        if record.fraud_score >= 0.8 and record.is_negative_class:
            types.append("HIGH_SCORE_NEGATIVE_FEEDBACK")
        if record.fraud_score < 0.2 and record.is_positive_class:
            types.append("LOW_SCORE_POSITIVE_FEEDBACK")
    if record.alert_recommended is True and record.is_negative_class:
        types.append("ALERT_RECOMMENDED_NEGATIVE_FEEDBACK")
    if record.alert_recommended is False and record.is_positive_class:
        types.append("NO_ALERT_POSITIVE_FEEDBACK")
    if record.risk_level in HIGH_RISK_LEVELS and record.is_negative_class:
        types.append("HIGH_RISK_NEGATIVE_FEEDBACK")
    if record.risk_level in LOW_RISK_LEVELS and record.is_positive_class:
        types.append("LOW_RISK_POSITIVE_FEEDBACK")
    return types


def _type_counts(records: tuple[Fdp123DatasetRecord, ...] | list[Fdp123DatasetRecord]) -> dict[str, int]:
    counts = {
        "ALERT_RECOMMENDED_NEGATIVE_FEEDBACK": 0,
        "HIGH_RISK_NEGATIVE_FEEDBACK": 0,
        "HIGH_SCORE_NEGATIVE_FEEDBACK": 0,
        "LOW_RISK_POSITIVE_FEEDBACK": 0,
        "LOW_SCORE_POSITIVE_FEEDBACK": 0,
        "NO_ALERT_POSITIVE_FEEDBACK": 0,
    }
    for record in records:
        for type_ in _disagreement_types(record):
            counts[type_] += 1
    return counts

