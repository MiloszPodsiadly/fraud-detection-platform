from __future__ import annotations

import json
import tempfile
from contextlib import contextmanager
from pathlib import Path


GENERATED_AT = "2026-06-10T00:00:00Z"


def metadata(**overrides: object) -> dict[str, object]:
    payload: dict[str, object] = {
        "type": "DATASET_METADATA",
        "datasetVersion": "feedback-dataset-v1",
        "builtAt": "2026-06-10T00:00:00Z",
        "timeBasis": "FEEDBACK_CREATED_AT",
        "fromInclusive": "2026-06-01T00:00:00Z",
        "toInclusive": "2026-06-09T23:59:59Z",
        "rawRowsRead": 1,
        "recordsReturned": 1,
        "excludedUnresolvedCount": 0,
        "excludedGovernanceReviewCount": 0,
        "skippedMissingRequiredFieldCount": 0,
        "skippedInvalidSourceRecordCount": 0,
        "truncated": False,
        "failureReason": "NONE",
    }
    payload.update(overrides)
    return payload


def record(**overrides: object) -> dict[str, object]:
    payload: dict[str, object] = {
        "datasetVersion": "feedback-dataset-v1",
        "evaluationRecordId": "eval_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
        "transactionReference": "txnref_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
        "feedbackLabel": "CONFIRMED_FRAUD",
        "evaluationLabel": "POSITIVE_FRAUD",
        "decisionReasonCodes": ["CUSTOMER_CONFIRMED_FRAUD"],
        "feedbackCreatedAt": "2026-06-03T12:00:00Z",
        "fraudScore": 0.91,
        "riskLevel": "HIGH",
        "alertRecommended": True,
        "engineIntelligenceStatus": "AVAILABLE",
        "agreementStatus": "ENGINES_AGREE",
        "riskMismatchStatus": "NONE",
        "scoreDeltaBucket": "SMALL_DELTA",
        "analystRecommendationStatus": "GENERATED",
        "analystRecommendation": "REVIEW_TRANSACTION",
        "analystRecommendationVersion": "v1",
        "analystRecommendationGeneratedAt": "2026-06-03T12:00:01Z",
        "analystRecommendationReasonCodes": ["MODEL_SIGNAL"],
        "scoredAt": "2026-06-03T11:59:00Z",
        "transactionTimestamp": "2026-06-03T11:58:00Z",
    }
    payload.update(overrides)
    if "decisionReasonCodes" not in overrides and payload["feedbackLabel"] == "CONFIRMED_LEGITIMATE":
        payload["decisionReasonCodes"] = ["CUSTOMER_CONFIRMED_LEGITIMATE"]
    return payload


def jsonl(*records: dict[str, object], metadata_overrides: dict[str, object] | None = None) -> str:
    first = metadata()
    first["rawRowsRead"] = len(records)
    first["recordsReturned"] = len(records)
    first.update(metadata_overrides or {})
    lines = [json.dumps(first, separators=(",", ":"))]
    lines.extend(json.dumps({"type": "DATASET_RECORD", "record": item}, separators=(",", ":")) for item in records)
    return "\n".join(lines) + "\n"


@contextmanager
def jsonl_file(payload: str):
    with tempfile.TemporaryDirectory() as directory:
        path = Path(directory) / "fdp123.jsonl"
        path.write_text(payload, encoding="utf-8")
        yield path
