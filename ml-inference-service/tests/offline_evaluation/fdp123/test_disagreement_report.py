import unittest

from offline_evaluation.fdp123.dataset_reader import read_fdp123_feedback_dataset_jsonl
from offline_evaluation.fdp123.disagreement_report import build_fdp123_disagreement_report
try:
    from fdp123.fdp123_fixtures import jsonl, jsonl_file, record
except ModuleNotFoundError:
    from fdp123_fixtures import jsonl, jsonl_file, record


class Fdp123DisagreementReportTest(unittest.TestCase):
    def test_detectsHighScoreNegativeFeedback(self):
        report = self._report(record(feedbackLabel="CONFIRMED_LEGITIMATE", evaluationLabel="NEGATIVE_LEGITIMATE", fraudScore=0.9))

        self.assertIn("HIGH_SCORE_NEGATIVE_FEEDBACK", report["rows"][0]["types"])

    def test_detectsLowScorePositiveFeedback(self):
        report = self._report(record(fraudScore=0.1))

        self.assertIn("LOW_SCORE_POSITIVE_FEEDBACK", report["rows"][0]["types"])

    def test_detectsAlertRecommendedNegativeFeedback(self):
        report = self._report(record(feedbackLabel="CONFIRMED_LEGITIMATE", evaluationLabel="NEGATIVE_LEGITIMATE", alertRecommended=True))

        self.assertIn("ALERT_RECOMMENDED_NEGATIVE_FEEDBACK", report["rows"][0]["types"])

    def test_detectsNoAlertPositiveFeedback(self):
        report = self._report(record(alertRecommended=False))

        self.assertIn("NO_ALERT_POSITIVE_FEEDBACK", report["rows"][0]["types"])

    def test_usesOnlySafePseudonymousIdentifiers(self):
        report = self._report(record(fraudScore=0.1))
        row = report["rows"][0]

        self.assertIn("evaluationRecordId", row)
        self.assertIn("transactionReference", row)
        self.assertNotIn("transactionId", row)
        self.assertNotIn("feedbackId", row)
        self.assertNotIn("notes", row)

    def test_reportIsBounded(self):
        records = [
            record(
                evaluationRecordId=f"eval_{index:032x}",
                transactionReference=f"txnref_{index:032x}",
                fraudScore=0.1,
            )
            for index in range(5)
        ]

        report = self._report(*records, max_rows=2)

        self.assertEqual(2, len(report["rows"]))
        self.assertTrue(report["summary"]["truncated"])
        self.assertEqual(5, report["summary"]["totalDisagreementRows"])

    def _report(self, *records, max_rows=100):
        with jsonl_file(jsonl(*records)) as path:
            dataset = read_fdp123_feedback_dataset_jsonl(path)
        return build_fdp123_disagreement_report(dataset.records, max_rows=max_rows)


if __name__ == "__main__":
    unittest.main()
