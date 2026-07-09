import json
import sys
import tempfile
import unittest
from pathlib import Path

from offline_evaluation.fdp123.dataset_reader import read_fdp123_feedback_dataset_jsonl
from offline_evaluation.fdp123.evaluation_runner import build_fdp123_evaluation_reports
from offline_evaluation.fdp123.model_card.generator import generate_model_card_from_fdp124_artifacts
from offline_evaluation.fdp123.model_card.schema import (
    REQUIRED_GOVERNANCE_BOUNDARY,
    REQUIRED_LIMITATIONS,
    REQUIRED_NOT_INTENDED_USE,
    Fdp123ModelCardValidationError,
)
from offline_evaluation.fdp123.report_writer import write_fdp123_reports

sys.path.append(str(Path(__file__).resolve().parents[1]))

try:
    from fdp123.fdp123_fixtures import GENERATED_AT, jsonl, jsonl_file, record
except ModuleNotFoundError:
    from fdp123_fixtures import GENERATED_AT, jsonl, jsonl_file, record


MODEL_CARD_GENERATED_AT = "2026-06-12T00:00:00Z"


class Fdp123ModelCardGeneratorTest(unittest.TestCase):
    def test_generatesModelCardFromValidFdp124Artifacts(self):
        with self.artifacts() as paths:
            card = self.generate(paths)

        self.assertEqual("MODEL_CARD_V1", card["cardType"])
        self.assertEqual("FDP123_FEEDBACK_DATASET_OFFLINE_EVALUATION_V1", card["evaluationEvidence"]["evaluationReportType"])
        self.assertEqual("NOT_APPROVED", card["productionApproval"])
        self.assertEqual("NOT_EVALUATED_FOR_PROMOTION", card["promotionStatus"])
        self.assertIn("allowedUsageModes", card)
        self.assertNotIn("approvedFor", card)

    def test_validatesManifestBeforeSummaryIsTrusted(self):
        with self.artifacts() as paths:
            manifest = json.loads(paths["manifest"].read_text(encoding="utf-8"))
            manifest["files"] = []
            paths["manifest"].write_text(json.dumps(manifest), encoding="utf-8")

            with self.assertRaises(Fdp123ModelCardValidationError):
                self.generate(paths)

    def test_failsIfManifestMissing(self):
        with self.artifacts() as paths:
            paths["manifest"].unlink()
            with self.assertRaises(Fdp123ModelCardValidationError):
                self.generate(paths)

    def test_failsIfEvaluationSummaryMissing(self):
        with self.artifacts() as paths:
            paths["evaluationSummary"].unlink()
            with self.assertRaises(Fdp123ModelCardValidationError):
                self.generate(paths)

    def test_failsIfManifestHashMismatch(self):
        with self.artifacts() as paths:
            self._mutate_manifest_entry(paths, sha256="b" * 64)
            with self.assertRaises(Fdp123ModelCardValidationError):
                self.generate(paths)

    def test_failsIfManifestSizeMismatch(self):
        with self.artifacts() as paths:
            self._mutate_manifest_entry(paths, sizeBytes=1)
            with self.assertRaises(Fdp123ModelCardValidationError):
                self.generate(paths)

    def test_failsIfManifestReportTypeUnsupported(self):
        with self.artifacts() as paths:
            self._mutate_manifest(paths, reportType="OTHER")
            with self.assertRaises(Fdp123ModelCardValidationError):
                self.generate(paths)

    def test_failsIfManifestArtifactSetVersionUnsupported(self):
        with self.artifacts() as paths:
            self._mutate_manifest(paths, artifactSetVersion="other")
            with self.assertRaises(Fdp123ModelCardValidationError):
                self.generate(paths)

    def test_failsIfEvaluationSummaryReportTypeUnsupported(self):
        with self.artifacts() as paths:
            self._mutate_summary(paths, reportType="OTHER")
            self._rewrite_manifest(paths)
            with self.assertRaises(Fdp123ModelCardValidationError):
                self.generate(paths)

    def test_failsIfSummaryMissingGeneratedAt(self):
        self._assert_summary_missing("generatedAt")

    def test_failsIfQualityMetricsMissing(self):
        self._assert_summary_missing("qualityMetrics")

    def test_failsIfDatasetSummaryMissing(self):
        with self.artifacts() as paths:
            summary = self._summary(paths)
            summary["qualityMetrics"].pop("datasetSummary")
            self._write_summary(paths, summary)
            with self.assertRaises(Fdp123ModelCardValidationError):
                self.generate(paths)

    def test_failsIfClassBalanceMissing(self):
        with self.artifacts() as paths:
            summary = self._summary(paths)
            summary["qualityMetrics"].pop("classBalance")
            self._write_summary(paths, summary)
            with self.assertRaises(Fdp123ModelCardValidationError):
                self.generate(paths)

    def test_failsIfAlertRecommendedConfusionMatrixMissing(self):
        with self.artifacts() as paths:
            summary = self._summary(paths)
            summary["qualityMetrics"].pop("alertRecommendedConfusionMatrix")
            self._write_summary(paths, summary)
            with self.assertRaises(Fdp123ModelCardValidationError):
                self.generate(paths)

    def test_failsIfMetricObjectShapeInvalid(self):
        with self.artifacts() as paths:
            summary = self._summary(paths)
            summary["qualityMetrics"]["alertRecommendedConfusionMatrix"]["precision"] = {"value": 0.5}
            self._write_summary(paths, summary)
            with self.assertRaises(Fdp123ModelCardValidationError):
                self.generate(paths)

    def test_failsIfModelMetadataMissingRequiredFields(self):
        with self.artifacts() as paths:
            metadata = model_metadata()
            metadata.pop("modelVersion")
            with self.assertRaises(Fdp123ModelCardValidationError):
                self.generate(paths, metadata=metadata)

    def test_failsIfModelMetadataContainsUrlTokenOrUnknown(self):
        for field, value in (
            ("modelName", "https://example.test/model"),
            ("modelVersion", "unknown"),
            ("featureContractVersion", "token-contract"),
        ):
            with self.subTest(field=field):
                with self.artifacts() as paths:
                    metadata = model_metadata(**{field: value})
                    with self.assertRaises(Fdp123ModelCardValidationError):
                        self.generate(paths, metadata=metadata)

    def test_doesNotReadDisagreementJsonl(self):
        with self.artifacts() as paths:
            paths["disagreementReport"].write_text("transactionId unsafe\n", encoding="utf-8")
            card = self.generate(paths)

        self.assertEqual("MODEL_CARD_V1", card["cardType"])

    def test_doesNotIncludeRawIdsOrDecisionReasonCodes(self):
        with self.artifacts() as paths:
            payload = json.dumps(self.generate(paths))

        self.assertNotIn("evaluationRecordId", payload)
        self.assertNotIn("transactionReference", payload)
        self.assertNotIn("decisionReasonCodes", payload)

    def test_preservesUnavailableMetricShape(self):
        with self.artifacts(record(alertRecommended=False)) as paths:
            card = self.generate(paths)

        precision = card["metricsSummary"]["alertRecommendedPrecision"]
        self.assertFalse(precision["available"])
        self.assertIsNone(precision["value"])
        self.assertEqual("NO_PREDICTED_POSITIVES", precision["reason"])

    def generate(self, paths, metadata=None):
        return generate_model_card_from_fdp124_artifacts(
            paths["evaluationSummary"],
            paths["manifest"],
            metadata or model_metadata(),
            MODEL_CARD_GENERATED_AT,
        )

    def _assert_summary_missing(self, field):
        with self.artifacts() as paths:
            summary = self._summary(paths)
            summary.pop(field)
            self._write_summary(paths, summary)
            with self.assertRaises(Fdp123ModelCardValidationError):
                self.generate(paths)

    def _summary(self, paths):
        return json.loads(paths["evaluationSummary"].read_text(encoding="utf-8"))

    def _write_summary(self, paths, summary):
        paths["evaluationSummary"].write_text(json.dumps(summary, sort_keys=True, separators=(",", ":")) + "\n", encoding="utf-8")
        self._rewrite_manifest(paths)

    def _rewrite_manifest(self, paths):
        payload = paths["evaluationSummary"].read_bytes()
        manifest = json.loads(paths["manifest"].read_text(encoding="utf-8"))
        for item in manifest["files"]:
            if item["name"] == "evaluation_summary.json":
                import hashlib
                item["sha256"] = hashlib.sha256(payload).hexdigest()
                item["sizeBytes"] = len(payload)
        paths["manifest"].write_text(json.dumps(manifest, sort_keys=True, separators=(",", ":")) + "\n", encoding="utf-8")

    def _mutate_manifest_entry(self, paths, **overrides):
        manifest = json.loads(paths["manifest"].read_text(encoding="utf-8"))
        for item in manifest["files"]:
            if item["name"] == "evaluation_summary.json":
                item.update(overrides)
        paths["manifest"].write_text(json.dumps(manifest), encoding="utf-8")

    def _mutate_manifest(self, paths, **overrides):
        manifest = json.loads(paths["manifest"].read_text(encoding="utf-8"))
        manifest.update(overrides)
        paths["manifest"].write_text(json.dumps(manifest), encoding="utf-8")

    def _mutate_summary(self, paths, **overrides):
        summary = self._summary(paths)
        summary.update(overrides)
        self._write_summary(paths, summary)

    def artifacts(self, *records):
        return fdp124_artifacts(*records)


