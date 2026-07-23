from __future__ import annotations

import argparse
import json
import os
import sys
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

from offline_evaluation.evaluation_runner import build_evaluation_report
from offline_evaluation.shadow_performance_schema import (
    BANNER,
    EXPECTED_DATASET_DEDUPLICATION_POLICY,
    EXPECTED_DATASET_TIME_BASIS,
    EXPECTED_EVALUATION_REPORT_TYPE,
    EXPECTED_EVALUATION_REPORT_VERSION,
    EXPECTED_GOVERNANCE_STATUS,
    EXPECTED_METRIC_BASIS,
    SUMMARY_TYPE,
    SUMMARY_VERSION,
    validate_shadow_performance_summary,
)
from offline_evaluation.shadow_performance_writer import write_shadow_performance_summary


REPO_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_DATASET_JSONL = REPO_ROOT / "deployment/local-demo-inputs/shadow-performance/fdp102-feedback-dataset.synthetic.jsonl"
DEFAULT_MODEL_METADATA = REPO_ROOT / "deployment/local-demo-inputs/shadow-performance/model-metadata.synthetic.json"
DEFAULT_OUTPUT_ROOT = REPO_ROOT / "deployment/local-generated/shadow-performance"
DEFAULT_OUTPUT = DEFAULT_OUTPUT_ROOT / "current-summary.json"
FORBIDDEN_GENERATION_INPUT_SEGMENTS = ("deployment", "local-fixtures")


class CurrentSummaryGenerationError(RuntimeError):
    """Raised when the manual current-summary generation job cannot safely publish output."""


def generate_current_shadow_summary(
        dataset_jsonl_path: Path,
        model_metadata_path: Path,
        output_path: Path,
        *,
        generated_at: str | None = None,
        review_budget: int = 10,
        top_k: int = 10,
        allowed_output_root: Path | None = None,
) -> Path:
    dataset_jsonl = _read_required_text_input(dataset_jsonl_path, "FDP-102 dataset JSONL")
    model_metadata = _read_required_json_object(model_metadata_path, "model metadata")
    timestamp = generated_at or _utc_now()

    evaluation_report = build_evaluation_report(
        dataset_jsonl,
        review_budget=review_budget,
        top_k=top_k,
        generated_at=timestamp,
    )
    summary = build_shadow_performance_summary_from_evaluation_report(evaluation_report, model_metadata, timestamp)
    payload = write_shadow_performance_summary(summary)

    publish_current_summary(payload, output_path, allowed_output_root=allowed_output_root)
    return output_path


