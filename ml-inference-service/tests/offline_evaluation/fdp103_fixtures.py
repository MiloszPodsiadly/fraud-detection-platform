from __future__ import annotations

import json


GENERATED_AT = "2026-06-08T00:00:00Z"


def metadata(**overrides: object) -> dict[str, object]:
    payload: dict[str, object] = {
        "type": "EXPORT_METADATA",
        "fromInclusive": "2026-06-01T00:00:00Z",
        "toInclusive": "2026-06-02T00:00:00Z",
        "exportedAt": "2026-06-02T12:00:00Z",
        "maxRecords": 10,
        "rawRowsRead": 1,
        "recordsReturned": 1,
        "truncated": False,
        "timeBasis": "FEEDBACK_SUBMITTED_AT",
        "deduplicationPolicy": "TRANSACTION_REFERENCE_NEWEST_SUBMITTED_AT_FEEDBACK_ID_ASC",
        "failureReason": None,
    }
    payload.update(overrides)
    return payload


def record(**overrides: object) -> dict[str, object]:
    payload: dict[str, object] = {
        "evaluationRecordId": "eval-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
        "transactionReference": "txnref-bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
        "feedbackSubmittedAt": "2026-06-01T01:00:00Z",
        "evaluationLabel": "ANALYST_CONFIRMED_FRAUD",
        "labelSource": "ALERT_ANALYST_DECISION",
        "feedbackType": "ENGINE_INTELLIGENCE_USEFULNESS",
        "usefulness": "HELPFUL",
        "accuracyAssessment": "SIGNALS_LOOK_CORRECT",
        "projectionStatus": "PRESENT",
        "agreementStatus": "ENGINES_AGREE",
        "riskMismatchStatus": "NONE",
        "scoreDeltaBucket": "SMALL_DELTA",
        "mlEngineStatus": "AVAILABLE",
        "mlScoreBucket": "HIGH",
        "mlRiskLevel": "HIGH",
        "rulesEngineStatus": "AVAILABLE",
        "rulesScoreBucket": "LOW",
        "rulesRiskLevel": "LOW",
        "reasonCodes": ["ML_MODEL_SIGNAL"],
        "diagnosticSignals": ["ML_MODEL_SIGNAL"],
    }
    payload.update(overrides)
    return payload


def jsonl(*records: dict[str, object], metadata_overrides: dict[str, object] | None = None) -> str:
    first = metadata(**(metadata_overrides or {}))
    first["rawRowsRead"] = len(records)
    first["recordsReturned"] = len(records)
    lines = [json.dumps(first, separators=(",", ":"))]
    lines.extend(json.dumps({"type": "DATASET_RECORD", "record": item}, separators=(",", ":")) for item in records)
    return "\n".join(lines) + "\n"
