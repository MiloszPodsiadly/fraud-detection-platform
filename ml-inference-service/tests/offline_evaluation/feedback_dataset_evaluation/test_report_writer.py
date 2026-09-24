import hashlib
import json
import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from offline_evaluation.feedback_dataset_evaluation.dataset_reader import read_feedback_dataset_jsonl
from offline_evaluation.feedback_dataset_evaluation.evaluation_contract import EVALUATION_SUBJECT, METRIC_BASIS, METRICS_SUBJECT
from offline_evaluation.feedback_dataset_evaluation.evaluation_runner import build_feedback_dataset_evaluation_reports, run_feedback_dataset_evaluation
from offline_evaluation.feedback_dataset_evaluation.model_evaluation import ModelEvaluationIdentity
from offline_evaluation.feedback_dataset_evaluation.run_feedback_dataset_evaluation import main
from offline_evaluation.feedback_dataset_evaluation.report_writer import (
    REPORT_TYPE,
    build_artifact_manifest,
    disagreement_jsonl,
    report_json,
    write_feedback_dataset_evaluation_reports,
)
from feedback_dataset_evaluation.evaluation_card.test_schema import INVALID_CANONICAL_TIMESTAMPS, VALID_CANONICAL_TIMESTAMPS
try:
    from feedback_dataset_evaluation.feedback_dataset_fixtures import GENERATED_AT, jsonl, jsonl_file, record
except ModuleNotFoundError:
    from feedback_dataset_fixtures import GENERATED_AT, jsonl, jsonl_file, record


