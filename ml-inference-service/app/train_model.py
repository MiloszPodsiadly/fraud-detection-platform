from __future__ import annotations

import argparse
import json
import random
from dataclasses import dataclass
from pathlib import Path

from app.model import DEFAULT_ARTIFACT_PATH, FraudModel


FEATURE_NAMES = [
    "recentTransactionCount",
    "recentAmountSum",
    "transactionVelocityPerMinute",
    "merchantFrequency7d",
    "deviceNovelty",
    "countryMismatch",
    "proxyOrVpnDetected",
    "highRiskFlagCount",
    "rapidTransferBurst",
]


@dataclass(frozen=True)
class TrainingExample:
    features: dict[str, float]
    label: int


def generate_examples(count: int, seed: int) -> list[TrainingExample]:
    rng = random.Random(seed)
    examples: list[TrainingExample] = []
    scenarios = [
        ("normal", 0.91),
        ("rapid_transfer_seed", 0.04),
        ("new_device", 0.01),
        ("high_proxy_purchase", 0.01),
        ("country_mismatch", 0.01),
        ("account_takeover", 0.01),
        ("rapid_transfer_burst", 0.01),
    ]
    names = [name for name, _ in scenarios]
    weights = [weight for _, weight in scenarios]

    for _ in range(count):
        scenario = rng.choices(names, weights=weights, k=1)[0]
        examples.append(example_for(scenario, rng))
    return examples


def example_for(scenario: str, rng: random.Random) -> TrainingExample:
    if scenario == "normal":
        return TrainingExample(
            {
                "recentTransactionCount": rng.uniform(0.0, 0.25),
                "recentAmountSum": rng.uniform(0.0, 0.18),
                "transactionVelocityPerMinute": rng.uniform(0.0, 0.12),
                "merchantFrequency7d": rng.uniform(0.0, 0.35),
                "deviceNovelty": 0.0,
                "countryMismatch": 0.0,
                "proxyOrVpnDetected": 0.0,
                "highRiskFlagCount": 0.0,
                "rapidTransferBurst": 0.0,
            },
            0,
        )
    if scenario == "new_device":
        return TrainingExample(
            {
                "recentTransactionCount": rng.uniform(0.05, 0.35),
                "recentAmountSum": rng.uniform(0.03, 0.25),
                "transactionVelocityPerMinute": rng.uniform(0.04, 0.20),
                "merchantFrequency7d": rng.uniform(0.0, 0.45),
                "deviceNovelty": 1.0,
                "countryMismatch": 0.0,
                "proxyOrVpnDetected": rng.choice([0.0, 1.0]),
                "highRiskFlagCount": rng.uniform(0.15, 0.35),
                "rapidTransferBurst": 0.0,
            },
            0,
        )
    if scenario == "rapid_transfer_seed":
        return TrainingExample(
            {
                "recentTransactionCount": rng.uniform(0.10, 0.20),
                "recentAmountSum": rng.uniform(0.45, 0.55),
                "transactionVelocityPerMinute": rng.uniform(0.20, 0.40),
                "merchantFrequency7d": rng.uniform(0.0, 0.20),
                "deviceNovelty": 0.0,
                "countryMismatch": 0.0,
                "proxyOrVpnDetected": 0.0,
                "highRiskFlagCount": 0.0,
                "rapidTransferBurst": 0.0,
            },
            0,
        )
    if scenario == "country_mismatch":
        return TrainingExample(
            {
                "recentTransactionCount": rng.uniform(0.05, 0.45),
                "recentAmountSum": rng.uniform(0.05, 0.35),
                "transactionVelocityPerMinute": rng.uniform(0.05, 0.35),
                "merchantFrequency7d": rng.uniform(0.0, 0.50),
                "deviceNovelty": 1.0,
                "countryMismatch": 1.0,
                "proxyOrVpnDetected": 0.0,
                "highRiskFlagCount": rng.uniform(0.30, 0.55),
                "rapidTransferBurst": 0.0,
            },
            0,
        )
    if scenario == "high_proxy_purchase":
        return TrainingExample(
            {
                "recentTransactionCount": rng.uniform(0.35, 0.80),
                "recentAmountSum": rng.uniform(0.35, 0.95),
                "transactionVelocityPerMinute": rng.uniform(0.30, 0.80),
                "merchantFrequency7d": rng.uniform(0.25, 0.85),
                "deviceNovelty": 1.0,
                "countryMismatch": 0.0,
                "proxyOrVpnDetected": 1.0,
                "highRiskFlagCount": rng.uniform(0.50, 0.85),
                "rapidTransferBurst": rng.choice([0.0, 1.0]),
            },
            1,
        )
    if scenario == "rapid_transfer_burst":
        return TrainingExample(
            {
                "recentTransactionCount": rng.uniform(0.20, 0.35),
                "recentAmountSum": rng.uniform(0.95, 1.0),
                "transactionVelocityPerMinute": rng.uniform(0.40, 0.65),
                "merchantFrequency7d": rng.uniform(0.0, 0.20),
                "deviceNovelty": rng.choice([0.0, 0.0, 1.0]),
                "countryMismatch": 0.0,
                "proxyOrVpnDetected": 0.0,
                "highRiskFlagCount": rng.uniform(0.10, 0.35),
                "rapidTransferBurst": 1.0,
            },
            1,
        )
    return TrainingExample(
        {
            "recentTransactionCount": rng.uniform(0.55, 1.0),
            "recentAmountSum": rng.uniform(0.45, 1.0),
            "transactionVelocityPerMinute": rng.uniform(0.45, 1.0),
            "merchantFrequency7d": rng.uniform(0.20, 1.0),
            "deviceNovelty": 1.0,
            "countryMismatch": 1.0,
            "proxyOrVpnDetected": 1.0,
            "highRiskFlagCount": rng.uniform(0.70, 1.0),
            "rapidTransferBurst": rng.choice([0.0, 1.0]),
        },
        1,
    )