def build_shadow_performance_summary_from_evaluation_report(
        evaluation_report: dict[str, Any],
        governance_metadata: dict[str, Any],
        generated_at: str,
) -> dict[str, Any]:
    input_summary = _required_object(evaluation_report, "inputSummary")
    quality_metrics = _required_object(evaluation_report, "qualityMetrics")
    disagreement_summary = _required_object(evaluation_report, "disagreementSummary")
    approved_for = _approved_for(governance_metadata)
    summary = {
        "summaryType": SUMMARY_TYPE,
        "summaryVersion": SUMMARY_VERSION,
        "generatedAt": generated_at,
        "model": {
            "modelName": "NOT_AVAILABLE",
            "modelVersion": "NOT_AVAILABLE",
            "modelFamily": "PLATFORM_RECOMMENDATION",
            "featureContractVersion": "NOT_APPLICABLE",
        },
        "governance": {
            "governanceStatus": EXPECTED_GOVERNANCE_STATUS,
            "approvedFor": approved_for,
            "diagnosticOnly": True,
            "notProductionApproval": True,
            "notPromotionApproval": True,
            "notThresholdRecommendation": True,
            "notPaymentAuthorization": True,
            "notAutomaticDecisioning": True,
        },
        "evaluation": {
            "evaluationReportType": EXPECTED_EVALUATION_REPORT_TYPE,
            "evaluationReportVersion": EXPECTED_EVALUATION_REPORT_VERSION,
            "metricBasis": EXPECTED_METRIC_BASIS,
            "datasetTimeBasis": EXPECTED_DATASET_TIME_BASIS,
            "datasetDeduplicationPolicy": EXPECTED_DATASET_DEDUPLICATION_POLICY,
        },
        "evaluationPopulation": {
            "datasetRecordsRead": input_summary.get("datasetRecordsRead"),
            "recordsAcceptedForEvaluation": input_summary.get("recordsAcceptedForEvaluation"),
            "recordsExcludedNotEvaluationEligible": input_summary.get("recordsExcludedNotEvaluationEligible"),
        },
        "metrics": {
            "precisionAtBudget": quality_metrics.get("precisionAtBudget"),
            "recallAtTopK": quality_metrics.get("recallAtTopK"),
            "falsePositiveRate": quality_metrics.get("falsePositiveRate"),
            "mlCaughtRulesMissedCount": quality_metrics.get("mlCaughtRulesMissedCount"),
            "rulesCaughtMlMissedCount": quality_metrics.get("rulesCaughtMlMissedCount"),
            "missingMlCount": quality_metrics.get("missingMlCount"),
            "missingRulesCount": quality_metrics.get("missingRulesCount"),
            "missingProjectionCount": input_summary.get("recordsWithMissingProjection"),
            "notEvaluationEligibleCount": quality_metrics.get("notEvaluationEligibleCount"),
        },
        "disagreementSummary": {
            "rulesHighMlHigh": disagreement_summary.get("rulesHighMlHigh"),
            "rulesHighMlLowOrMedium": disagreement_summary.get("rulesHighMlLowOrMedium"),
            "rulesLowOrMediumMlHigh": disagreement_summary.get("rulesLowOrMediumMlHigh"),
            "rulesLowOrMediumMlLowOrMedium": disagreement_summary.get("rulesLowOrMediumMlLowOrMedium"),
            "rulesMissingMlPresent": disagreement_summary.get("rulesMissingMlPresent"),
            "mlMissingRulesPresent": disagreement_summary.get("mlMissingRulesPresent"),
            "bothMissing": disagreement_summary.get("bothMissing"),
            "notEvaluationEligibleExcluded": disagreement_summary.get("notEvaluationEligibleExcluded"),
        },
        "warnings": _machine_code_list(evaluation_report.get("warnings", [])),
        "limitations": [
            "ANALYST_LABELS_ARE_EVALUATION_SIGNALS_NOT_GROUND_TRUTH",
            "BUCKET_ORDERED_METRICS_NOT_CALIBRATED_PROBABILITIES",
            "DIAGNOSTIC_ONLY",
            "METRICS_ARE_PLATFORM_RECOMMENDATION_DIAGNOSTICS",
            "NO_AUTOMATIC_APPROVE_DECLINE_BLOCK",
            "NO_MODEL_PROMOTION_APPROVAL",
            "NO_PAYMENT_AUTHORIZATION",
            "NO_PRODUCTION_DECISIONING_APPROVAL",
            "NO_THRESHOLD_RECOMMENDATION",
            "NOT_EVALUATION_ELIGIBLE_EXCLUDED_FROM_QUALITY_METRICS",
            "OFFLINE_ONLY",
        ],
        "banner": BANNER,
    }
    return validate_shadow_performance_summary(summary)


def publish_current_summary(payload: str, output_path: Path, *, allowed_output_root: Path | None = None) -> Path:
    final_path = _assert_allowed_output_path(output_path, allowed_output_root=allowed_output_root)
    final_path.parent.mkdir(parents=True, exist_ok=True)
    temp_path = final_path.with_name(f"{final_path.name}.tmp")
    if temp_path.is_symlink():
        raise CurrentSummaryGenerationError("temporary output path must not be a symlink")

    temp_path.write_text(payload, encoding="utf-8")
    try:
        validate_current_summary_file(temp_path)
        os.replace(temp_path, final_path)
    except Exception:
        temp_path.unlink(missing_ok=True)
        raise
    return final_path


def validate_current_summary_file(path: Path) -> dict[str, Any]:
    raw = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(raw, dict):
        raise CurrentSummaryGenerationError("current summary must be a JSON object")
    return validate_shadow_performance_summary(raw)


