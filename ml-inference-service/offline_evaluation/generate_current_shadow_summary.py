from __future__ import annotations

import argparse
import json
import os
import sys
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

from offline_evaluation.evaluation_runner import build_evaluation_report
from offline_evaluation.model_card_generator import build_model_card
from offline_evaluation.model_card_writer import model_card_json
from offline_evaluation.shadow_performance_schema import validate_shadow_performance_summary
from offline_evaluation.shadow_performance_summary import build_shadow_performance_summary
from offline_evaluation.shadow_performance_writer import write_shadow_performance_summary


REPO_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_DATASET_JSONL = REPO_ROOT / "deployment/local-inputs/shadow-performance/fdp102-feedback-dataset.jsonl"
DEFAULT_MODEL_METADATA = REPO_ROOT / "deployment/local-inputs/shadow-performance/model-metadata.json"
DEFAULT_OUTPUT = REPO_ROOT / "deployment/local-generated/shadow-performance/current-summary.json"
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
    model_card = build_model_card(evaluation_report, model_metadata, timestamp)
    safe_model_card = json.loads(model_card_json(model_card))
    summary = build_shadow_performance_summary(safe_model_card, timestamp)
    payload = write_shadow_performance_summary(summary)

    publish_current_summary(payload, output_path)
    return output_path


def publish_current_summary(payload: str, output_path: Path) -> Path:
    final_path = Path(output_path)
    if final_path.name != "current-summary.json":
        raise CurrentSummaryGenerationError("output path must end with current-summary.json")
    final_path.parent.mkdir(parents=True, exist_ok=True)
    temp_path = final_path.with_name(f"{final_path.name}.tmp")

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
