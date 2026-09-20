from __future__ import annotations

from datetime import UTC, datetime
from pathlib import Path
from typing import Any

from offline_evaluation.feedback_dataset_evaluation.dataset_reader import read_feedback_dataset_jsonl
from offline_evaluation.feedback_dataset_evaluation.disagreement_report import build_feedback_dataset_disagreement_report
from offline_evaluation.feedback_dataset_evaluation.evaluation_contract import EVALUATION_SUBJECT, METRIC_BASIS, METRICS_SUBJECT
from offline_evaluation.feedback_dataset_evaluation.metrics import build_feedback_dataset_metrics
from offline_evaluation.feedback_dataset_evaluation.model_evaluation import (
    ModelEvaluationIdentity,
    build_model_specific_evaluation_summary,
)
from offline_evaluation.feedback_dataset_evaluation.models import FeedbackDataset
from offline_evaluation.feedback_dataset_evaluation.report_contract import REPORT_TYPE
from offline_evaluation.feedback_dataset_evaluation.report_writer import write_feedback_dataset_evaluation_reports
from offline_evaluation.feedback_dataset_evaluation.timestamp_contract import normalize_rfc3339_timestamp


def build_feedback_dataset_evaluation_reports(
        dataset: FeedbackDataset,
        generated_at: str | None = None,
        model_identity: ModelEvaluationIdentity | None = None,
) -> dict[str, Any]:
    raw_generated = generated_at
    if raw_generated is None:
        raw_generated = datetime.now(UTC).replace(microsecond=0).isoformat().replace("+00:00", "Z")
    generated = normalize_rfc3339_timestamp(
        raw_generated,
        "generated_at",
    )
    metrics = build_feedback_dataset_metrics(dataset)
    disagreement = build_feedback_dataset_disagreement_report(dataset.records)
    warnings = list(metrics["warnings"])
    if len(set(warnings)) != len(warnings):
        raise ValueError("metrics warnings contains duplicate values")
    warnings = sorted(warnings)
    reports = {
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
    if model_identity is not None:
        reports["modelEvaluationSummary"] = build_model_specific_evaluation_summary(
            dataset,
            model_identity,
            generated,
        )
    return reports


def run_feedback_dataset_evaluation(
        input_path: str | Path,
        output_dir: str | Path,
        generated_at: str | None = None,
        allow_output_root: str | Path | None = None,
        model_identity: ModelEvaluationIdentity | None = None,
) -> dict[str, Path]:
    dataset = read_feedback_dataset_jsonl(input_path)
    reports = build_feedback_dataset_evaluation_reports(
        dataset,
        generated_at=generated_at,
        model_identity=model_identity,
    )
    root = Path(allow_output_root) if allow_output_root is not None else None
    return write_feedback_dataset_evaluation_reports(reports, Path(output_dir), allow_output_root=root)