def _required_object(raw: dict[str, Any], field: str) -> dict[str, Any]:
    value = raw.get(field)
    if not isinstance(value, dict):
        raise CurrentSummaryGenerationError(f"{field} must be an object")
    return value


def _approved_for(governance_metadata: dict[str, Any]) -> list[str]:
    values = governance_metadata.get("approvedFor")
    if not isinstance(values, list) or not values:
        raise CurrentSummaryGenerationError("approvedFor must be a non-empty list")
    return sorted(str(item) for item in values)


def _machine_code_list(raw: Any) -> list[str]:
    if raw is None:
        return []
    if not isinstance(raw, list):
        raise CurrentSummaryGenerationError("warnings must be a list")
    return sorted(str(item) for item in raw)


def main(argv: list[str] | None = None) -> int:
    parser = _parser()
    args = parser.parse_args(argv)
    try:
        output_path = generate_current_shadow_summary(
            Path(args.dataset_jsonl),
            Path(args.model_metadata),
            Path(args.output),
            generated_at=args.generated_at,
            review_budget=args.review_budget,
            top_k=args.top_k,
            allowed_output_root=Path(args.allow_output_root) if args.allow_output_root else None,
        )
    except Exception as exc:
        print(f"shadow performance summary generation failed: {exc}", file=sys.stderr)
        return 1
    print(f"wrote {output_path}")
    return 0


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Generate a validated ShadowPerformanceSummary v1 current artifact for FDP-108."
    )
    parser.add_argument("--dataset-jsonl", default=str(DEFAULT_DATASET_JSONL))
    parser.add_argument("--model-metadata", default=str(DEFAULT_MODEL_METADATA))
    parser.add_argument("--output", default=str(DEFAULT_OUTPUT))
    parser.add_argument("--allow-output-root")
    parser.add_argument("--generated-at")
    parser.add_argument("--review-budget", type=int, default=10)
    parser.add_argument("--top-k", type=int, default=10)
    return parser


def _read_required_text_input(path: Path, label: str) -> str:
    _reject_forbidden_generation_input(path)
    if not path.is_file():
        raise CurrentSummaryGenerationError(f"{label} is missing")
    return path.read_text(encoding="utf-8")


def _read_required_json_object(path: Path, label: str) -> dict[str, Any]:
    _reject_forbidden_generation_input(path)
    if not path.is_file():
        raise CurrentSummaryGenerationError(f"{label} is missing")
    raw = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(raw, dict):
        raise CurrentSummaryGenerationError(f"{label} must be a JSON object")
    return raw


def _reject_forbidden_generation_input(path: Path) -> None:
    normalized_parts = tuple(part.lower() for part in Path(path).parts)
    if _contains_ordered_segments(normalized_parts, FORBIDDEN_GENERATION_INPUT_SEGMENTS):
        raise CurrentSummaryGenerationError("generation input must not come from deployment/local-fixtures")


def _assert_allowed_output_path(output_path: Path, *, allowed_output_root: Path | None = None) -> Path:
    final_path = Path(output_path)
    if final_path.name != "current-summary.json":
        raise CurrentSummaryGenerationError("output path must end with current-summary.json")
    if final_path.parent.exists() and final_path.parent.is_symlink():
        raise CurrentSummaryGenerationError("output directory must not be a symlink")
    if final_path.exists() and final_path.is_symlink():
        raise CurrentSummaryGenerationError("final output path must not be a symlink")

    resolved_output = final_path.resolve(strict=False)
    resolved_root = Path(allowed_output_root or DEFAULT_OUTPUT_ROOT).resolve(strict=False)
    if resolved_output.parent != resolved_root:
        raise CurrentSummaryGenerationError("output path must be under deployment/local-generated/shadow-performance")
    return final_path


def _contains_ordered_segments(parts: tuple[str, ...], segments: tuple[str, ...]) -> bool:
    if not segments:
        return True
    position = 0
    for part in parts:
        if part == segments[position]:
            position += 1
            if position == len(segments):
                return True
    return False


def _utc_now() -> str:
    return datetime.now(UTC).replace(microsecond=0).isoformat().replace("+00:00", "Z")


if __name__ == "__main__":
    raise SystemExit(main())
