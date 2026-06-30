from __future__ import annotations

import argparse
from pathlib import Path

from offline_evaluation.fdp123.evaluation_runner import run_fdp123_evaluation


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Run offline FDP-123 feedback dataset evaluation.")
    parser.add_argument("--input", required=True, help="Path to FDP-123 feedback dataset JSONL.")
    parser.add_argument("--output-dir", required=True, help="Directory for local report artifacts.")
    parser.add_argument("--generated-at", help="Optional deterministic generatedAt timestamp for local review artifacts.")
    args = parser.parse_args(argv)
    run_fdp123_evaluation(Path(args.input), Path(args.output_dir), generated_at=args.generated_at)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
