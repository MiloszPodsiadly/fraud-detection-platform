from __future__ import annotations

import hashlib
import json
import tempfile
import unittest
from contextlib import contextmanager
from pathlib import Path

from offline_evaluation.feedback_dataset_evaluation.dataset_schema import MAX_DATASET_RECORDS
from offline_evaluation.feedback_dataset_evaluation.evaluation_card.generator import (
    generate_platform_evaluation_card_from_artifacts,
)
from offline_evaluation.feedback_dataset_evaluation.evaluation_card.artifact_reader import (
    read_validated_evaluation_card_artifact_set,
)
from offline_evaluation.feedback_dataset_evaluation.evaluation_card.schema import (
    REQUIRED_GOVERNANCE_BOUNDARY,
    REQUIRED_LIMITATIONS,
    REQUIRED_NOT_INTENDED_USE,
    FeedbackDatasetEvaluationCardValidationError,
)
from offline_evaluation.feedback_dataset_evaluation.evaluation_card.writer import write_evaluation_card_artifacts
from offline_evaluation.feedback_dataset_evaluation.evaluation_runner import run_feedback_dataset_evaluation
from offline_evaluation.feedback_dataset_evaluation.model_evaluation import (
    ModelEvaluationIdentity,
)
from offline_evaluation.feedback_dataset_evaluation.model_evaluation_artifact_set import (
    ModelEvaluationArtifactSetError,
    read_validated_model_evaluation_artifact_set,
)
from offline_evaluation.feedback_dataset_evaluation.timestamp_contract import compare_rfc3339_timestamps
from offline_evaluation.generate_current_shadow_summary import generate_current_shadow_summary
from offline_evaluation.generate_promotion_review_readiness_report import (
    generate_promotion_review_readiness_report,
)
from offline_evaluation.promotion_review_readiness_artifact_set import (
    read_validated_promotion_review_readiness_artifact_set,
)
from offline_evaluation.shadow_performance_artifact_set import read_validated_shadow_performance_artifact_set

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
CARD_GENERATED_AT = "2026-06-11T00:00:00.123456789Z"
SHADOW_GENERATED_AT = "2026-06-12T00:00:00.234567891Z"
PROMOTION_READINESS_GENERATED_AT = "2026-06-13T00:00:00.345678912Z"


