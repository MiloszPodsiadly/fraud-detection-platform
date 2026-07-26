import hashlib
import json
import sys
import tempfile
import unittest
from pathlib import Path

from offline_evaluation.fdp123.dataset_reader import read_fdp123_feedback_dataset_jsonl
from offline_evaluation.fdp123.evaluation_runner import build_fdp123_evaluation_reports
from offline_evaluation.fdp123.evaluation_card.generator import generate_evaluation_card_from_fdp124_artifacts
from offline_evaluation.fdp123.evaluation_card.schema import (
    EVALUATION_SUBJECT,
    METRIC_BASIS,
    METRICS_SUBJECT,
    MAX_EVALUATION_MANIFEST_BYTES,
    MAX_EVALUATION_SUMMARY_BYTES,
    REQUIRED_GOVERNANCE_BOUNDARY,
    REQUIRED_LIMITATIONS,
    REQUIRED_NOT_INTENDED_USE,
    Fdp123EvaluationCardValidationError,
)
from offline_evaluation.fdp123.report_writer import write_fdp123_reports

sys.path.append(str(Path(__file__).resolve().parents[1]))

try:
    from fdp123.fdp123_fixtures import GENERATED_AT, jsonl, jsonl_file, record
except ModuleNotFoundError:
    from fdp123_fixtures import GENERATED_AT, jsonl, jsonl_file, record


PLATFORM_RECOMMENDATION_EVALUATION_CARD_GENERATED_AT = "2026-06-12T00:00:00Z"


