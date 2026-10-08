from __future__ import annotations

import argparse
import json
from pathlib import Path

from app.data.generator import generate_examples
from app.evaluation.evaluate import cli_summary, write_report
from app.features.feature_contract import FEATURE_CONTRACT
from app.features.feature_pipeline import FeaturePipeline
from app.governance.profile import NumericProfile, RISK_LEVELS
from app.model_identity_policy import ModelArtifactIdentity
from app.models.model_loader import load_validated_model_artifact, model_from_validated_artifact
from app.registry.model_registry import ModelRegistry, default_registry_path
from app.training.train import train_model_with_evaluation, write_model_artifact


DEFAULT_ARTIFACT_PATH = Path(__file__).with_name("model_artifact.json")
DEFAULT_REFERENCE_PROFILE_PATH = Path(__file__).parent / "governance" / "reference_profile.local.json"


def main() -> None:
    """Train and write the fraud model artifact."""
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, default=DEFAULT_ARTIFACT_PATH)
    parser.add_argument("--examples", type=int, default=50000)
    parser.add_argument("--epochs", type=int, default=1100)
    parser.add_argument("--learning-rate", type=float, default=0.9)
    parser.add_argument("--seed", type=int, default=7341)
    parser.add_argument("--model-type", choices=["logistic", "xgboost"], default="logistic")
    parser.add_argument("--training-mode", choices=["production", "full"], default="production")
    parser.add_argument("--evaluation-output", type=Path)
    parser.add_argument("--reference-profile-output", type=Path, default=DEFAULT_REFERENCE_PROFILE_PATH)
    parser.add_argument("--reference-examples", type=int, default=1000)
    parser.add_argument("--reference-seed", type=int, default=7307)
    parser.add_argument("--register-model", action="store_true")
    parser.add_argument("--registry-path", type=Path, default=default_registry_path())
    args = parser.parse_args()

    dataset = generate_examples(args.examples, args.seed)
    model, evaluation = train_model_with_evaluation(
        dataset,
        args.model_type,
        args.epochs,
        args.learning_rate,
        training_mode=args.training_mode,
    )
    write_model_artifact(args.output, model, dataset.size, evaluation)
    persisted_model, artifact_identity = _load_reference_profile_source(args.output, model)
    evaluation_output = args.evaluation_output or args.output.with_suffix(".evaluation.json")
    write_report(evaluation, evaluation_output)
    reference_dataset = generate_examples(args.reference_examples, args.reference_seed)
    write_reference_profile(
        args.reference_profile_output,
        persisted_model,
        reference_dataset,
        seed=args.reference_seed,
        examples=args.reference_examples,
        artifact_identity=artifact_identity,
    )
    if args.register_model:
        registry = ModelRegistry(args.registry_path)
        registry.register(args.output)

    from app.model import FraudModel

    model = FraudModel.from_packaged_artifact(args.output)
    high_risk = model.score(
        {
            "recentTransactionCount": 8,
            "currentTransactionAmountPln": 28_800.0,
            "currency": "USD",
            "transactionVelocityPerMinute": 8.0,
            "merchantFrequency7d": 9,
            "deviceNovelty": True,
            "countryMismatch": True,
            "proxyOrVpnDetected": True,
            "recentTransactionCountWindow": "PT1M",
            "recentAmountSumWindow": "PT1M",
            "recentAmountSumPln": 28_800.0,
        }
    )
    baseline = model.score(
        {
            "recentTransactionCount": 1,
            "currentTransactionAmountPln": 45.0,
            "currency": "PLN",
            "transactionVelocityPerMinute": 1.0,
            "merchantFrequency7d": 1,
            "deviceNovelty": False,
            "countryMismatch": False,
            "proxyOrVpnDetected": False,
            "recentTransactionCountWindow": "PT1M",
            "recentAmountSumWindow": "PT1M",
            "recentAmountSumPln": 45.0,
        }
    )
    print(f"wrote {args.output}")
    print(f"wrote {evaluation_output}")
    print(f"wrote {args.reference_profile_output}")
    if args.register_model:
        print(f"registered immutable model artifact in {args.registry_path}")
    print(cli_summary(evaluation))
    print(f"baseline={baseline['fraudScore']} {baseline['riskLevel']}")
    print(f"highRisk={high_risk['fraudScore']} {high_risk['riskLevel']}")