def train(examples: list[TrainingExample], epochs: int, learning_rate: float) -> tuple[float, dict[str, float]]:
    weights = {name: 0.0 for name in FEATURE_NAMES}
    bias = 0.0

    for _ in range(epochs):
        gradients = {name: 0.0 for name in FEATURE_NAMES}
        bias_gradient = 0.0

        for example in examples:
            prediction = sigmoid(bias + sum(weights[name] * example.features[name] for name in FEATURE_NAMES))
            error = prediction - example.label
            bias_gradient += error
            for name in FEATURE_NAMES:
                gradients[name] += error * example.features[name]

        scale = learning_rate / len(examples)
        bias -= scale * bias_gradient
        for name in FEATURE_NAMES:
            weights[name] -= scale * gradients[name]

    return round(bias, 6), {name: round(weight, 6) for name, weight in weights.items()}


def sigmoid(value: float) -> float:
    return 1.0 / (1.0 + pow(2.718281828459045, -value))


def write_artifact(path: Path, bias: float, weights: dict[str, float], examples: int) -> None:
    artifact = {
        "modelName": "python-logistic-fraud-model",
        "modelVersion": "2026-04-21.trained.v1",
        "modelFamily": "LOGISTIC_REGRESSION",
        "bias": bias,
        "weights": weights,
        "thresholds": {
            "medium": 0.45,
            "high": 0.75,
            "critical": 0.90,
        },
        "training": {
            "source": "synthetic-fraud-scenarios",
            "algorithm": "batch-gradient-descent",
            "examples": examples,
        },
    }
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(artifact, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, default=DEFAULT_ARTIFACT_PATH)
    parser.add_argument("--examples", type=int, default=50000)
    parser.add_argument("--epochs", type=int, default=1100)
    parser.add_argument("--learning-rate", type=float, default=0.9)
    parser.add_argument("--seed", type=int, default=7341)
    args = parser.parse_args()

    examples = generate_examples(args.examples, args.seed)
    bias, weights = train(examples, args.epochs, args.learning_rate)
    write_artifact(args.output, bias, weights, len(examples))

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
    print(f"baseline={baseline['fraudScore']} {baseline['riskLevel']}")
    print(f"highRisk={high_risk['fraudScore']} {high_risk['riskLevel']}")


if __name__ == "__main__":
    main()
