from __future__ import annotations

import argparse
from pathlib import Path

from offline_evaluation.feedback_dataset_evaluation.evaluation_runner import run_feedback_dataset_evaluation
from offline_evaluation.feedback_dataset_evaluation.model_evaluation import ModelEvaluationIdentity


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Run offline feedback dataset evaluation.")
    parser.add_argument("--input", required=True, help="Path to feedback dataset JSONL.")
    parser.add_argument("--output-dir", required=True, help="Directory for local report artifacts.")
    parser.add_argument("--generated-at", help="Optional deterministic generatedAt timestamp for local review artifacts.")
    parser.add_argument("--allow-output-root", help="Optional root directory that output-dir must stay within.")
    parser.add_argument("--model-name", help="Exact ML model name for optional model-specific evaluation.")
    parser.add_argument("--model-version", help="Exact ML model version for optional model-specific evaluation.")
    parser.add_argument("--feature-contract-version", help="Exact ML feature contract version for optional model-specific evaluation.")
    args = parser.parse_args(argv)
    model_identity_args = (args.model_name, args.model_version, args.feature_contract_version)
    if any(model_identity_args) and not all(model_identity_args):
        parser.error("--model-name, --model-version, and --feature-contract-version must be provided together")
    run_feedback_dataset_evaluation(
        Path(args.input),
        Path(args.output_dir),
        generated_at=args.generated_at,
        allow_output_root=Path(args.allow_output_root) if args.allow_output_root else None,
        model_identity=ModelEvaluationIdentity(*model_identity_args) if all(model_identity_args) else None,
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
