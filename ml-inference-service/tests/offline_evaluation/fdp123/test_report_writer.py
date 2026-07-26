import hashlib
import json
import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from offline_evaluation.fdp123.dataset_reader import read_fdp123_feedback_dataset_jsonl
from offline_evaluation.fdp123.evaluation_contract import EVALUATION_SUBJECT, METRIC_BASIS, METRICS_SUBJECT
from offline_evaluation.fdp123.evaluation_runner import build_fdp123_evaluation_reports, run_fdp123_evaluation
from offline_evaluation.fdp123.run_fdp123_evaluation import main
from offline_evaluation.fdp123.report_writer import (
    REPORT_TYPE,
    build_artifact_manifest,
    disagreement_jsonl,
    report_json,
    write_fdp123_reports,
)
from fdp123.evaluation_card.test_schema import INVALID_CANONICAL_TIMESTAMPS, VALID_CANONICAL_TIMESTAMPS
try:
    from fdp123.fdp123_fixtures import GENERATED_AT, jsonl, jsonl_file, record
except ModuleNotFoundError:
    from fdp123_fixtures import GENERATED_AT, jsonl, jsonl_file, record


class Fdp123ReportWriterTest(unittest.TestCase):
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
            paths = write_fdp123_reports(reports, Path(directory))

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
                write_fdp123_reports(self._reports(), link)

    def test_writeReportsRejectsSymlinkFinalArtifactPath(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "output"
            output.mkdir()
            target = Path(directory) / "target.json"
            target.write_text("{}", encoding="utf-8")
            link = output / "evaluation_summary.json"
            try:
                link.symlink_to(target)
            except OSError as exception:
                self.skipTest(f"symlink creation is unavailable: {exception}")

            with self.assertRaises(ValueError):
                write_fdp123_reports(self._reports(), output)

    def test_writeReportsRejectsSymlinkManifestPath(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "output"
            output.mkdir()
            target = Path(directory) / "target.json"
            target.write_text("{}", encoding="utf-8")
            link = output / "manifest.json"
            try:
                link.symlink_to(target)
            except OSError as exception:
                self.skipTest(f"symlink creation is unavailable: {exception}")

            with self.assertRaises(ValueError):
                write_fdp123_reports(self._reports(), output)

    def test_writeReportsUsesTempFilesThenFinalReplace(self):
        reports = self._reports(record(fraudScore=0.1))
        original_replace = os.replace
        replace_calls = []

        def recording_replace(source, destination):
            replace_calls.append((Path(source).name, Path(destination).name))
            return original_replace(source, destination)

        with tempfile.TemporaryDirectory() as directory:
            with patch("offline_evaluation.fdp123.report_writer.os.replace", recording_replace):
                write_fdp123_reports(reports, Path(directory))

        self.assertIn(("evaluation_summary.json.tmp", "evaluation_summary.json"), replace_calls)
        self.assertIn(("disagreement_report.jsonl.tmp", "disagreement_report.jsonl"), replace_calls)
        self.assertEqual(("manifest.json.tmp", "manifest.json"), replace_calls[-1])

    def test_manifestListsExpectedArtifactFiles(self):
        reports = self._reports(record(fraudScore=0.1))

        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            write_fdp123_reports(reports, output)

            manifest = json.loads((output / "manifest.json").read_text(encoding="utf-8"))
            self.assertEqual(REPORT_TYPE, manifest["reportType"])
            self.assertEqual("fdp123-report-artifact-set-v1", manifest["artifactSetVersion"])
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

    def test_manifestHashesMatchWrittenFiles(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            write_fdp123_reports(self._reports(record(fraudScore=0.1)), output)

            manifest = json.loads((output / "manifest.json").read_text(encoding="utf-8"))
            for item in manifest["files"]:
                payload = (output / item["name"]).read_bytes()
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
                write_fdp123_reports(reports, output)

            self.assertEqual([], list(output.glob("*.tmp")))

    def test_writeReportsDoesNotCreateFinalFilesWhenValidationFailsBeforeReplace(self):
        reports = self._reports()
        reports["evaluationSummary"]["rawNotes"] = "unsafe"

        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            with self.assertRaises(ValueError):
                write_fdp123_reports(reports, output)

            self.assertEqual([], list(output.iterdir()))

    def test_replaceFailureBeforeManifestDoesNotCreateManifest(self):
        def failing_replace(source, destination):
            raise OSError("simulated replace failure")

        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            with patch("offline_evaluation.fdp123.report_writer.os.replace", failing_replace):
                with self.assertRaises(OSError):
                    write_fdp123_reports(self._reports(record(fraudScore=0.1)), output)

            self.assertFalse((output / "manifest.json").exists())
            self.assertFalse((output / "manifest.json.tmp").exists())
            self.assertEqual([], list(output.glob("*.tmp")))

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
            with patch("offline_evaluation.fdp123.report_writer.os.replace", failing_second_replace):
                with self.assertRaises(OSError):
                    write_fdp123_reports(self._reports(record(fraudScore=0.1)), output)

            self.assertTrue((output / replace_calls[0]).exists())
            self.assertFalse((output / "manifest.json").exists())
            self.assertFalse((output / "manifest.json.tmp").exists())
            self.assertEqual([], list(output.glob("*.tmp")))

    def test_runRejectsOutputOutsideAllowedRoot(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "allowed"
            output = Path(directory) / "outside"
            with jsonl_file(jsonl(record())) as input_path:
                with self.assertRaises(ValueError):
                    run_fdp123_evaluation(input_path, output, allow_output_root=root)

    def test_runAcceptsOutputInsideAllowedRoot(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "allowed"
            output = root / "reports"
            with jsonl_file(jsonl(record())) as input_path:
                paths = run_fdp123_evaluation(input_path, output, allow_output_root=root)

            self.assertTrue(paths["manifest"].exists())
            self.assertTrue((output / "evaluation_summary.json").exists())

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
            self.assertTrue((output / "manifest.json").exists())

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

            summary = json.loads((output / "evaluation_summary.json").read_text(encoding="utf-8"))
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
                            run_fdp123_evaluation(input_path, output, generated_at=generated_at)

                    self.assertFalse((output / "evaluation_summary.json").exists())
                    self.assertFalse((output / "manifest.json").exists())
                    self.assertEqual([], list(output.glob("*.tmp")) if output.exists() else [])

    def _reports(self, *records):
        records = records or (record(),)
        with jsonl_file(jsonl(*records)) as path:
            dataset = read_fdp123_feedback_dataset_jsonl(path)
        return build_fdp123_evaluation_reports(dataset, generated_at=GENERATED_AT)


if __name__ == "__main__":
    unittest.main()
