from __future__ import annotations

import argparse

from offline_evaluation.feedback_dataset_evaluation.model_card.generator import (
    generate_model_card_artifact_set,
)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Generate an exact-artifact ML Model Card from trusted offline evidence."
    )
    parser.add_argument("--model-artifact", required=True)
    parser.add_argument("--output-dir", required=True)
    parser.add_argument("--allow-output-root", required=True)
    parser.add_argument("--generated-at", required=True)
    parser.add_argument("--model-evaluation-dir")
    parser.add_argument("--source-dataset")
    args = parser.parse_args(argv)

    generate_model_card_artifact_set(
        model_artifact_path=args.model_artifact,
        output_dir=args.output_dir,
        generated_at=args.generated_at,
        model_evaluation_dir=args.model_evaluation_dir,
        source_dataset_path=args.source_dataset,
        allow_output_root=args.allow_output_root,
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
