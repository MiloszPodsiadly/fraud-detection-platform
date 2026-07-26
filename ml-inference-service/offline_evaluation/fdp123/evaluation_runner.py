from __future__ import annotations

from datetime import UTC, datetime
from pathlib import Path
from typing import Any

from offline_evaluation.fdp123.dataset_reader import read_fdp123_feedback_dataset_jsonl
from offline_evaluation.fdp123.disagreement_report import build_fdp123_disagreement_report
from offline_evaluation.fdp123.evaluation_contract import EVALUATION_SUBJECT, METRIC_BASIS, METRICS_SUBJECT
from offline_evaluation.fdp123.metrics import build_fdp123_metrics
from offline_evaluation.fdp123.models import Fdp123Dataset
from offline_evaluation.fdp123.report_contract import REPORT_TYPE
from offline_evaluation.fdp123.report_writer import write_fdp123_reports
from offline_evaluation.fdp123.timestamp_contract import normalize_rfc3339_timestamp


def build_fdp123_evaluation_reports(
        dataset: Fdp123Dataset,
        generated_at: str | None = None,
) -> dict[str, Any]:
    raw_generated = generated_at
    if raw_generated is None:
        raw_generated = datetime.now(UTC).replace(microsecond=0).isoformat().replace("+00:00", "Z")
    generated = normalize_rfc3339_timestamp(
        raw_generated,
        "generated_at",
    )
    metrics = build_fdp123_metrics(dataset)
    disagreement = build_fdp123_disagreement_report(dataset.records)
    warnings = list(metrics["warnings"])
    if len(set(warnings)) != len(warnings):
        raise ValueError("metrics warnings contains duplicate values")
    warnings = sorted(warnings)
    return {
        "evaluationSummary": {
            "datasetMetadata": dataset.metadata.as_report_dict(),
            "disagreementSummary": disagreement["summary"],
            "evaluationSubject": dict(EVALUATION_SUBJECT),
            "generatedAt": generated,
            "metricBasis": METRIC_BASIS,
            "metricsSubject": METRICS_SUBJECT,
            "qualityMetrics": metrics,
            "reportType": REPORT_TYPE,
            "warnings": warnings,
        },
        "scoreBucketReport": {
            "generatedAt": generated,
            "reportType": REPORT_TYPE,
            "scoreBuckets": metrics["fraudScoreBucketAnalysis"],
            "warnings": warnings,
        },
        "riskLevelReport": {
            "generatedAt": generated,
            "reportType": REPORT_TYPE,
            "riskLevels": metrics["riskLevelBreakdown"],
            "warnings": warnings,
        },
        "disagreementReport": disagreement,
    }


def run_fdp123_evaluation(
        input_path: str | Path,
        output_dir: str | Path,
        generated_at: str | None = None,
        allow_output_root: str | Path | None = None,
) -> dict[str, Path]:
    dataset = read_fdp123_feedback_dataset_jsonl(input_path)
    reports = build_fdp123_evaluation_reports(dataset, generated_at=generated_at)
    root = Path(allow_output_root) if allow_output_root is not None else None
    return write_fdp123_reports(reports, Path(output_dir), allow_output_root=root)
