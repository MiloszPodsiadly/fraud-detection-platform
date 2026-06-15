from __future__ import annotations

import argparse
import json
import os
import sys
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

from offline_evaluation.promotion_readiness_schema import (
    PromotionReadinessValidationError,
    build_promotion_review_readiness_report,
    promotion_review_readiness_report_json,
    validate_promotion_review_readiness_report,
)


REPO_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_SHADOW_SUMMARY = REPO_ROOT / "deployment/local-generated/shadow-performance/current-summary.json"
DEFAULT_OUTPUT_ROOT = REPO_ROOT / "deployment/local-generated/promotion-readiness"
DEFAULT_OUTPUT = DEFAULT_OUTPUT_ROOT / "promotion-review-readiness-report.json"


class PromotionReadinessGenerationError(RuntimeError):
    """Raised when FDP-111 cannot safely publish the local diagnostic report."""


def generate_promotion_readiness_report(
        shadow_summary_path: Path,
        output_path: Path,
        *,
        generated_at: str | None = None,
        minimum_diagnostic_evidence_records: int = 1,
        allowed_output_root: Path | None = None,
) -> Path:
    current_summary = _read_required_json_object(shadow_summary_path, "Shadow Performance Summary")
    timestamp = generated_at or _utc_now()
    report = build_promotion_review_readiness_report(
        current_summary,
        generated_at=timestamp,
        minimum_diagnostic_evidence_records=minimum_diagnostic_evidence_records,
    )
    payload = promotion_review_readiness_report_json(report)
    publish_promotion_readiness_report(payload, output_path, allowed_output_root=allowed_output_root)
    return output_path


def publish_promotion_readiness_report(payload: str, output_path: Path, *, allowed_output_root: Path | None = None) -> Path:
    final_path = _assert_allowed_output_path(output_path, allowed_output_root=allowed_output_root)
    final_path.parent.mkdir(parents=True, exist_ok=True)
    temp_path = final_path.with_name(f"{final_path.name}.tmp")
    if temp_path.is_symlink():
        raise PromotionReadinessGenerationError("temporary output path must not be a symlink")

    temp_path.write_text(payload, encoding="utf-8")
    try:
        validate_promotion_readiness_report_file(temp_path)
        os.replace(temp_path, final_path)
    except Exception:
        temp_path.unlink(missing_ok=True)
        raise
    return final_path


def validate_promotion_readiness_report_file(path: Path) -> dict[str, Any]:
    raw = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(raw, dict):
        raise PromotionReadinessGenerationError("promotion review readiness report must be a JSON object")
    try:
        return validate_promotion_review_readiness_report(raw)
    except PromotionReadinessValidationError as exc:
        raise PromotionReadinessGenerationError(str(exc)) from exc


def main(argv: list[str] | None = None) -> int:
    parser = _parser()
    args = parser.parse_args(argv)
    try:
        output_path = generate_promotion_readiness_report(
            Path(args.shadow_summary),
            Path(args.output),
            generated_at=args.generated_at,
            minimum_diagnostic_evidence_records=args.minimum_diagnostic_evidence_records,
            allowed_output_root=Path(args.allow_output_root) if args.allow_output_root else None,
        )
    except Exception as exc:
        print(f"promotion review readiness report generation failed: {exc}", file=sys.stderr)
        return 1
    print(f"wrote {output_path}")
    return 0


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Generate a non-decisioning PromotionReviewReadinessReport v1 artifact for FDP-111."
    )
    parser.add_argument("--shadow-summary", default=str(DEFAULT_SHADOW_SUMMARY))
    parser.add_argument("--output", default=str(DEFAULT_OUTPUT))
    parser.add_argument("--allow-output-root")
    parser.add_argument("--generated-at")
    parser.add_argument("--minimum-diagnostic-evidence-records", type=int, default=1)
    return parser


def _read_required_json_object(path: Path, label: str) -> dict[str, Any]:
    if not path.is_file():
        raise PromotionReadinessGenerationError(f"{label} is missing")
    raw = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(raw, dict):
        raise PromotionReadinessGenerationError(f"{label} must be a JSON object")
    return raw


def _assert_allowed_output_path(output_path: Path, *, allowed_output_root: Path | None = None) -> Path:
    final_path = Path(output_path)
    if final_path.name != "promotion-review-readiness-report.json":
        raise PromotionReadinessGenerationError("output path must end with promotion-review-readiness-report.json")
    if final_path.parent.exists() and final_path.parent.is_symlink():
        raise PromotionReadinessGenerationError("output directory must not be a symlink")
    if final_path.exists() and final_path.is_symlink():
        raise PromotionReadinessGenerationError("final output path must not be a symlink")

    resolved_output = final_path.resolve(strict=False)
    resolved_root = Path(allowed_output_root or DEFAULT_OUTPUT_ROOT).resolve(strict=False)
    if resolved_output.parent != resolved_root:
        raise PromotionReadinessGenerationError("output path must be under deployment/local-generated/promotion-readiness")
    return final_path


def _utc_now() -> str:
    return datetime.now(UTC).replace(microsecond=0).isoformat().replace("+00:00", "Z")


if __name__ == "__main__":
    raise SystemExit(main())
