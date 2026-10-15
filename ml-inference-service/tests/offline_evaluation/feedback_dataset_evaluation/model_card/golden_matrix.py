from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

from app.models.model_loader import load_validated_model_artifact
from offline_evaluation.feedback_dataset_evaluation.dataset_reader import read_feedback_dataset_jsonl
from offline_evaluation.feedback_dataset_evaluation.model_card.generator import generate_model_card_artifact_set
from offline_evaluation.feedback_dataset_evaluation.model_evaluation import (
    ModelEvaluationIdentity,
    build_model_specific_evaluation_summary,
)
from offline_evaluation.feedback_dataset_evaluation.report_contract import (
    MODEL_EVALUATION_ARTIFACT_SET_VERSION,
    MODEL_EVALUATION_REPORT_TYPE,
)
from offline_evaluation.feedback_dataset_evaluation.report_writer import (
    build_artifact_manifest,
    report_json,
)

try:
    from feedback_dataset_evaluation.feedback_dataset_fixtures import GENERATED_AT, jsonl, record
except ModuleNotFoundError:
    from feedback_dataset_fixtures import GENERATED_AT, jsonl, record


CARD_GENERATED_AT = "2026-06-10T00:00:01Z"
MIXED_FIXTURE = "python-generated-mixed-v1"
NO_DATA_FIXTURE = "python-generated-no-data-v1"
PARTIAL_TRUNCATED_FIXTURE = "python-generated-partial-truncated-v1"
GOLDEN_MATRIX_FIXTURES = (MIXED_FIXTURE, NO_DATA_FIXTURE, PARTIAL_TRUNCATED_FIXTURE)


@dataclass(frozen=True)
class GoldenScenario:
    name: str
    dataset_payload: str
    top_k_values: tuple[int, ...]


def generate_golden_matrix(
        model_artifact_path: Path,
        output_root: Path,
        workspace_root: Path,
) -> tuple[ModelEvaluationIdentity, dict[str, dict[str, Path]]]:
    loaded = load_validated_model_artifact(model_artifact_path)
    artifact_identity = loaded.artifact_identity
    identity = ModelEvaluationIdentity(
        artifact_identity.model_name,
        artifact_identity.model_version,
        artifact_identity.feature_contract_version,
        artifact_identity.artifact_sha256,
    )
    generated: dict[str, dict[str, Path]] = {}
    for scenario in _scenarios(identity):
        scenario_workspace = workspace_root / scenario.name
        scenario_workspace.mkdir(parents=True)
        dataset_path = scenario_workspace / "feedback-dataset.jsonl"
        dataset_path.write_text(scenario.dataset_payload, encoding="utf-8", newline="\n")
        evaluation_dir = scenario_workspace / "model-evaluation"
        _write_evaluation_artifact_set(
            dataset_path,
            evaluation_dir,
            identity,
            scenario.top_k_values,
        )
        generated[scenario.name] = generate_model_card_artifact_set(
            model_artifact_path=model_artifact_path,
            output_dir=output_root / scenario.name,
            generated_at=CARD_GENERATED_AT,
            model_evaluation_dir=evaluation_dir,
            source_dataset_path=dataset_path,
            allow_output_root=output_root,
        )
    return identity, generated


def _scenarios(identity: ModelEvaluationIdentity) -> tuple[GoldenScenario, ...]:
    mixed_records = (
        _evidence_record(identity, 1, positive=True, predicted_high=True, score=0.99, rules_high=True),
        _evidence_record(identity, 2, positive=False, predicted_high=True, score=0.95, rules_high=False),
        _evidence_record(identity, 3, positive=True, predicted_high=False, score=0.90, rules_high=True),
        _evidence_record(identity, 4, positive=False, predicted_high=False, score=0.80, rules_high=False),
        _evidence_record(identity, 5, positive=True, predicted_high=True, score=0.70, rules_high=False),
        _evidence_record(identity, 6, positive=True, predicted_high=False, score=0.60, rules_high=False),
        _evidence_record(identity, 7, positive=False, predicted_high=False, score=0.50, rules_high=True),
    )
    partial_records = (
        _evidence_record(identity, 11, positive=True, predicted_high=True, score=0.90, rules_high=True),
        _evidence_record(identity, 12, positive=False, predicted_high=False, score=0.40, rules_high=False),
        _evidence_record(
            identity,
            13,
            positive=False,
            predicted_high=False,
            score=0.20,
            rules_high=False,
            model_version="nonmatching-model-v1",
        ),
    )
    return (
        GoldenScenario(MIXED_FIXTURE, jsonl(*mixed_records), (1, 3, 5, 7)),
        GoldenScenario(NO_DATA_FIXTURE, jsonl(), (1, 3)),
        GoldenScenario(
            PARTIAL_TRUNCATED_FIXTURE,
            jsonl(*partial_records, metadata_overrides={"rawRowsRead": 4, "truncated": True}),
            (1, 2),
        ),
    )


def _evidence_record(
        identity: ModelEvaluationIdentity,
        index: int,
        *,
        positive: bool,
        predicted_high: bool,
        score: float,
        rules_high: bool,
        model_version: str | None = None,
) -> dict[str, object]:
    return record(
        evaluationRecordId=f"eval_{index:032x}",
        transactionReference=f"txnref_{index:032x}",
        feedbackLabel="CONFIRMED_FRAUD" if positive else "CONFIRMED_LEGITIMATE",
        evaluationLabel="POSITIVE_FRAUD" if positive else "NEGATIVE_LEGITIMATE",
        mlPredictionScore=score,
        mlPredictionRiskLevel="HIGH" if predicted_high else "LOW",
        mlModelName=identity.model_name,
        mlModelVersion=model_version or identity.model_version,
        mlFeatureContractVersion=identity.feature_contract_version,
        mlModelArtifactSha256=identity.model_artifact_sha256,
        rulesRiskLevel="HIGH" if rules_high else "LOW",
    )


def _write_evaluation_artifact_set(
        dataset_path: Path,
        evaluation_dir: Path,
        identity: ModelEvaluationIdentity,
        top_k_values: tuple[int, ...],
) -> None:
    dataset = read_feedback_dataset_jsonl(dataset_path)
    summary = build_model_specific_evaluation_summary(
        dataset,
        identity,
        GENERATED_AT,
        top_k_values=top_k_values,
    )
    evaluation_dir.mkdir()
    summary_path = evaluation_dir / "model_evaluation_summary.json"
    summary_payload = report_json(summary)
    summary_path.write_text(summary_payload, encoding="utf-8", newline="\n")
    manifest_payload = build_artifact_manifest(
        {summary_path: summary_payload},
        GENERATED_AT,
        artifact_set_version=MODEL_EVALUATION_ARTIFACT_SET_VERSION,
        report_type=MODEL_EVALUATION_REPORT_TYPE,
    )
    (evaluation_dir / "manifest.json").write_text(
        manifest_payload,
        encoding="utf-8",
        newline="\n",
    )