class FeedbackDatasetEvaluationReportWriterTest(unittest.TestCase):
    def test_summaryJsonIsDeterministic(self):
        report = self._reports()["evaluationSummary"]

        self.assertEqual(report_json(report), report_json(report))

    def test_reportContainsExpectedSections(self):
        payload = json.loads(report_json(self._reports()["evaluationSummary"]))

        self.assertIn("qualityMetrics", payload)
        self.assertIn("disagreementSummary", payload)
        self.assertIn("datasetMetadata", payload)
        self.assertEqual(EVALUATION_SUBJECT, payload["evaluationSubject"])
        self.assertEqual(METRICS_SUBJECT, payload["metricsSubject"])
        self.assertEqual(METRIC_BASIS, payload["metricBasis"])
        self.assertEqual(METRIC_BASIS, payload["qualityMetrics"]["metricBasis"])

    def test_reportWarningsAreBounded(self):
        report = self._reports()["evaluationSummary"]
        report["warnings"] = [f"W{index}" for index in range(20)]

        with self.assertRaisesRegex(ValueError, "warnings exceeds maximum item count"):
            report_json(report)

    def test_summaryDoesNotContainPseudonymousIdentifiers(self):
        payload = report_json(self._reports()["evaluationSummary"])

        self.assertNotIn("eval_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", payload)
        self.assertNotIn("txnref_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", payload)

    def test_disagreementJsonlContainsAllowedPseudonymousIdentifiers(self):
        payload = disagreement_jsonl(self._reports(record(fraudScore=0.1))["disagreementReport"])

        self.assertIn("evaluationRecordId", payload)
        self.assertIn("transactionReference", payload)
        self.assertIn("eval_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", payload)

    def test_disagreementJsonlKeepsBoundedDecisionReasonCodesOnly(self):
        payload = disagreement_jsonl(self._reports(record(fraudScore=0.1))["disagreementReport"])

        self.assertIn("decisionReasonCodes", payload)
        self.assertIn("CUSTOMER_CONFIRMED_FRAUD", payload)
        self.assertNotIn("notes", payload)
        self.assertNotIn("rawEvidence", payload)

    def test_reportRejectsForbiddenRawFields(self):
        with self.assertRaises(ValueError):
            report_json({"transactionId": "raw-1"})

    def test_reportRejectsNotesAndPayloads(self):
        with self.assertRaises(ValueError):
            report_json({"rawNotes": "unsafe"})

    def test_writeReportsCreatesLocalArtifacts(self):
        reports = self._reports(record(fraudScore=0.1))

        with tempfile.TemporaryDirectory() as directory:
            paths = write_feedback_dataset_evaluation_reports(reports, Path(directory))

            self.assertTrue(paths["evaluationSummary"].exists())
            self.assertTrue(paths["scoreBucketReport"].exists())
            self.assertTrue(paths["riskLevelReport"].exists())
            self.assertTrue(paths["disagreementReport"].exists())
            self.assertTrue(paths["evaluationRunMarkdown"].exists())
            self.assertTrue(paths["manifest"].exists())

    def test_writeReportsRejectsSymlinkOutputDirectory(self):
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / "target"
            target.mkdir()
            link = Path(directory) / "link"
            try:
                link.symlink_to(target, target_is_directory=True)
            except OSError as exception:
                self.skipTest(f"symlink creation is unavailable: {exception}")

            with self.assertRaises(ValueError):
                write_feedback_dataset_evaluation_reports(self._reports(), link)

    def test_writeReportsRejectsSymlinkFinalArtifactPath(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "output"
            platform = output / "platform-evaluation"
            platform.mkdir(parents=True)
            target = Path(directory) / "target.json"
            target.write_text("{}", encoding="utf-8")
            link = platform / "evaluation_summary.json"
            try:
                link.symlink_to(target)
            except OSError as exception:
                self.skipTest(f"symlink creation is unavailable: {exception}")

            with self.assertRaises(ValueError):
                write_feedback_dataset_evaluation_reports(self._reports(), output)

    def test_writeReportsRejectsSymlinkManifestPath(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "output"
            platform = output / "platform-evaluation"
            platform.mkdir(parents=True)
            target = Path(directory) / "target.json"
            target.write_text("{}", encoding="utf-8")
            link = platform / "manifest.json"
            try:
                link.symlink_to(target)
            except OSError as exception:
                self.skipTest(f"symlink creation is unavailable: {exception}")

            with self.assertRaises(ValueError):
                write_feedback_dataset_evaluation_reports(self._reports(), output)

    def test_writeReportsUsesTempFilesThenFinalReplace(self):
        reports = self._reports(record(fraudScore=0.1))
        original_replace = os.replace
        replace_calls = []

        def recording_replace(source, destination):
            replace_calls.append((Path(source).name, Path(destination).name))
            return original_replace(source, destination)

        with tempfile.TemporaryDirectory() as directory:
            with patch("offline_evaluation.feedback_dataset_evaluation.report_writer.os.replace", recording_replace):
                write_feedback_dataset_evaluation_reports(reports, Path(directory))

        self.assertIn(("evaluation_summary.json.tmp", "evaluation_summary.json"), replace_calls)
        self.assertIn(("disagreement_report.jsonl.tmp", "disagreement_report.jsonl"), replace_calls)
        self.assertEqual(("manifest.json.tmp", "manifest.json"), replace_calls[-1])

    def test_manifestListsExpectedArtifactFiles(self):
        reports = self._reports(record(fraudScore=0.1))

        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            write_feedback_dataset_evaluation_reports(reports, output)

            manifest = json.loads((output / "platform-evaluation" / "manifest.json").read_text(encoding="utf-8"))
            self.assertEqual(REPORT_TYPE, manifest["reportType"])
            self.assertEqual("feedback-dataset-evaluation-report-artifact-set-v1", manifest["artifactSetVersion"])
            self.assertEqual(GENERATED_AT, manifest["generatedAt"])
            self.assertEqual(
                [
                    "disagreement_report.jsonl",
                    "evaluation_run.md",
                    "evaluation_summary.json",
                    "risk_level_report.json",
                    "score_bucket_report.json",
                ],
                [item["name"] for item in manifest["files"]],
            )

    def test_modelEvaluationSummaryUsesIndependentArtifactSetWhenExactModelRequested(self):
        reports = self._reports(
            record(
                fraudScore=0.1,
                mlModelName="python-logistic-fraud-model",
                mlModelVersion="2026-06-25.v1",
                mlFeatureContractVersion="feature-contract-v2",
            ),
            model_identity=self._model_identity(),
        )

        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            write_feedback_dataset_evaluation_reports(reports, output)

            platform_manifest = json.loads((output / "platform-evaluation" / "manifest.json").read_text(encoding="utf-8"))
            model_manifest = json.loads((output / "model-evaluation" / "manifest.json").read_text(encoding="utf-8"))
            self.assertTrue((output / "model-evaluation" / "model_evaluation_summary.json").exists())
            self.assertNotIn("model_evaluation_summary.json", [item["name"] for item in platform_manifest["files"]])
            self.assertEqual("ML_MODEL_FEEDBACK_DATASET_EVALUATION_V1", model_manifest["reportType"])
            self.assertEqual(
                "ml-model-feedback-dataset-evaluation-artifact-set-v1",
                model_manifest["artifactSetVersion"],
            )
            self.assertEqual(["model_evaluation_summary.json"], [item["name"] for item in model_manifest["files"]])

    def test_writerRejectsInvalidModelEvaluationSummaryBeforeCreatingArtifacts(self):
        reports = self._reports(
            record(
                fraudScore=0.1,
                mlModelName="python-logistic-fraud-model",
                mlModelVersion="2026-06-25.v1",
                mlFeatureContractVersion="feature-contract-v2",
            ),
            model_identity=self._model_identity(),
        )
        reports["modelEvaluationSummary"]["unexpected"] = "field"

        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            with self.assertRaisesRegex(ValueError, "unsupported fields"):
                write_feedback_dataset_evaluation_reports(reports, output)

            self.assertEqual([], list(output.iterdir()))

    def test_manifestHashesMatchWrittenFiles(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            write_feedback_dataset_evaluation_reports(self._reports(record(fraudScore=0.1)), output)

            manifest_path = output / "platform-evaluation" / "manifest.json"
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
            for item in manifest["files"]:
                payload = (manifest_path.parent / item["name"]).read_bytes()
                self.assertEqual(len(payload), item["sizeBytes"])
                self.assertEqual(hashlib.sha256(payload).hexdigest(), item["sha256"])

    def test_manifestPayloadRejectsForbiddenTerms(self):
        with self.assertRaises(ValueError):
            build_artifact_manifest({Path("rawNotes.json"): "{}\n"}, GENERATED_AT)

    def test_writeReportsCleansTempFilesOnValidationFailure(self):
        reports = self._reports()
        reports["evaluationSummary"]["transactionId"] = "raw-1"

        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            with self.assertRaises(ValueError):
                write_feedback_dataset_evaluation_reports(reports, output)

            self.assertEqual([], list(output.glob("*.tmp")))

    def test_writeReportsDoesNotCreateFinalFilesWhenValidationFailsBeforeReplace(self):
        reports = self._reports()
        reports["evaluationSummary"]["rawNotes"] = "unsafe"

        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            with self.assertRaises(ValueError):
                write_feedback_dataset_evaluation_reports(reports, output)

            self.assertEqual([], list(output.iterdir()))

    def test_replaceFailureBeforeManifestDoesNotCreateManifest(self):
        def failing_replace(source, destination):
            raise OSError("simulated replace failure")

        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            with patch("offline_evaluation.feedback_dataset_evaluation.report_writer.os.replace", failing_replace):
                with self.assertRaises(OSError):
                    write_feedback_dataset_evaluation_reports(self._reports(record(fraudScore=0.1)), output)

            platform = output / "platform-evaluation"
            self.assertFalse((platform / "manifest.json").exists())
            self.assertFalse((platform / "manifest.json.tmp").exists())
            self.assertEqual([], list(output.rglob("*.tmp")))

    def test_replaceFailureAfterArtifactReplaceDoesNotLeaveValidManifest(self):
        original_replace = os.replace
        replace_calls = []

        def failing_second_replace(source, destination):
            replace_calls.append(Path(destination).name)
            if len(replace_calls) == 2:
                raise OSError("simulated replace failure")
            return original_replace(source, destination)

        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            with patch("offline_evaluation.feedback_dataset_evaluation.report_writer.os.replace", failing_second_replace):
                with self.assertRaises(OSError):
                    write_feedback_dataset_evaluation_reports(self._reports(record(fraudScore=0.1)), output)

            platform = output / "platform-evaluation"
            self.assertTrue((platform / replace_calls[0]).exists())
            self.assertFalse((platform / "manifest.json").exists())
            self.assertFalse((platform / "manifest.json.tmp").exists())
            self.assertEqual([], list(output.rglob("*.tmp")))

    def test_runRejectsOutputOutsideAllowedRoot(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "allowed"
            output = Path(directory) / "outside"
            with jsonl_file(jsonl(record())) as input_path:
                with self.assertRaises(ValueError):
                    run_feedback_dataset_evaluation(input_path, output, allow_output_root=root)

    def test_runAcceptsOutputInsideAllowedRoot(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "allowed"
            output = root / "reports"
            with jsonl_file(jsonl(record())) as input_path:
                paths = run_feedback_dataset_evaluation(input_path, output, allow_output_root=root)

            self.assertTrue(paths["manifest"].exists())
            self.assertTrue((output / "platform-evaluation" / "evaluation_summary.json").exists())

    def test_cliAcceptsAllowOutputRootWhenInsideRoot(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "allowed"
            output = root / "reports"
            with jsonl_file(jsonl(record())) as input_path:
                result = main([
                    "--input", str(input_path),
                    "--output-dir", str(output),
                    "--allow-output-root", str(root),
                ])

            self.assertEqual(0, result)
            self.assertTrue((output / "platform-evaluation" / "manifest.json").exists())

    def test_cliWritesModelEvaluationSummaryWhenExactIdentityIsProvided(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "allowed"
            output = root / "reports"
            with jsonl_file(jsonl(record(
                mlModelName="python-logistic-fraud-model",
                mlModelVersion="2026-06-25.v1",
                mlFeatureContractVersion="feature-contract-v2",
            ))) as input_path:
                result = main([
                    "--input", str(input_path),
                    "--output-dir", str(output),
                    "--allow-output-root", str(root),
                    "--model-name", "python-logistic-fraud-model",
                    "--model-version", "2026-06-25.v1",
                    "--feature-contract-version", "feature-contract-v2",
                ])

            self.assertEqual(0, result)
            self.assertTrue((output / "model-evaluation" / "model_evaluation_summary.json").exists())
            self.assertTrue((output / "model-evaluation" / "manifest.json").exists())

    def test_cliRejectsPartialModelIdentity(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "out"
            with jsonl_file(jsonl(record())) as input_path:
                with self.assertRaises(SystemExit):
                    main([
                        "--input", str(input_path),
                        "--output-dir", str(output),
                        "--model-name", "python-logistic-fraud-model",
                    ])

    def test_cliRejectsAllowOutputRootWhenOutsideRoot(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "allowed"
            output = Path(directory) / "outside"
            with jsonl_file(jsonl(record())) as input_path:
                with self.assertRaises(ValueError):
                    main([
                        "--input", str(input_path),
                        "--output-dir", str(output),
                        "--allow-output-root", str(root),
                    ])

    def test_generatedAtCliOptionIsReflectedInOutput(self):
        generated_at = "2026-06-11T10:15:30Z"

        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "out"
            with jsonl_file(jsonl(record())) as input_path:
                result = main(["--input", str(input_path), "--output-dir", str(output), "--generated-at", generated_at])

            summary = json.loads((output / "platform-evaluation" / "evaluation_summary.json").read_text(encoding="utf-8"))
            self.assertEqual(0, result)
            self.assertEqual(generated_at, summary["generatedAt"])

    def test_fdp124WriterConsumesCanonicalTimestampFixture(self):
        for generated_at in VALID_CANONICAL_TIMESTAMPS:
            with self.subTest(generated_at=generated_at):
                reports = self._reports()
                for key in ("evaluationSummary", "scoreBucketReport", "riskLevelReport"):
                    reports[key]["generatedAt"] = generated_at

                self.assertIn(generated_at, build_artifact_manifest({
                    Path("evaluation_summary.json"): report_json(reports["evaluationSummary"]),
                }, generated_at))

    def test_invalidGeneratedAtCreatesNoFdp124ArtifactsOrTemps(self):
        for generated_at in INVALID_CANONICAL_TIMESTAMPS:
            if generated_at is None:
                continue
            with self.subTest(generated_at=generated_at):
                with tempfile.TemporaryDirectory() as directory:
                    output = Path(directory) / "out"
                    with jsonl_file(jsonl(record())) as input_path:
                        with self.assertRaises(ValueError):
                            run_feedback_dataset_evaluation(input_path, output, generated_at=generated_at)

                    self.assertFalse((output / "platform-evaluation" / "evaluation_summary.json").exists())
                    self.assertFalse((output / "platform-evaluation" / "manifest.json").exists())
                    self.assertEqual([], list(output.rglob("*.tmp")) if output.exists() else [])

    def _reports(self, *records, model_identity=None):
        records = records or (record(),)
        with jsonl_file(jsonl(*records)) as path:
            dataset = read_feedback_dataset_jsonl(path)
        return build_feedback_dataset_evaluation_reports(
            dataset,
            generated_at=GENERATED_AT,
            model_identity=model_identity,
        )

    def _model_identity(self):
        return ModelEvaluationIdentity(
            "python-logistic-fraud-model",
            "2026-06-25.v1",
            "feature-contract-v2",
        )


if __name__ == "__main__":
    unittest.main()