class Fdp123EvaluationCardGeneratorTest(unittest.TestCase):
    def test_generatesEvaluationCardFromValidFdp124Artifacts(self):
        with self.artifacts() as paths:
            card = self.generate(paths)

        self.assertEqual("PLATFORM_RECOMMENDATION_EVALUATION_CARD_V1", card["cardType"])
        self.assertEqual("FDP123_FEEDBACK_DATASET_OFFLINE_EVALUATION_V1", card["evaluationEvidence"]["evaluationReportType"])
        self.assertEqual(EVALUATION_SUBJECT, card["evaluationSubject"])
        self.assertEqual(METRICS_SUBJECT, card["metricsSubject"])
        self.assertEqual(METRIC_BASIS, card["metricBasis"])
        self.assertEqual("OFFLINE_DIAGNOSTIC", card["evaluationPurpose"])
        self.assertEqual("NONE", card["runtimeDecisionAuthority"])
        self.assertEqual("NONE", card["promotionAuthority"])
        self.assertEqual("NONE", card["thresholdChangeAuthority"])
        self.assertEqual("NONE", card["paymentAuthorizationAuthority"])
        self.assertEqual("NONE", card["workflowAuthority"])
        self.assertIn("allowedUsageModes", card)
        self.assertEqual({"SHADOW", "COMPARE", "OFFLINE_EVALUATION"}, set(card["allowedUsageModes"]))
        self.assertNotIn("disagreementSummary", card["metricsSummary"])

    def test_acceptsCanonicalSummaryAndManifestFilenames(self):
        with self.artifacts() as paths:
            card = self.generate(paths)

        self.assertEqual("PLATFORM_RECOMMENDATION_EVALUATION_CARD_V1", card["cardType"])

    def test_validatesManifestBeforeSummaryIsTrusted(self):
        with self.artifacts() as paths:
            manifest = json.loads(paths["manifest"].read_text(encoding="utf-8"))
            manifest["files"] = []
            paths["manifest"].write_text(json.dumps(manifest), encoding="utf-8")

            with self.assertRaises(Fdp123EvaluationCardValidationError):
                self.generate(paths)

    def test_failsIfManifestMissing(self):
        with self.artifacts() as paths:
            paths["manifest"].unlink()
            with self.assertRaises(Fdp123EvaluationCardValidationError):
                self.generate(paths)

    def test_failsIfEvaluationSummaryMissing(self):
        with self.artifacts() as paths:
            paths["evaluationSummary"].unlink()
            with self.assertRaises(Fdp123EvaluationCardValidationError):
                self.generate(paths)

    def test_failsIfManifestHashMismatch(self):
        with self.artifacts() as paths:
            self._mutate_manifest_entry(paths, sha256="b" * 64)
            with self.assertRaises(Fdp123EvaluationCardValidationError):
                self.generate(paths)

    def test_failsIfManifestSizeMismatch(self):
        with self.artifacts() as paths:
            self._mutate_manifest_entry(paths, sizeBytes=1)
            with self.assertRaises(Fdp123EvaluationCardValidationError):
                self.generate(paths)

    def test_renamedSummaryRejectedEvenWithMatchingManifestHashAndSize(self):
        with self.artifacts() as paths:
            renamed = paths["evaluationSummary"].with_name("foo.json")
            payload = paths["evaluationSummary"].read_bytes()
            renamed.write_bytes(payload)
            manifest = json.loads(paths["manifest"].read_text(encoding="utf-8"))
            for item in manifest["files"]:
                if item["name"] == "evaluation_summary.json":
                    item["name"] = "foo.json"
                    item["sha256"] = hashlib.sha256(payload).hexdigest()
                    item["sizeBytes"] = len(payload)
            paths["manifest"].write_text(json.dumps(manifest), encoding="utf-8")

            with self.assertRaises(Fdp123EvaluationCardValidationError):
                generate_evaluation_card_from_fdp124_artifacts(
                    renamed,
                    paths["manifest"],
                    model_metadata(),
                    PLATFORM_RECOMMENDATION_EVALUATION_CARD_GENERATED_AT,
                )

    def test_renamedManifestRejectedEvenWithValidContent(self):
        with self.artifacts() as paths:
            renamed = paths["manifest"].with_name("random-manifest.json")
            renamed.write_bytes(paths["manifest"].read_bytes())

            with self.assertRaises(Fdp123EvaluationCardValidationError):
                generate_evaluation_card_from_fdp124_artifacts(
                    paths["evaluationSummary"],
                    renamed,
                    model_metadata(),
                    PLATFORM_RECOMMENDATION_EVALUATION_CARD_GENERATED_AT,
                )

    def test_manifestListingOtherJsonDoesNotSatisfyEvaluationSummaryContract(self):
        with self.artifacts() as paths:
            payload = paths["evaluationSummary"].read_bytes()
            manifest = json.loads(paths["manifest"].read_text(encoding="utf-8"))
            for item in manifest["files"]:
                if item["name"] == "evaluation_summary.json":
                    item["name"] = "other.json"
                    item["sha256"] = hashlib.sha256(payload).hexdigest()
                    item["sizeBytes"] = len(payload)
            paths["manifest"].write_text(json.dumps(manifest), encoding="utf-8")

            with self.assertRaises(Fdp123EvaluationCardValidationError):
                self.generate(paths)

    def test_manifestWithZeroEvaluationSummaryEntriesRejected(self):
        with self.artifacts() as paths:
            manifest = json.loads(paths["manifest"].read_text(encoding="utf-8"))
            manifest["files"] = [item for item in manifest["files"] if item["name"] != "evaluation_summary.json"]
            paths["manifest"].write_text(json.dumps(manifest), encoding="utf-8")

            with self.assertRaises(Fdp123EvaluationCardValidationError):
                self.generate(paths)

    def test_manifestWithMultipleEvaluationSummaryEntriesRejected(self):
        with self.artifacts() as paths:
            manifest = json.loads(paths["manifest"].read_text(encoding="utf-8"))
            summary_entry = next(item for item in manifest["files"] if item["name"] == "evaluation_summary.json")
            manifest["files"].append(dict(summary_entry))
            paths["manifest"].write_text(json.dumps(manifest), encoding="utf-8")

            with self.assertRaises(Fdp123EvaluationCardValidationError):
                self.generate(paths)

    def test_failsIfManifestReportTypeUnsupported(self):
        with self.artifacts() as paths:
            self._mutate_manifest(paths, reportType="OTHER")
            with self.assertRaises(Fdp123EvaluationCardValidationError):
                self.generate(paths)

    def test_failsIfManifestArtifactSetVersionUnsupported(self):
        with self.artifacts() as paths:
            self._mutate_manifest(paths, artifactSetVersion="other")
            with self.assertRaises(Fdp123EvaluationCardValidationError):
                self.generate(paths)

    def test_failsIfEvaluationSummaryReportTypeUnsupported(self):
        with self.artifacts() as paths:
            self._mutate_summary(paths, reportType="OTHER")
            self._rewrite_manifest(paths)
            with self.assertRaises(Fdp123EvaluationCardValidationError):
                self.generate(paths)

    def test_failsIfSummaryMissingGeneratedAt(self):
        self._assert_summary_missing("generatedAt")

    def test_failsIfEvaluationSummaryGeneratedAtIsNotRealTimestamp(self):
        for value in ("banana", "2026-06-12", "2026-06-12T00:00:00", "2026-13-40T00:00:00Z"):
            with self.subTest(value=value):
                with self.artifacts() as paths:
                    self._mutate_summary(paths, generatedAt=value)
                    with self.assertRaises(Fdp123EvaluationCardValidationError):
                        self.generate(paths)

    def test_acceptsAndNormalizesExplicitOffsetEvaluationTimestamp(self):
        with self.artifacts() as paths:
            self._mutate_summary(paths, generatedAt="2026-06-10T02:00:00+02:00")
            card = self.generate(paths)

        self.assertEqual("2026-06-10T00:00:00Z", card["evaluationEvidence"]["evaluationGeneratedAt"])

    def test_failsIfEvaluationCardGeneratedBeforeEvaluationEvidence(self):
        with self.artifacts() as paths:
            with self.assertRaises(Fdp123EvaluationCardValidationError):
                self.generate(paths, generated_at="2026-06-09T23:59:59Z")

    def test_acceptsEvaluationCardGeneratedAtSameInstantAsEvaluationEvidence(self):
        with self.artifacts() as paths:
            self._mutate_summary(paths, generatedAt=PLATFORM_RECOMMENDATION_EVALUATION_CARD_GENERATED_AT)
            card = self.generate(paths)

        self.assertEqual(PLATFORM_RECOMMENDATION_EVALUATION_CARD_GENERATED_AT, card["generatedAt"])

    def test_acceptsEvaluationCardGeneratedAfterEvaluationEvidence(self):
        with self.artifacts() as paths:
            card = self.generate(paths, generated_at="2026-06-12T00:00:01Z")

        self.assertEqual("2026-06-12T00:00:01Z", card["generatedAt"])

    def test_failsIfQualityMetricsMissing(self):
        self._assert_summary_missing("qualityMetrics")

    def test_failsIfDatasetSummaryMissing(self):
        with self.artifacts() as paths:
            summary = self._summary(paths)
            summary["qualityMetrics"].pop("datasetSummary")
            self._write_summary(paths, summary)
            with self.assertRaises(Fdp123EvaluationCardValidationError):
                self.generate(paths)

    def test_failsIfClassBalanceMissing(self):
        with self.artifacts() as paths:
            summary = self._summary(paths)
            summary["qualityMetrics"].pop("classBalance")
            self._write_summary(paths, summary)
            with self.assertRaises(Fdp123EvaluationCardValidationError):
                self.generate(paths)

    def test_failsIfAlertRecommendedConfusionMatrixMissing(self):
        with self.artifacts() as paths:
            summary = self._summary(paths)
            summary["qualityMetrics"].pop("alertRecommendedConfusionMatrix")
            self._write_summary(paths, summary)
            with self.assertRaises(Fdp123EvaluationCardValidationError):
                self.generate(paths)

    def test_failsIfMetricObjectShapeInvalid(self):
        with self.artifacts() as paths:
            summary = self._summary(paths)
            summary["qualityMetrics"]["alertRecommendedConfusionMatrix"]["precision"] = {"value": 0.5}
            self._write_summary(paths, summary)
            with self.assertRaises(Fdp123EvaluationCardValidationError):
                self.generate(paths)

    def test_failsIfEvaluationSummarySubjectUnsupported(self):
        with self.artifacts() as paths:
            summary = self._summary(paths)
            summary["evaluationSubject"] = dict(EVALUATION_SUBJECT)
            summary["evaluationSubject"]["sourceVersion"] = "OTHER"
            self._write_summary(paths, summary)
            with self.assertRaises(Fdp123EvaluationCardValidationError):
                self.generate(paths)

    def test_failsIfEvaluationSummaryMetricBasisUnsupported(self):
        with self.artifacts() as paths:
            summary = self._summary(paths)
            summary["metricBasis"] = "MODEL_PERFORMANCE"
            self._write_summary(paths, summary)
            with self.assertRaises(Fdp123EvaluationCardValidationError):
                self.generate(paths)

    def test_failsIfQualityMetricsMetricBasisUnsupported(self):
        with self.artifacts() as paths:
            summary = self._summary(paths)
            summary["qualityMetrics"]["metricBasis"] = "MODEL_PERFORMANCE"
            self._write_summary(paths, summary)
            with self.assertRaises(Fdp123EvaluationCardValidationError):
                self.generate(paths)

    def test_failsIfSourceWarningsContainNonStringEvidence(self):
        with self.artifacts() as paths:
            summary = self._summary(paths)
            summary["warnings"] = ["LOW_SAMPLE_SIZE", 123]
            self._write_summary(paths, summary)

            with self.assertRaises(Fdp123EvaluationCardValidationError):
                self.generate(paths)

    def test_failsIfClassCountSumBelowRecordsEvaluated(self):
        with self.artifacts() as paths:
            summary = self._summary(paths)
            summary["qualityMetrics"]["datasetSummary"]["recordsEvaluated"] = 100
            summary["qualityMetrics"]["classBalance"]["positiveClassCount"] = 10
            summary["qualityMetrics"]["classBalance"]["negativeClassCount"] = 10
            self._write_summary(paths, summary)

            with self.assertRaises(Fdp123EvaluationCardValidationError):
                self.generate(paths)

    def test_failsIfClassCountSumAboveRecordsEvaluated(self):
        with self.artifacts() as paths:
            summary = self._summary(paths)
            summary["qualityMetrics"]["datasetSummary"]["recordsEvaluated"] = 1
            summary["qualityMetrics"]["classBalance"]["positiveClassCount"] = 1
            summary["qualityMetrics"]["classBalance"]["negativeClassCount"] = 1
            self._write_summary(paths, summary)

            with self.assertRaises(Fdp123EvaluationCardValidationError):
                self.generate(paths)

    def test_acceptsZeroClassCountsForEmptyEvaluation(self):
        with self.artifacts() as paths:
            summary = self._summary(paths)
            summary["datasetMetadata"]["recordsReturned"] = 0
            summary["qualityMetrics"]["datasetSummary"]["recordsEvaluated"] = 0
            summary["qualityMetrics"]["datasetSummary"]["recordsReturned"] = 0
            summary["qualityMetrics"]["classBalance"]["positiveClassCount"] = 0
            summary["qualityMetrics"]["classBalance"]["negativeClassCount"] = 0
            self._write_summary(paths, summary)
            card = self.generate(paths)

        self.assertEqual(0, card["evaluationEvidence"]["recordsEvaluated"])

    def test_malformedSourceDisagreementSummaryIsNotConsumedOrEmitted(self):
        with self.artifacts() as paths:
            summary = self._summary(paths)
            summary["disagreementSummary"] = {"unexpected": {"nested": "not-count"}}
            self._write_summary(paths, summary)
            card = self.generate(paths)

        self.assertNotIn("disagreementSummary", card["metricsSummary"])

    def test_summaryFileExactlyAtLimitUsesNormalParsing(self):
        with self.artifacts() as paths:
            payload = paths["evaluationSummary"].read_bytes()
            padding = b" " * (MAX_EVALUATION_SUMMARY_BYTES - len(payload))
            paths["evaluationSummary"].write_bytes(payload + padding)
            self._rewrite_manifest(paths)

            card = self.generate(paths)

        self.assertEqual("PLATFORM_RECOMMENDATION_EVALUATION_CARD_V1", card["cardType"])

    def test_summaryFileAboveLimitRejectedBeforeTrustingManifestSize(self):
        with self.artifacts() as paths:
            paths["evaluationSummary"].write_bytes(b" " * (MAX_EVALUATION_SUMMARY_BYTES + 1))

            with self.assertRaises(Fdp123EvaluationCardValidationError):
                self.generate(paths)

    def test_manifestFileExactlyAtLimitUsesNormalParsing(self):
        with self.artifacts() as paths:
            payload = paths["manifest"].read_bytes()
            padding = b" " * (MAX_EVALUATION_MANIFEST_BYTES - len(payload))
            paths["manifest"].write_bytes(payload + padding)

            card = self.generate(paths)

        self.assertEqual("PLATFORM_RECOMMENDATION_EVALUATION_CARD_V1", card["cardType"])

    def test_manifestFileAboveLimitRejectedBeforeJsonParsing(self):
        with self.artifacts() as paths:
            paths["manifest"].write_bytes(b" " * (MAX_EVALUATION_MANIFEST_BYTES + 1))

            with self.assertRaises(Fdp123EvaluationCardValidationError):
                self.generate(paths)

    def test_failsIfGovernanceMetadataMissingRequiredFields(self):
        with self.artifacts() as paths:
            metadata = model_metadata()
            metadata.pop("allowedUsageModes")
            with self.assertRaises(Fdp123EvaluationCardValidationError):
                self.generate(paths, metadata=metadata)

    def test_failsIfGovernanceMetadataContainsCallerControlledIdentity(self):
        with self.artifacts() as paths:
            metadata = model_metadata(modelName="caller-controlled")
            with self.assertRaises(Fdp123EvaluationCardValidationError):
                self.generate(paths, metadata=metadata)

    def test_doesNotReadDisagreementJsonl(self):
        with self.artifacts() as paths:
            paths["disagreementReport"].write_text("transactionId unsafe\n", encoding="utf-8")
            card = self.generate(paths)

        self.assertEqual("PLATFORM_RECOMMENDATION_EVALUATION_CARD_V1", card["cardType"])

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

    def generate(self, paths, metadata=None, generated_at=PLATFORM_RECOMMENDATION_EVALUATION_CARD_GENERATED_AT):
        return generate_evaluation_card_from_fdp124_artifacts(
            paths["evaluationSummary"],
            paths["manifest"],
            metadata or model_metadata(),
            generated_at,
        )

    def _assert_summary_missing(self, field):
        with self.artifacts() as paths:
            summary = self._summary(paths)
            summary.pop(field)
            self._write_summary(paths, summary)
            with self.assertRaises(Fdp123EvaluationCardValidationError):
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
