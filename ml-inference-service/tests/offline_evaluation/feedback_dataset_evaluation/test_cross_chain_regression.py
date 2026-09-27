from __future__ import annotations

import hashlib
import json
import tempfile
import unittest
from contextlib import contextmanager
from datetime import datetime
from pathlib import Path

from offline_evaluation.feedback_dataset_evaluation.dataset_schema import MAX_DATASET_RECORDS
from offline_evaluation.feedback_dataset_evaluation.evaluation_card.generator import (
    generate_evaluation_card_from_fdp124_artifacts,
)
from offline_evaluation.feedback_dataset_evaluation.evaluation_card.schema import (
    REQUIRED_GOVERNANCE_BOUNDARY,
    REQUIRED_LIMITATIONS,
    REQUIRED_NOT_INTENDED_USE,
    FeedbackDatasetEvaluationCardValidationError,
)
from offline_evaluation.feedback_dataset_evaluation.evaluation_runner import run_feedback_dataset_evaluation
from offline_evaluation.feedback_dataset_evaluation.model_evaluation import (
    MODEL_PREDICTION_SIGNAL_UNAVAILABLE,
    ModelEvaluationIdentity,
)
from offline_evaluation.feedback_dataset_evaluation.model_evaluation_artifact_set import (
    ModelEvaluationArtifactSetError,
    read_validated_model_evaluation_artifact_set,
)

try:
    from feedback_dataset_evaluation.feedback_dataset_fixtures import GENERATED_AT, jsonl, record
except ModuleNotFoundError:
    from feedback_dataset_fixtures import GENERATED_AT, jsonl, record


MODEL_X = ModelEvaluationIdentity(
    "python-logistic-fraud-model",
    "model-X",
    "feature-contract-v2",
)
MODEL_Y = ModelEvaluationIdentity(
    "python-logistic-fraud-model",
    "model-Y",
    "feature-contract-v2",
)
CARD_GENERATED_AT = "2026-06-11T00:00:00Z"


class FeedbackEvaluationCrossChainRegressionTest(unittest.TestCase):
    def test_real_run_produces_independent_trusted_platform_and_model_artifact_sets(self):
        with completed_cross_chain_run() as run:
            platform_manifest = self._json(run.platform_dir / "manifest.json")
            model_manifest = self._json(run.model_dir / "manifest.json")
            model_evidence = read_validated_model_evaluation_artifact_set(run.model_dir)
            model_summary = model_evidence.summary
            card = self._generate_card(run)

            self.assertEqual(
                {
                    "disagreement_report.jsonl",
                    "evaluation_run.md",
                    "evaluation_summary.json",
                    "risk_level_report.json",
                    "score_bucket_report.json",
                },
                {item["name"] for item in platform_manifest["files"]},
            )
            self.assertEqual(
                {"model_evaluation_summary.json"},
                {item["name"] for item in model_manifest["files"]},
            )
            self.assertEqual(MODEL_X.as_subject(), dict(model_summary["evaluationSubject"]))
            self.assertEqual(
                {
                    "recordsConsidered": 4,
                    "recordsEvaluated": 2,
                    "recordsExcludedMissingLineage": 1,
                    "recordsExcludedIdentityMismatch": 1,
                },
                dict(model_summary["population"]),
            )
            self.assertEqual(
                {"positiveClassCount": 1, "negativeClassCount": 1},
                dict(model_summary["classBalance"]),
            )
            self.assertEqual((MODEL_PREDICTION_SIGNAL_UNAVAILABLE,), model_summary["warnings"])
            self.assertTrue(model_summary["supportedMetrics"]["classBalance"]["available"])
            self.assertFalse(model_summary["supportedMetrics"]["mlPredictionMetrics"]["available"])
            self.assertEqual(
                MODEL_PREDICTION_SIGNAL_UNAVAILABLE,
                model_summary["supportedMetrics"]["mlPredictionMetrics"]["reason"],
            )
            self.assertLessEqual(model_summary["population"]["recordsConsidered"], MAX_DATASET_RECORDS)
            window = model_summary["evaluationWindow"]
            self.assertLessEqual(
                datetime.fromisoformat(window["fromInclusive"].replace("Z", "+00:00")),
                datetime.fromisoformat(window["toInclusive"].replace("Z", "+00:00")),
            )
            self.assertEqual(4, card["evaluationEvidence"]["recordsEvaluated"])

    def test_tampering_one_artifact_set_does_not_invalidate_the_unchanged_sibling(self):
        with completed_cross_chain_run() as run:
            model_summary_path = run.model_dir / "model_evaluation_summary.json"
            original_model_summary = model_summary_path.read_bytes()
            model_summary_path.write_bytes(original_model_summary + b" ")

            with self.assertRaises(ModelEvaluationArtifactSetError):
                read_validated_model_evaluation_artifact_set(run.model_dir)
            self._generate_card(run)
            model_summary_path.write_bytes(original_model_summary)

            platform_report_path = run.platform_dir / "score_bucket_report.json"
            original_platform_report = platform_report_path.read_bytes()
            platform_report_path.write_bytes(original_platform_report + b" ")

            with self.assertRaises(FeedbackDatasetEvaluationCardValidationError):
                self._generate_card(run)
            read_validated_model_evaluation_artifact_set(run.model_dir)
            platform_report_path.write_bytes(original_platform_report)

            original_model_manifest = (run.model_dir / "manifest.json").read_bytes()
            inconsistent = self._json(model_summary_path)
            inconsistent["warnings"] = []
            self._write_json(model_summary_path, inconsistent)
            self._reseal_model_manifest(run.model_dir)

            with self.assertRaisesRegex(ModelEvaluationArtifactSetError, "warnings"):
                read_validated_model_evaluation_artifact_set(run.model_dir)
            self._generate_card(run)

            model_summary_path.write_bytes(original_model_summary)
            (run.model_dir / "manifest.json").write_bytes(original_model_manifest)
            read_validated_model_evaluation_artifact_set(run.model_dir)
            self._generate_card(run)

    def test_second_run_fails_closed_without_changing_or_combining_artifacts(self):
        with completed_cross_chain_run() as run:
            original_files = self._artifact_bytes(run.output_dir)
            original_manifests = sorted(run.output_dir.rglob("manifest.json"))

            with self.assertRaisesRegex(ValueError, "must be empty"):
                run_feedback_dataset_evaluation(
                    run.input_path,
                    run.output_dir,
                    generated_at=GENERATED_AT,
                    allow_output_root=run.root,
                    model_identity=MODEL_X,
                )

            self.assertEqual(original_files, self._artifact_bytes(run.output_dir))
            self.assertEqual(original_manifests, sorted(run.output_dir.rglob("manifest.json")))
            self.assertEqual([], list(run.output_dir.rglob("*.tmp")))
            self._generate_card(run)
            read_validated_model_evaluation_artifact_set(run.model_dir)

    def _generate_card(self, run):
        return generate_evaluation_card_from_fdp124_artifacts(
            run.platform_dir / "evaluation_summary.json",
            run.platform_dir / "manifest.json",
            governance_metadata(),
            CARD_GENERATED_AT,
        )

    @staticmethod
    def _artifact_bytes(output_dir):
        return {
            path.relative_to(output_dir): path.read_bytes()
            for path in output_dir.rglob("*")
            if path.is_file()
        }

    @staticmethod
    def _json(path):
        return json.loads(path.read_text(encoding="utf-8"))

    @staticmethod
    def _write_json(path, value):
        path.write_text(
            json.dumps(value, sort_keys=True, separators=(",", ":")) + "\n",
            encoding="utf-8",
            newline="\n",
        )

    def _reseal_model_manifest(self, model_dir):
        summary_path = model_dir / "model_evaluation_summary.json"
        payload = summary_path.read_bytes()
        manifest_path = model_dir / "manifest.json"
        manifest = self._json(manifest_path)
        manifest["files"][0]["sha256"] = hashlib.sha256(payload).hexdigest()
        manifest["files"][0]["sizeBytes"] = len(payload)
        self._write_json(manifest_path, manifest)


