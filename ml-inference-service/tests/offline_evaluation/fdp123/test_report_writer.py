import json
import tempfile
import unittest
from pathlib import Path

from offline_evaluation.fdp123.dataset_reader import read_fdp123_feedback_dataset_jsonl
from offline_evaluation.fdp123.evaluation_runner import build_fdp123_evaluation_reports
from offline_evaluation.fdp123.report_writer import disagreement_jsonl, report_json, write_fdp123_reports
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

    def test_reportWarningsAreBounded(self):
        report = self._reports()["evaluationSummary"]
        report["warnings"] = [f"W{index}" for index in range(20)]

        self.assertLessEqual(len(json.loads(report_json(report))["warnings"]), 10)

    def test_summaryDoesNotContainPseudonymousIdentifiers(self):
        payload = report_json(self._reports()["evaluationSummary"])

        self.assertNotIn("eval_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", payload)
        self.assertNotIn("txnref_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", payload)

    def test_disagreementJsonlContainsAllowedPseudonymousIdentifiers(self):
        payload = disagreement_jsonl(self._reports(record(fraudScore=0.1))["disagreementReport"])

        self.assertIn("evaluationRecordId", payload)
        self.assertIn("transactionReference", payload)
        self.assertIn("eval_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", payload)

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

    def _reports(self, *records):
        records = records or (record(),)
        with jsonl_file(jsonl(*records)) as path:
            dataset = read_fdp123_feedback_dataset_jsonl(path)
        return build_fdp123_evaluation_reports(dataset, generated_at=GENERATED_AT)


if __name__ == "__main__":
    unittest.main()
