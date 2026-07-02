import unittest

from offline_evaluation.fdp123.dataset_reader import read_fdp123_feedback_dataset_jsonl
from offline_evaluation.fdp123.dataset_schema import Fdp123DatasetValidationError, evaluation_label_value
try:
    from fdp123.fdp123_fixtures import jsonl, jsonl_file, record
except ModuleNotFoundError:
    from fdp123_fixtures import jsonl, jsonl_file, record


class Fdp123DatasetSchemaTest(unittest.TestCase):
    def test_positiveFraudMapsToPositiveClass(self):
        parsed = self._parse(record(feedbackLabel="CONFIRMED_FRAUD", evaluationLabel="POSITIVE_FRAUD"))

        self.assertEqual(1, evaluation_label_value(parsed.records[0]))
        self.assertTrue(parsed.records[0].is_positive_class)

    def test_negativeLegitimateMapsToNegativeClass(self):
        parsed = self._parse(record(feedbackLabel="CONFIRMED_LEGITIMATE", evaluationLabel="NEGATIVE_LEGITIMATE"))

        self.assertEqual(0, evaluation_label_value(parsed.records[0]))
        self.assertTrue(parsed.records[0].is_negative_class)

    def test_rejectsMismatchedFeedbackAndEvaluationLabel(self):
        self._assert_rejected(record(feedbackLabel="CONFIRMED_FRAUD", evaluationLabel="NEGATIVE_LEGITIMATE"))

    def test_rejectsInconclusiveFeedbackLabel(self):
        self._assert_rejected(record(feedbackLabel="INCONCLUSIVE", evaluationLabel="NEGATIVE_LEGITIMATE"))

    def test_rejectsNeedsMoreInfoFeedbackLabel(self):
        self._assert_rejected(record(feedbackLabel="NEEDS_MORE_INFO", evaluationLabel="NEGATIVE_LEGITIMATE"))

    def test_rejectsFdp102Labels(self):
        self._assert_rejected(record(evaluationLabel="ANALYST_CONFIRMED_FRAUD"))

    def test_rejectsRawIds(self):
        self._assert_rejected(record(transactionId="raw-txn-1"))

    def test_rejectsForbiddenFields(self):
        self._assert_rejected(record(groundTruth=True))

    def test_rejectsUnknownRecordFields(self):
        self._assert_rejected(record(safeOptionalButUnknown="value"))

    def test_rejectsMissingRequiredRecordField(self):
        payload = record()
        payload.pop("feedbackCreatedAt")

        self._assert_rejected(payload)

    def test_rejectsElevenDecisionReasonCodes(self):
        self._assert_rejected(record(decisionReasonCodes=[f"CODE_{index}" for index in range(11)]))

    def test_rejectsEmptyDecisionReasonCodes(self):
        self._assert_rejected(record(decisionReasonCodes=[]))

    def test_rejectsFreeTextDecisionReasonCode(self):
        self._assert_rejected(record(decisionReasonCodes=["Free text"]))

    def test_rejectsBadEvaluationRecordId(self):
        self._assert_rejected(record(evaluationRecordId="eval-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"))

    def test_rejectsBadTransactionReference(self):
        self._assert_rejected(record(transactionReference="txnref-bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"))

    def test_preservesMissingScoreAsNone(self):
        parsed = self._parse(record(fraudScore=None))

        self.assertIsNone(parsed.records[0].fraud_score)

    def test_preservesMissingRiskAsNone(self):
        parsed = self._parse(record(riskLevel=None))

        self.assertIsNone(parsed.records[0].risk_level)

    def test_preservesMissingAlertRecommendedAsNone(self):
        parsed = self._parse(record(alertRecommended=None))

        self.assertIsNone(parsed.records[0].alert_recommended)

    def _parse(self, payload):
        with jsonl_file(jsonl(payload)) as path:
            return read_fdp123_feedback_dataset_jsonl(path)

    def _assert_rejected(self, payload):
        with jsonl_file(jsonl(payload)) as path:
            with self.assertRaises(Fdp123DatasetValidationError):
                read_fdp123_feedback_dataset_jsonl(path)


if __name__ == "__main__":
    unittest.main()