class CrossChainRun:
    def __init__(self, root, input_path, output_dir):
        self.root = root
        self.input_path = input_path
        self.output_dir = output_dir
        self.platform_dir = output_dir / "platform-evaluation"
        self.model_dir = output_dir / "model-evaluation"


@contextmanager
def completed_cross_chain_run():
    with tempfile.TemporaryDirectory() as directory:
        root = Path(directory)
        input_path = root / "feedback-dataset.jsonl"
        output_dir = root / "output"
        input_path.write_text(cross_chain_dataset(), encoding="utf-8", newline="\n")
        run_feedback_dataset_evaluation(
            input_path,
            output_dir,
            generated_at=GENERATED_AT,
            allow_output_root=root,
            model_identity=MODEL_X,
        )
        yield CrossChainRun(root, input_path, output_dir)


def cross_chain_dataset():
    return jsonl(
        model_record("eval_11111111111111111111111111111111", "txnref_11111111111111111111111111111111", MODEL_X),
        model_record(
            "eval_22222222222222222222222222222222",
            "txnref_22222222222222222222222222222222",
            MODEL_X,
            feedbackLabel="CONFIRMED_LEGITIMATE",
            evaluationLabel="NEGATIVE_LEGITIMATE",
        ),
        model_record("eval_33333333333333333333333333333333", "txnref_33333333333333333333333333333333", MODEL_Y),
        record(
            evaluationRecordId="eval_44444444444444444444444444444444",
            transactionReference="txnref_44444444444444444444444444444444",
            mlModelName=None,
            mlModelVersion=None,
            mlFeatureContractVersion=None,
        ),
    )


def model_record(evaluation_record_id, transaction_reference, identity, **overrides):
    return record(
        evaluationRecordId=evaluation_record_id,
        transactionReference=transaction_reference,
        mlModelName=identity.model_name,
        mlModelVersion=identity.model_version,
        mlFeatureContractVersion=identity.feature_contract_version,
        **overrides,
    )


def governance_metadata():
    return {
        "allowedUsageModes": ["SHADOW", "COMPARE", "OFFLINE_EVALUATION"],
        "intendedUse": ["SHADOW_FRAUD_RISK_REVIEW", "OFFLINE_DIAGNOSTIC_ANALYSIS"],
        "notIntendedUse": sorted(REQUIRED_NOT_INTENDED_USE),
        "limitations": sorted(REQUIRED_LIMITATIONS),
        "governanceBoundary": sorted(REQUIRED_GOVERNANCE_BOUNDARY),
    }


if __name__ == "__main__":
    unittest.main()