class FeedbackEvaluationCrossChainRegressionTest(unittest.TestCase):
    def test_current_pipeline_reaches_promotion_review_readiness_with_verified_provenance(self):
        with completed_cross_chain_run() as run:
            platform_manifest_path = run.platform_dir / "manifest.json"
            platform_manifest = self._json(platform_manifest_path)
            platform_summary = self._json(run.platform_dir / "evaluation_summary.json")
            self.assertEqual("FEEDBACK_DATASET_OFFLINE_EVALUATION_V1", platform_manifest["reportType"])
            self.assertEqual(
                "feedback-dataset-evaluation-report-artifact-set-v1",
                platform_manifest["artifactSetVersion"],
            )
            self.assertEqual(GENERATED_AT, platform_manifest["generatedAt"])
            self.assertEqual(GENERATED_AT, platform_summary["generatedAt"])
            self.assertEqual(platform_manifest["reportType"], platform_summary["reportType"])
            self.assertEqual(
                "PLATFORM_RECOMMENDATION_NOT_MODEL_ARTIFACT_SCOPED",
                platform_summary["evaluationSubject"]["identityCompleteness"],
            )
            self._assert_named_manifest_integrity(run.platform_dir, platform_manifest)

            card = self._generate_card(run)
            card_dir = run.root / "platform-recommendation-evaluation-card"
            card_paths = write_evaluation_card_artifacts(card, card_dir, allow_output_root=run.root)
            validated_card, card_manifest_sha256 = read_validated_evaluation_card_artifact_set(
                card_paths["evaluationCardJson"],
                card_paths["manifest"],
            )
            platform_manifest_sha256 = hashlib.sha256(platform_manifest_path.read_bytes()).hexdigest()
            self.assertEqual(platform_manifest_sha256, validated_card["evaluationEvidence"]["sourceManifestSha256"])
            self.assertLessEqual(
                compare_rfc3339_timestamps(
                    validated_card["evaluationEvidence"]["evaluationGeneratedAt"],
                    validated_card["generatedAt"],
                ),
                0,
            )
            card_manifest = self._json(card_paths["manifest"])
            self.assertEqual(validated_card["generatedAt"], card_manifest["generatedAt"])
            self._assert_named_manifest_integrity(card_dir, card_manifest)

            shadow_path = (
                run.root
                / "deployment"
                / "local-generated"
                / "shadow-performance"
                / "current-summary.json"
            )
            generate_current_shadow_summary(
                card_paths["evaluationCardJson"],
                card_paths["manifest"],
                shadow_path,
                generated_at=SHADOW_GENERATED_AT,
                allowed_output_root=shadow_path.parent,
            )
            shadow_summary, shadow_manifest_sha256 = read_validated_shadow_performance_artifact_set(
                shadow_path,
                shadow_path.with_name("manifest.json"),
            )
            self.assertEqual("feedback-dataset-evaluation-v1", shadow_summary["evaluation"]["evaluationReportVersion"])
            self.assertEqual(platform_manifest_sha256, shadow_summary["evaluation"]["sourceManifestSha256"])
            self.assertEqual(
                card_manifest_sha256,
                shadow_summary["evaluation"]["sourceEvaluationCardManifestSha256"],
            )
            self.assertLessEqual(
                compare_rfc3339_timestamps(validated_card["generatedAt"], shadow_summary["generatedAt"]),
                0,
            )
            self.assertTrue(shadow_summary["governance"]["notProductionApproval"])
            self.assertTrue(shadow_summary["governance"]["notPromotionApproval"])

            readiness_path = (
                run.root
                / "deployment"
                / "local-generated"
                / "promotion-readiness"
                / "promotion-review-readiness-report.json"
            )
            generate_promotion_review_readiness_report(
                shadow_path,
                shadow_path.with_name("manifest.json"),
                readiness_path,
                generated_at=PROMOTION_READINESS_GENERATED_AT,
                allowed_output_root=readiness_path.parent,
            )
            readiness = read_validated_promotion_review_readiness_artifact_set(
                readiness_path,
                readiness_path.with_name("manifest.json"),
            )
            self.assertEqual(
                shadow_manifest_sha256,
                readiness["checkInputs"]["sourceShadowSummaryManifestSha256"],
            )
            self.assertEqual(
                card_manifest_sha256,
                readiness["checkInputs"]["shadowPerformanceSummary"]["sourceEvaluationCardManifestSha256"],
            )
            self.assertLessEqual(
                compare_rfc3339_timestamps(shadow_summary["generatedAt"], readiness["generatedAt"]),
                0,
            )
            for field in (
                    "diagnosticOnly",
                    "notPromotionApproval",
                    "notThresholdRecommendation",
                    "notProductionDecisioning",
                    "notPaymentAuthorization",
                    "notAutomaticDecisioning",
                    "notAnalystRecommendation",
            ):
                self.assertTrue(readiness[field])
            self.assertEqual([], list(run.root.rglob("*.tmp")))

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
                    "recordsWithPredictionEvidence": 3,
                    "recordsEvaluated": 2,
                    "recordsExcludedIdentityMismatch": 1,
                    "recordsExcludedSourceIdentityMismatch": 0,
                    "recordsExcludedMissingPredictionEvidence": 1,
                    "recordsExcludedUnexpectedMissingPredictionEvidence": 0,
                    "recordsExcludedInvalidPredictionEvidence": 0,
                },
                dict(model_summary["population"]),
            )
            self.assertEqual(
                {"positiveClassCount": 1, "negativeClassCount": 1},
                dict(model_summary["classBalance"]),
            )
            self.assertEqual(("MODEL_EVALUATION_PARTIAL_COVERAGE",), model_summary["warnings"])
            self.assertTrue(model_summary["supportedMetrics"]["classBalance"]["available"])
            self.assertTrue(model_summary["supportedMetrics"]["mlPredictionMetrics"]["available"])
            self.assertIsNone(model_summary["supportedMetrics"]["mlPredictionMetrics"]["reason"])
            self.assertLessEqual(model_summary["population"]["recordsConsidered"], MAX_DATASET_RECORDS)
            window = model_summary["evaluationWindow"]
            self.assertLessEqual(
                compare_rfc3339_timestamps(
                    window["fromInclusive"],
                    window["toInclusive"],
                ),
                0,
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
            inconsistent["warnings"] = ["SINGLE_CLASS_MODEL_LINEAGE_RECORDS"]
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
        return generate_platform_evaluation_card_from_artifacts(
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

    def _assert_named_manifest_integrity(self, artifact_dir, manifest):
        for item in manifest["files"]:
            payload = (artifact_dir / item["name"]).read_bytes()
            self.assertEqual(len(payload), item["sizeBytes"])
            self.assertEqual(hashlib.sha256(payload).hexdigest(), item["sha256"])

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
