from __future__ import annotations

import argparse
from pathlib import Path

from offline_evaluation.fdp123.evaluation_card.generator import generate_evaluation_card_from_fdp124_artifacts
from offline_evaluation.fdp123.evaluation_card.writer import write_evaluation_card_artifacts


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Generate a local FDP-123/FDP-124 Platform Recommendation Evaluation Card v1 artifact set."
    )
    parser.add_argument("--evaluation-summary", required=True)
    parser.add_argument("--evaluation-manifest", required=True)
    parser.add_argument("--output-dir", required=True)
    parser.add_argument("--allow-output-root", required=True)
    parser.add_argument("--generated-at", required=True)
    parser.add_argument("--intended-use", action="append", default=[])
    parser.add_argument("--not-intended-use", action="append", default=[])
    parser.add_argument("--allowed-usage-mode", action="append", default=[])
    parser.add_argument("--limitation", action="append", default=[])
    parser.add_argument("--governance-boundary", action="append", default=[])
    args = parser.parse_args(argv)

    metadata = {
        "allowedUsageModes": _list_arg(args.allowed_usage_mode),
        "intendedUse": _list_arg(args.intended_use),
        "notIntendedUse": _list_arg(args.not_intended_use),
        "limitations": _list_arg(args.limitation),
        "governanceBoundary": _list_arg(args.governance_boundary),
    }
    evaluation_card = generate_evaluation_card_from_fdp124_artifacts(
        Path(args.evaluation_summary),
        Path(args.evaluation_manifest),
        metadata,
        args.generated_at,
    )
    write_evaluation_card_artifacts(evaluation_card, Path(args.output_dir), allow_output_root=Path(args.allow_output_root))
    return 0


def _list_arg(values: list[str]) -> list[str]:
    result: list[str] = []
    for value in values:
        result.extend(item.strip() for item in value.split(",") if item.strip())
    return result


if __name__ == "__main__":
    raise SystemExit(main())
