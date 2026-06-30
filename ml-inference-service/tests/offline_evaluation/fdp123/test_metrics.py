import unittest

from offline_evaluation.fdp123.dataset_reader import read_fdp123_feedback_dataset_jsonl
from offline_evaluation.fdp123.metrics import build_fdp123_metrics
try:
    from fdp123.fdp123_fixtures import jsonl, jsonl_file, record
except ModuleNotFoundError:
    from fdp123_fixtures import jsonl, jsonl_file, record


class Fdp123MetricsTest(unittest.TestCase):
    def test_classBalanceCountsPositiveAndNegative(self):
        metrics = self._metrics(
            record(feedbackLabel="CONFIRMED_FRAUD", evaluationLabel="POSITIVE_FRAUD"),
            record(
                evaluationRecordId="eval_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                transactionReference="txnref_cccccccccccccccccccccccccccccccc",
                feedbackLabel="CONFIRMED_LEGITIMATE",
                evaluationLabel="NEGATIVE_LEGITIMATE",
            ),
        )

        self.assertEqual(1, metrics["classBalance"]["positiveClassCount"])
        self.assertEqual(1, metrics["classBalance"]["negativeClassCount"])

    def test_emptyDatasetWarningsAndUnavailableShares(self):
        metrics = self._metrics()

        self.assertIn("EMPTY_DATASET", metrics["warnings"])
        self.assertFalse(metrics["classBalance"]["positiveClassShare"]["available"])
        self.assertEqual("EMPTY_DATASET", metrics["classBalance"]["positiveClassShare"]["reason"])

    def test_singleClassDatasetWarning(self):
        metrics = self._metrics(record())

        self.assertIn("SINGLE_CLASS_DATASET", metrics["warnings"])

    def test_alertRecommendedConfusionMatrix(self):
        metrics = self._metrics(
            record(evaluationRecordId="eval_11111111111111111111111111111111", alertRecommended=True),
            record(
                evaluationRecordId="eval_22222222222222222222222222222222",
                transactionReference="txnref_22222222222222222222222222222222",
                feedbackLabel="CONFIRMED_LEGITIMATE",
                evaluationLabel="NEGATIVE_LEGITIMATE",
                alertRecommended=True,
            ),
            record(
                evaluationRecordId="eval_33333333333333333333333333333333",
                transactionReference="txnref_33333333333333333333333333333333",
                feedbackLabel="CONFIRMED_LEGITIMATE",
                evaluationLabel="NEGATIVE_LEGITIMATE",
                alertRecommended=False,
            ),
            record(
                evaluationRecordId="eval_44444444444444444444444444444444",
                transactionReference="txnref_44444444444444444444444444444444",
                alertRecommended=False,
            ),
        )

        matrix = metrics["alertRecommendedConfusionMatrix"]
        self.assertEqual(4, matrix["recordsWithSignal"])
        self.assertEqual(1, matrix["truePositive"])
        self.assertEqual(1, matrix["falsePositive"])
        self.assertEqual(1, matrix["trueNegative"])
        self.assertEqual(1, matrix["falseNegative"])
        self.assertEqual(0.5, matrix["precision"]["value"])
        self.assertEqual(0.5, matrix["recall"]["value"])
        self.assertEqual(0.5, matrix["falsePositiveRate"]["value"])
        self.assertEqual(0.5, matrix["falseNegativeRate"]["value"])

    def test_recordsWithSignalExcludesMissingAlertRecommended(self):
        metrics = self._metrics(record(alertRecommended=None), record(
            evaluationRecordId="eval_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
            transactionReference="txnref_cccccccccccccccccccccccccccccccc",
            alertRecommended=True,
        ))

        self.assertEqual(1, metrics["alertRecommendedConfusionMatrix"]["recordsWithSignal"])

    def test_missingAlertRecommendedCounted(self):
        metrics = self._metrics(record(alertRecommended=None))

        self.assertEqual(1, metrics["missingAlertRecommendedCount"])
        self.assertIn("MISSING_ALERT_RECOMMENDATION_VALUES", metrics["warnings"])

    def test_riskLevelBreakdownAndMissingRisk(self):
        metrics = self._metrics(record(riskLevel=None), record(
            evaluationRecordId="eval_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
            transactionReference="txnref_cccccccccccccccccccccccccccccccc",
            riskLevel="LOW",
        ))

        self.assertEqual(1, metrics["riskLevelBreakdown"]["MISSING"]["totalCount"])
        self.assertEqual(1, metrics["missingRiskLevelCount"])

    def test_fraudScoreBucketAnalysisAndMissingScore(self):
        metrics = self._metrics(record(fraudScore=None), record(
            evaluationRecordId="eval_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
            transactionReference="txnref_cccccccccccccccccccccccccccccccc",
            fraudScore=0.1,
        ))

        self.assertEqual(1, metrics["missingFraudScoreCount"])
        self.assertEqual(1, metrics["fraudScoreBucketAnalysis"][0]["recordCount"])
        self.assertTrue(metrics["fraudScoreBucketAnalysis"][0]["positiveClassShare"]["available"])
        self.assertFalse(metrics["fraudScoreBucketAnalysis"][1]["positiveClassShare"]["available"])
        self.assertEqual("NO_BUCKET_RECORDS", metrics["fraudScoreBucketAnalysis"][1]["positiveClassShare"]["reason"])

    def test_precisionAtKUsesActualK(self):
        metrics = self._metrics(record(fraudScore=0.9))

        self.assertEqual(1, metrics["precisionAtK"]["10"]["actualK"])
        self.assertEqual(1.0, metrics["precisionAtK"]["10"]["value"]["value"])

    def test_precisionAtKWithNoScoredRowsIsUnavailable(self):
        metrics = self._metrics(record(fraudScore=None))

        self.assertEqual(0, metrics["precisionAtK"]["10"]["actualK"])
        self.assertFalse(metrics["precisionAtK"]["10"]["value"]["available"])
        self.assertEqual("NO_SCORED_RECORDS", metrics["precisionAtK"]["10"]["value"]["reason"])

    def test_recallAtKWithNoPositiveIsUnavailable(self):
        metrics = self._metrics(record(
            feedbackLabel="CONFIRMED_LEGITIMATE",
            evaluationLabel="NEGATIVE_LEGITIMATE",
        ))

        self.assertFalse(metrics["recallAtK"]["10"]["value"]["available"])
        self.assertEqual("NO_POSITIVE_RECORDS", metrics["recallAtK"]["10"]["value"]["reason"])

    def test_alertPrecisionUnavailableWhenNoPredictedPositives(self):
        metrics = self._metrics(record(alertRecommended=False))

        precision = metrics["alertRecommendedConfusionMatrix"]["precision"]
        self.assertFalse(precision["available"])
        self.assertEqual("NO_PREDICTED_POSITIVES", precision["reason"])

    def test_alertRecallUnavailableWhenNoActualPositives(self):
        metrics = self._metrics(record(feedbackLabel="CONFIRMED_LEGITIMATE", evaluationLabel="NEGATIVE_LEGITIMATE"))

        recall = metrics["alertRecommendedConfusionMatrix"]["recall"]
        self.assertFalse(recall["available"])
        self.assertEqual("NO_ACTUAL_POSITIVES", recall["reason"])

    def test_falsePositiveRateUnavailableWhenNoActualNegatives(self):
        metrics = self._metrics(record())

        false_positive_rate = metrics["alertRecommendedConfusionMatrix"]["falsePositiveRate"]
        self.assertFalse(false_positive_rate["available"])
        self.assertEqual("NO_ACTUAL_NEGATIVES", false_positive_rate["reason"])

    def test_falseNegativeRateUnavailableWhenNoActualPositives(self):
        metrics = self._metrics(record(feedbackLabel="CONFIRMED_LEGITIMATE", evaluationLabel="NEGATIVE_LEGITIMATE"))

        false_negative_rate = metrics["alertRecommendedConfusionMatrix"]["falseNegativeRate"]
        self.assertFalse(false_negative_rate["available"])
        self.assertEqual("NO_ACTUAL_POSITIVES", false_negative_rate["reason"])

    def test_fixturesUseFdp123CompatibleReasonCodes(self):
        self.assertEqual(["CUSTOMER_CONFIRMED_FRAUD"], record()["decisionReasonCodes"])
        self.assertEqual(
            ["CUSTOMER_CONFIRMED_LEGITIMATE"],
            record(feedbackLabel="CONFIRMED_LEGITIMATE", evaluationLabel="NEGATIVE_LEGITIMATE")["decisionReasonCodes"],
        )

    def _metrics(self, *records):
        with jsonl_file(jsonl(*records)) as path:
            dataset = read_fdp123_feedback_dataset_jsonl(path)
        return build_fdp123_metrics(dataset)


if __name__ == "__main__":
    unittest.main()
