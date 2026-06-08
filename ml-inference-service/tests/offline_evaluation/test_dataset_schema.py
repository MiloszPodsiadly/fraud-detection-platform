import unittest

from offline_evaluation.dataset_reader import read_fdp102_jsonl
from offline_evaluation.dataset_schema import DatasetValidationError, evaluation_label_value
from fdp103_fixtures import jsonl, record


class DatasetSchemaValidationTest(unittest.TestCase):
    def test_analystConfirmedFraudIsEvaluationPositive(self):
        parsed = read_fdp102_jsonl(jsonl(record(evaluationLabel="ANALYST_CONFIRMED_FRAUD")))

        self.assertEqual(1, evaluation_label_value(parsed.records[0]))
        self.assertTrue(parsed.records[0].is_evaluation_positive)

    def test_analystMarkedLegitimateIsEvaluationNegative(self):
        parsed = read_fdp102_jsonl(jsonl(record(evaluationLabel="ANALYST_MARKED_LEGITIMATE")))

        self.assertEqual(0, evaluation_label_value(parsed.records[0]))
        self.assertTrue(parsed.records[0].is_evaluation_negative)

    def test_notEvaluationEligibleIsExcluded(self):
        parsed = read_fdp102_jsonl(jsonl(record(evaluationLabel="NOT_EVALUATION_ELIGIBLE")))

        self.assertFalse(parsed.records[0].is_evaluation_eligible)
        self.assertEqual((), parsed.evaluation_records)

    def test_notEvaluationEligibleNeverNegative(self):
        parsed = read_fdp102_jsonl(jsonl(record(evaluationLabel="NOT_EVALUATION_ELIGIBLE")))

        self.assertIsNone(evaluation_label_value(parsed.records[0]))
        self.assertFalse(parsed.records[0].is_evaluation_negative)

    def test_rejectsUnknownLabel(self):
        with self.assertRaises(DatasetValidationError):
            read_fdp102_jsonl(jsonl(record(evaluationLabel="CONFIRMED_FRAUD")))

    def test_rejectsGroundTruthField(self):
        with self.assertRaises(DatasetValidationError):
            read_fdp102_jsonl(jsonl(record(groundTruth=True)))

    def test_rejectsModelTrainingLabelField(self):
        with self.assertRaises(DatasetValidationError):
            read_fdp102_jsonl(jsonl(record(modelTrainingLabel=1)))

    def test_rejectsFinalDecisionField(self):
        with self.assertRaises(DatasetValidationError):
            read_fdp102_jsonl(jsonl(record(finalDecision="APPROVE")))

    def test_missingMlScoreDoesNotBecomeZero(self):
        parsed = read_fdp102_jsonl(jsonl(record(mlScoreBucket=None, mlRiskLevel="HIGH")))

        self.assertIsNone(parsed.records[0].ml_score_bucket)

    def test_missingMlRiskDoesNotBecomeLow(self):
        parsed = read_fdp102_jsonl(jsonl(record(mlRiskLevel=None, mlScoreBucket="HIGH")))

        self.assertIsNone(parsed.records[0].ml_risk_level)

    def test_missingRulesScoreDoesNotBecomeZero(self):
        parsed = read_fdp102_jsonl(jsonl(record(rulesScoreBucket=None, rulesRiskLevel="HIGH")))

        self.assertIsNone(parsed.records[0].rules_score_bucket)

    def test_missingRulesRiskDoesNotBecomeLow(self):
        parsed = read_fdp102_jsonl(jsonl(record(rulesRiskLevel=None, rulesScoreBucket="HIGH")))

        self.assertIsNone(parsed.records[0].rules_risk_level)

    def test_missingProjectionRemainsExplicit(self):
        parsed = read_fdp102_jsonl(jsonl(record(projectionStatus="MISSING")))

        self.assertTrue(parsed.records[0].projection_missing)

    def test_rejectsRawSensitiveFields(self):
        with self.assertRaises(DatasetValidationError):
            read_fdp102_jsonl(jsonl(record(customerId="cust-1")))


if __name__ == "__main__":
    unittest.main()
