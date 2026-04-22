from __future__ import annotations

import argparse
from pathlib import Path

from app.data.generator import generate_examples
from app.evaluation.evaluate import cli_summary, write_report
from app.model import DEFAULT_ARTIFACT_PATH, FraudModel
from app.registry.model_registry import ModelRegistry, default_registry_path
from app.training.train import train_with_evaluation, write_artifact


def main() -> None:
    """Train and write the fraud model artifact."""
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, default=DEFAULT_ARTIFACT_PATH)
    parser.add_argument("--examples", type=int, default=50000)
    parser.add_argument("--epochs", type=int, default=1100)
    parser.add_argument("--learning-rate", type=float, default=0.9)
    parser.add_argument("--seed", type=int, default=7341)
    parser.add_argument("--model-type", choices=["logistic", "xgboost"], default="logistic")
    parser.add_argument("--evaluation-output", type=Path)
    parser.add_argument("--register-model", action="store_true")
    parser.add_argument("--registry-path", type=Path, default=default_registry_path())
    parser.add_argument("--registry-role", choices=["champion", "challenger"], default="challenger")
    args = parser.parse_args()

    dataset = generate_examples(args.examples, args.seed)
    if args.model_type != "logistic":
        raise RuntimeError(
            "model_type=xgboost is an extension point only. "
            "Install the optional xgboost dependency and complete runtime artifact support before training with it. "
            "Use --model-type logistic for the default runnable path."
        )

    bias, weights, evaluation = train_with_evaluation(dataset, args.epochs, args.learning_rate)
    write_artifact(args.output, bias, weights, dataset.size, model_type=args.model_type, evaluation=evaluation)
    evaluation_output = args.evaluation_output or args.output.with_suffix(".evaluation.json")
    write_report(evaluation, evaluation_output)
    if args.register_model:
        registry = ModelRegistry(args.registry_path)
        registry.register(
            artifact_path=args.output,
            model_version=f"trained-{args.seed}-{args.examples}",
            model_type=args.model_type,
            metrics=evaluation,
            training_metadata={
                "examples": dataset.size,
                "epochs": args.epochs,
                "learningRate": args.learning_rate,
                "seed": args.seed,
            },
            role=args.registry_role,
        )

    model = FraudModel(args.output)
    high_risk = model.score(
        {
            "recentTransactionCount": 8,
            "recentAmountSum": {"amount": 7200.0},
            "transactionVelocityPerMinute": 0.7,
            "merchantFrequency7d": 9,
            "deviceNovelty": True,
            "countryMismatch": True,
            "proxyOrVpnDetected": True,
            "featureFlags": ["DEVICE_NOVELTY", "COUNTRY_MISMATCH", "PROXY_OR_VPN", "HIGH_VELOCITY"],
            "rapidTransferTotalPln": 28_800.0,
            "rapidTransferFraudCaseCandidate": True,
        }
    )
    baseline = model.score(
        {
            "recentTransactionCount": 1,
            "recentAmountSum": {"amount": 45.0},
            "transactionVelocityPerMinute": 0.05,
            "merchantFrequency7d": 1,
            "deviceNovelty": False,
            "countryMismatch": False,
            "proxyOrVpnDetected": False,
            "featureFlags": [],
        }
    )
    print(f"wrote {args.output}")
    print(f"wrote {evaluation_output}")
    if args.register_model:
        print(f"registered {args.registry_role} model in {args.registry_path}")
    print(cli_summary(evaluation))
    print(f"baseline={baseline['fraudScore']} {baseline['riskLevel']}")
    print(f"highRisk={high_risk['fraudScore']} {high_risk['riskLevel']}")


if __name__ == "__main__":
    main()