def model_metadata(**overrides):
    metadata = {
        "modelName": "python-logistic-fraud-model",
        "modelVersion": "2026.06.12-offline",
        "modelFamily": "LOGISTIC_REGRESSION",
        "trainingMode": "OFFLINE_TRAINED",
        "featureContractVersion": "feature-contract-2026.06",
        "referenceQuality": "BOUNDED_ANALYST_FEEDBACK",
        "allowedUsageModes": ["SHADOW", "COMPARE", "OFFLINE_EVALUATION"],
        "intendedUse": ["SHADOW_FRAUD_RISK_REVIEW", "OFFLINE_DIAGNOSTIC_ANALYSIS"],
        "notIntendedUse": sorted(REQUIRED_NOT_INTENDED_USE),
        "limitations": sorted(REQUIRED_LIMITATIONS),
        "governanceBoundary": sorted(REQUIRED_GOVERNANCE_BOUNDARY),
    }
    metadata.update(overrides)
    return metadata


class fdp124_artifacts:
    def __init__(self, *records):
        self.records = records or (record(), record(
            evaluationRecordId="eval_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
            transactionReference="txnref_cccccccccccccccccccccccccccccccc",
            feedbackLabel="CONFIRMED_LEGITIMATE",
            evaluationLabel="NEGATIVE_LEGITIMATE",
        ))
        self.directory = None

    def __enter__(self):
        self.directory = tempfile.TemporaryDirectory()
        output = Path(self.directory.name)
        with jsonl_file(jsonl(*self.records)) as input_path:
            dataset = read_fdp123_feedback_dataset_jsonl(input_path)
        reports = build_fdp123_evaluation_reports(dataset, generated_at=GENERATED_AT)
        return write_fdp123_reports(reports, output)

    def __exit__(self, exc_type, exc, tb):
        self.directory.cleanup()


if __name__ == "__main__":
    unittest.main()