def _load_reference_profile_source(path: Path, trained_model):
    validated_artifact = load_validated_model_artifact(path)
    identity = validated_artifact.artifact_identity
    _assert_model_matches_artifact_identity(trained_model, identity)
    persisted_model = model_from_validated_artifact(validated_artifact)
    _assert_model_matches_artifact_identity(persisted_model, identity)
    return persisted_model, identity


def _assert_model_matches_artifact_identity(model, identity: ModelArtifactIdentity) -> None:
    actual = (
        model.model_name,
        model.model_version,
        model.model_family,
        model.feature_contract_version,
    )
    expected = (
        identity.model_name,
        identity.model_version,
        identity.model_family,
        identity.feature_contract_version,
    )
    if actual != expected:
        raise ValueError(
            "Persisted model artifact identity does not match the trained model metadata."
        )


def write_reference_profile(
        path: Path,
        model,
        dataset,
        seed: int,
        examples: int,
        artifact_identity: ModelArtifactIdentity,
) -> None:
    """Write a local synthetic reference profile aligned with the active model artifact."""
    _assert_model_matches_artifact_identity(model, artifact_identity)
    pipeline = FeaturePipeline().fit(dataset)
    feature_rows = pipeline.transform(dataset, mode=model.training_mode)
    feature_profiles = {name: NumericProfile() for name in model.runtime_feature_names()}
    score_profile = NumericProfile()
    risk_level_counts = {level: 0 for level in RISK_LEVELS}

    for features in feature_rows:
        for name, profile in feature_profiles.items():
            profile.update(features[name])
        score = model.predict_proba(features)
        score_profile.update(score)
        risk_level_counts[_risk_level(score, model.thresholds)] += 1

    payload = {
        "profileType": "synthetic_local_reference",
        "profileVersion": "2026-09-14.rules-v2-canonical.synthetic.v1",
        "feature_schema_version": artifact_identity.feature_contract_version,
        "generated_at": "2026-09-14T00:00:00+00:00",
        "generated_by": "ml-inference-service canonical rules-v2 feature pipeline",
        "model_name": artifact_identity.model_name,
        "model_version": artifact_identity.model_version,
        "artifact_sha256": artifact_identity.artifact_sha256,
        "numeric_feature_stats": {
            name: profile.snapshot()
            for name, profile in feature_profiles.items()
        },
        "reference_quality": "SYNTHETIC",
        "risk_level_distribution": _distribution(risk_level_counts),
        "score_distribution": {
            **score_profile.snapshot(),
            "high_risk_rate": _high_risk_rate(risk_level_counts),
        },
        "sample_size": len(feature_rows),
        "source": "synthetic",
        "data_window": (
            f"{examples} deterministic local synthetic canonical fraud-behavior examples "
            f"generated with seed {seed}; not production traffic"
        ),
        "source_metadata": {
            "examples": examples,
            "generator": "generate_fraud_behavior",
            "limitations": ["local synthetic baseline, not production traffic"],
            "seed": seed,
            "type": "synthetic_training_pipeline",
        },
        "training_mode": model.training_mode,
    }
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def _risk_level(score: float, thresholds: dict[str, float]) -> str:
    if score >= thresholds["critical"]:
        return "CRITICAL"
    if score >= thresholds["high"]:
        return "HIGH"
    if score >= thresholds["medium"]:
        return "MEDIUM"
    return "LOW"


def _distribution(counts: dict[str, int]) -> dict[str, float]:
    total = sum(counts.values()) or 1
    return {
        level: round(counts[level] / total, 6)
        for level in RISK_LEVELS
    }


def _high_risk_rate(counts: dict[str, int]) -> float:
    total = sum(counts.values()) or 1
    return round((counts["HIGH"] + counts["CRITICAL"]) / total, 6)


if __name__ == "__main__":
    main()
