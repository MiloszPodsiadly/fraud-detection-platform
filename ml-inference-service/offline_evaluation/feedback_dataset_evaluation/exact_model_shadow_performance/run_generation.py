from __future__ import annotations

import argparse

from offline_evaluation.feedback_dataset_evaluation.exact_model_shadow_performance.generator import (
    build_exact_model_shadow_performance,
)
from offline_evaluation.feedback_dataset_evaluation.exact_model_shadow_performance.publisher import (
    publish_exact_model_shadow_artifact_set,
)
from offline_evaluation.feedback_dataset_evaluation.model_evaluation_artifact_set import (
    read_validated_model_evaluation_artifact_set,
)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Publish immutable Shadow Performance evidence for one exact model artifact."
    )
    parser.add_argument("--model-evaluation-dir", required=True)
    parser.add_argument("--artifact-root", required=True)
    parser.add_argument("--generated-at", required=True)
    parser.add_argument("--source-dataset")
    args = parser.parse_args(argv)

    evidence = read_validated_model_evaluation_artifact_set(
        args.model_evaluation_dir,
        args.source_dataset,
    )
    summary = build_exact_model_shadow_performance(evidence, args.generated_at)
    publish_exact_model_shadow_artifact_set(summary, args.artifact_root)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
