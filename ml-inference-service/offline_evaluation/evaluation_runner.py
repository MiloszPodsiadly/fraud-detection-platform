from __future__ import annotations

from datetime import UTC, datetime

from offline_evaluation.dataset_reader import read_fdp102_jsonl
from offline_evaluation.dataset_schema import ParsedDataset
from offline_evaluation.disagreement_report import build_disagreement_report
from offline_evaluation.quality_metrics import build_quality_metrics


def build_input_summary(parsed: ParsedDataset, generated_at: str | None = None, malformed_excluded: int = 0) -> dict[str, object]:
    generated = generated_at or datetime.now(UTC).replace(microsecond=0).isoformat().replace("+00:00", "Z")
    return {
        "datasetRecordsRead": parsed.dataset_records_read,
        "evaluationGeneratedAt": generated,
        "exportMetadata": parsed.metadata.as_report_dict(),
        "metadataLinesRead": parsed.metadata_lines_read,
        "recordsAcceptedForEvaluation": sum(1 for record in parsed.records if record.is_evaluation_eligible),
        "recordsExcludedAsMalformed": 0,
        "recordsExcludedDueToMissingRequiredEvaluationFields": 0,
        "recordsExcludedNotEvaluationEligible": sum(1 for record in parsed.records if not record.is_evaluation_eligible),
        "recordsWithMissingMlSignal": sum(1 for record in parsed.records if record.ml_signal_missing),
        "recordsWithMissingProjection": sum(1 for record in parsed.records if record.projection_missing),
        "recordsWithMissingRulesSignal": sum(1 for record in parsed.records if record.rules_signal_missing),
        "totalLinesRead": parsed.total_lines_read,
    }


def build_evaluation_report(jsonl: str, review_budget: int = 10, top_k: int = 10, generated_at: str | None = None) -> dict[str, object]:
    parsed = read_fdp102_jsonl(jsonl)
    input_summary = build_input_summary(parsed, generated_at)
    return {
        "disagreementSummary": build_disagreement_report(parsed.records),
        "exclusions": {
            "malformed": input_summary["recordsExcludedAsMalformed"],
            "missingRequiredEvaluationFields": input_summary["recordsExcludedDueToMissingRequiredEvaluationFields"],
            "notEvaluationEligible": input_summary["recordsExcludedNotEvaluationEligible"],
        },
        "generatedAt": input_summary["evaluationGeneratedAt"],
        "inputSummary": input_summary,
        "qualityMetrics": build_quality_metrics(parsed.records, review_budget, top_k),
        "reportType": "PYTHON_ML_EVALUATION_FOUNDATION",
        "warnings": _warnings(parsed),
    }


def _warnings(parsed: ParsedDataset) -> list[str]:
    warnings = []
    if any(record.ml_signal_missing for record in parsed.records):
        warnings.append("MISSING_ML_SIGNAL_PRESENT")
    if any(record.rules_signal_missing for record in parsed.records):
        warnings.append("MISSING_RULES_SIGNAL_PRESENT")
    if any(record.projection_missing for record in parsed.records):
        warnings.append("MISSING_PROJECTION_PRESENT")
    return sorted(warnings)[:10]
