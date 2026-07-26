from __future__ import annotations

import argparse
import json
import os
import sys
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

from offline_evaluation.json_contract import JsonContractError, loads_strict_json
from offline_evaluation.fdp123.evaluation_card.artifact_reader import read_validated_evaluation_card_artifact_set
from offline_evaluation.fdp123.evaluation_card.schema import Fdp123EvaluationCardValidationError
from offline_evaluation.shadow_performance_summary import build_shadow_performance_summary
from offline_evaluation.shadow_performance_artifact_set import publish_shadow_performance_artifact_set
from offline_evaluation.shadow_performance_writer import write_shadow_performance_summary


REPO_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_EVALUATION_CARD = (
    REPO_ROOT
    / "deployment/local-generated/platform-recommendation-evaluation-card/platform_recommendation_evaluation_card.json"
)
DEFAULT_EVALUATION_CARD_MANIFEST = DEFAULT_EVALUATION_CARD.with_name("manifest.json")
DEFAULT_OUTPUT_ROOT = REPO_ROOT / "deployment/local-generated/shadow-performance"
DEFAULT_OUTPUT = DEFAULT_OUTPUT_ROOT / "current-summary.json"
FORBIDDEN_GENERATION_INPUT_SEGMENTS = ("deployment", "local-fixtures")
MAX_CURRENT_SUMMARY_BYTES = 262_144


class CurrentSummaryGenerationError(RuntimeError):
    """Raised when the manual current-summary generation job cannot safely publish output."""


def generate_current_shadow_summary(
        evaluation_card_path: Path,
        evaluation_card_manifest_path: Path,
        output_path: Path,
        *,
        generated_at: str | None = None,
        allowed_output_root: Path | None = None,
) -> Path:
    _reject_forbidden_generation_input(evaluation_card_path)
    _reject_forbidden_generation_input(evaluation_card_manifest_path)
    try:
        safe_card, card_manifest_sha256 = read_validated_evaluation_card_artifact_set(
            evaluation_card_path,
            evaluation_card_manifest_path,
        )
    except Fdp123EvaluationCardValidationError as exc:
        raise CurrentSummaryGenerationError(str(exc)) from exc
    timestamp = generated_at or _utc_now()
    summary = build_shadow_performance_summary(
        safe_card,
        timestamp,
        source_evaluation_card_manifest_sha256=card_manifest_sha256,
    )
    payload = write_shadow_performance_summary(summary)
    publish_current_summary(payload, output_path, allowed_output_root=allowed_output_root)
    return output_path


def publish_current_summary(payload: str, output_path: Path, *, allowed_output_root: Path | None = None) -> Path:
    final_path = _assert_allowed_output_path(output_path, allowed_output_root=allowed_output_root)
    final_path.parent.mkdir(parents=True, exist_ok=True)
    temp_path = final_path.with_name(f"{final_path.name}.tmp")
    if temp_path.is_symlink():
        raise CurrentSummaryGenerationError("temporary output path must not be a symlink")

    temp_path.write_text(payload, encoding="utf-8")
    try:
        validate_current_summary_file(temp_path)
        publish_shadow_performance_artifact_set(payload, final_path)
        temp_path.unlink(missing_ok=True)
    except Exception:
        temp_path.unlink(missing_ok=True)
        raise
    return final_path


def validate_current_summary_file(path: Path) -> dict[str, Any]:
    raw = _read_required_json_object(path, "current summary", MAX_CURRENT_SUMMARY_BYTES)
    if not isinstance(raw, dict):
        raise CurrentSummaryGenerationError("current summary must be a JSON object")
    from offline_evaluation.shadow_performance_schema import validate_shadow_performance_summary

    return validate_shadow_performance_summary(raw)


def main(argv: list[str] | None = None) -> int:
    parser = _parser()
    args = parser.parse_args(argv)
    try:
        output_path = generate_current_shadow_summary(
            Path(args.evaluation_card),
            Path(args.evaluation_card_manifest),
            Path(args.output),
            generated_at=args.generated_at,
            allowed_output_root=Path(args.allow_output_root) if args.allow_output_root else None,
        )
    except Exception as exc:
        print(f"shadow performance summary generation failed: {exc}", file=sys.stderr)
        return 1
    print(f"wrote {output_path}")
    return 0


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Generate a validated Shadow Performance Summary v2 current artifact for FDP-126."
    )
    parser.add_argument("--evaluation-card", default=str(DEFAULT_EVALUATION_CARD))
    parser.add_argument("--evaluation-card-manifest", default=str(DEFAULT_EVALUATION_CARD_MANIFEST))
    parser.add_argument("--output", default=str(DEFAULT_OUTPUT))
    parser.add_argument("--allow-output-root")
    parser.add_argument("--generated-at")
    return parser


def _read_required_json_object(path: Path, label: str, max_bytes: int) -> dict[str, Any]:
    if not path.is_file():
        raise CurrentSummaryGenerationError(f"{label} is missing")
    with path.open("rb") as handle:
        payload = handle.read(max_bytes + 1)
    if len(payload) > max_bytes:
        raise CurrentSummaryGenerationError(f"{label} exceeds maximum byte size")
    try:
        raw = loads_strict_json(payload)
    except (UnicodeDecodeError, json.JSONDecodeError, JsonContractError) as exc:
        raise CurrentSummaryGenerationError(f"{label} must be valid JSON") from exc
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
