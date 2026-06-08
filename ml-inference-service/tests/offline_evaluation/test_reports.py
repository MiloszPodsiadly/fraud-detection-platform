import json
import unittest

from offline_evaluation.dataset_reader import read_fdp102_jsonl
from offline_evaluation.disagreement_report import build_disagreement_report
from offline_evaluation.evaluation_runner import build_evaluation_report, build_input_summary
from offline_evaluation.quality_metrics import build_quality_metrics, ranked_evaluation_record_ids
from offline_evaluation.report_writer import report_json
from fdp103_fixtures import GENERATED_AT, jsonl, record


class InputSummaryReportTest(unittest.TestCase):
    def test_inputSummaryCountsTotalLines(self):
        summary = self._summary(record(), record())

        self.assertEqual(3, summary["totalLinesRead"])

    def test_inputSummaryCountsDatasetRecords(self):
        summary = self._summary(record(), record())

        self.assertEqual(2, summary["datasetRecordsRead"])

    def test_inputSummaryCountsAcceptedRecords(self):
        summary = self._summary(record(), record(evaluationLabel="ANALYST_MARKED_LEGITIMATE"))

        self.assertEqual(2, summary["recordsAcceptedForEvaluation"])

    def test_inputSummaryCountsNotEvaluationEligibleExcluded(self):
        summary = self._summary(record(evaluationLabel="NOT_EVALUATION_ELIGIBLE"))

        self.assertEqual(1, summary["recordsExcludedNotEvaluationEligible"])

    def test_inputSummaryCountsMalformedExcluded(self):
        parsed = read_fdp102_jsonl(jsonl(record()))

        summary = build_input_summary(parsed, GENERATED_AT, malformed_excluded=1)

        self.assertEqual(1, summary["recordsExcludedAsMalformed"])

    def test_inputSummaryCountsMissingMl(self):
        summary = self._summary(record(mlRiskLevel=None, mlScoreBucket=None))

        self.assertEqual(1, summary["recordsWithMissingMlSignal"])

    def test_inputSummaryCountsMissingRules(self):
        summary = self._summary(record(rulesRiskLevel=None, rulesScoreBucket=None))

        self.assertEqual(1, summary["recordsWithMissingRulesSignal"])

    def test_inputSummaryCountsMissingProjection(self):
        summary = self._summary(record(projectionStatus="MISSING"))

        self.assertEqual(1, summary["recordsWithMissingProjection"])

    def test_inputSummaryUsesExportMetadata(self):
        summary = self._summary(record())

        self.assertEqual("FEEDBACK_SUBMITTED_AT", summary["exportMetadata"]["timeBasis"])

    def test_inputSummaryIsDeterministic(self):
        parsed = read_fdp102_jsonl(jsonl(record()))

        self.assertEqual(build_input_summary(parsed, GENERATED_AT), build_input_summary(parsed, GENERATED_AT))

    def _summary(self, *records):
        return build_input_summary(read_fdp102_jsonl(jsonl(*records)), GENERATED_AT)


class DisagreementReportTest(unittest.TestCase):
    def test_countsRulesHighMlHigh(self):
        self.assertEqual(1, self._report(record(rulesRiskLevel="HIGH", mlRiskLevel="HIGH"))["rulesHighMlHigh"])

    def test_countsRulesHighMlLowOrMedium(self):
        self.assertEqual(1, self._report(record(rulesRiskLevel="HIGH", mlRiskLevel="LOW"))["rulesHighMlLowOrMedium"])

    def test_countsRulesLowOrMediumMlHigh(self):
        self.assertEqual(1, self._report(record(rulesRiskLevel="LOW", mlRiskLevel="HIGH"))["rulesLowOrMediumMlHigh"])

    def test_countsRulesLowOrMediumMlLowOrMedium(self):
        self.assertEqual(1, self._report(record(rulesRiskLevel="MEDIUM", mlRiskLevel="LOW"))["rulesLowOrMediumMlLowOrMedium"])

    def test_countsRulesMissingMlPresent(self):
        self.assertEqual(1, self._report(record(rulesRiskLevel=None, rulesScoreBucket=None, mlRiskLevel="HIGH"))["rulesMissingMlPresent"])

    def test_countsMlMissingRulesPresent(self):
        self.assertEqual(1, self._report(record(mlRiskLevel=None, mlScoreBucket=None, rulesRiskLevel="HIGH"))["mlMissingRulesPresent"])

    def test_countsBothMissing(self):
        report = self._report(record(mlRiskLevel=None, mlScoreBucket=None, rulesRiskLevel=None, rulesScoreBucket=None))

        self.assertEqual(1, report["bothMissing"])

    def test_excludesNotEvaluationEligible(self):
        report = self._report(record(evaluationLabel="NOT_EVALUATION_ELIGIBLE", mlRiskLevel="HIGH", rulesRiskLevel="HIGH"))

        self.assertEqual(1, report["notEvaluationEligibleExcluded"])
        self.assertEqual(0, report["rulesHighMlHigh"])

    def test_missingIsNotLowRisk(self):
        report = self._report(record(rulesRiskLevel=None, rulesScoreBucket=None, mlRiskLevel="LOW"))

        self.assertEqual(1, report["rulesMissingMlPresent"])
        self.assertEqual(0, report["rulesLowOrMediumMlLowOrMedium"])

    def test_disagreementReportIsDeterministic(self):
        records = read_fdp102_jsonl(jsonl(record(), record(evaluationRecordId="eval-bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"))).records

        self.assertEqual(build_disagreement_report(records), build_disagreement_report(records))

    def _report(self, *records):
        return build_disagreement_report(read_fdp102_jsonl(jsonl(*records)).records)


class QualityMetricsTest(unittest.TestCase):
    def test_precisionAtBudgetCorrectOnFixture(self):
        metrics = self._metrics()

        self.assertEqual(0.5, metrics["precisionAtBudget"])

    def test_recallAtTopKCorrectOnFixture(self):
        metrics = self._metrics()

        self.assertEqual(0.5, metrics["recallAtTopK"])

    def test_falsePositiveRateCorrectOnFixture(self):
        metrics = self._metrics()

        self.assertEqual(0.5, metrics["falsePositiveRate"])

    def test_mlCaughtRulesMissedCorrectOnFixture(self):
        metrics = self._metrics()

        self.assertEqual(1, metrics["mlCaughtRulesMissedCount"])

    def test_rulesCaughtMlMissedCorrectOnFixture(self):
        metrics = self._metrics()

        self.assertEqual(1, metrics["rulesCaughtMlMissedCount"])

    def test_metricsExcludeNotEvaluationEligible(self):
        metrics = build_quality_metrics(read_fdp102_jsonl(jsonl(record(evaluationLabel="NOT_EVALUATION_ELIGIBLE"))).records, 1, 1)

        self.assertEqual(1, metrics["notEvaluationEligibleCount"])
        self.assertEqual(0.0, metrics["precisionAtBudget"])

    def test_notEvaluationEligibleNeverNegative(self):
        records = read_fdp102_jsonl(jsonl(record(evaluationLabel="NOT_EVALUATION_ELIGIBLE", mlRiskLevel="HIGH"))).records

        self.assertEqual(0.0, build_quality_metrics(records, 1, 1)["falsePositiveRate"])

    def test_missingMlExcludedFromMlRankingOrCountedSeparately(self):
        records = read_fdp102_jsonl(jsonl(record(mlRiskLevel=None, mlScoreBucket=None))).records

        self.assertEqual([], ranked_evaluation_record_ids(records))
        self.assertEqual(1, build_quality_metrics(records, 1, 1)["missingMlCount"])

    def test_missingRulesCountedSeparately(self):
        records = read_fdp102_jsonl(jsonl(record(rulesRiskLevel=None, rulesScoreBucket=None))).records

        self.assertEqual(1, build_quality_metrics(records, 1, 1)["missingRulesCount"])

    def test_deterministicTieBreakByEvaluationRecordId(self):
        records = read_fdp102_jsonl(jsonl(
            record(evaluationRecordId="eval-bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", mlRiskLevel="HIGH"),
            record(evaluationRecordId="eval-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", mlRiskLevel="HIGH"),
        )).records

        self.assertEqual([
            "eval-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            "eval-bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
        ], ranked_evaluation_record_ids(records))

    def test_invalidReviewBudgetRejected(self):
        with self.assertRaises(ValueError):
            build_quality_metrics(read_fdp102_jsonl(jsonl(record())).records, 0, 1)

    def test_invalidTopKRejected(self):
        with self.assertRaises(ValueError):
            build_quality_metrics(read_fdp102_jsonl(jsonl(record())).records, 1, 0)

    def _metrics(self):
        records = read_fdp102_jsonl(jsonl(
            record(evaluationRecordId="eval-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", evaluationLabel="ANALYST_CONFIRMED_FRAUD", mlRiskLevel="HIGH", rulesRiskLevel="LOW"),
            record(evaluationRecordId="eval-bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", evaluationLabel="ANALYST_MARKED_LEGITIMATE", mlRiskLevel="HIGH", rulesRiskLevel="LOW"),
            record(evaluationRecordId="eval-cccccccccccccccccccccccccccccccc", evaluationLabel="ANALYST_CONFIRMED_FRAUD", mlRiskLevel="LOW", rulesRiskLevel="HIGH"),
            record(evaluationRecordId="eval-dddddddddddddddddddddddddddddddd", evaluationLabel="ANALYST_MARKED_LEGITIMATE", mlRiskLevel="LOW", rulesRiskLevel="LOW"),
        )).records
        return build_quality_metrics(records, review_budget=2, top_k=2)


class ReportWriterTest(unittest.TestCase):
    def test_reportJsonIsDeterministic(self):
        report = self._report()

        self.assertEqual(report_json(report), report_json(report))

    def test_reportContainsInputSummary(self):
        self.assertIn("inputSummary", json.loads(report_json(self._report())))

    def test_reportContainsDisagreementSummary(self):
        self.assertIn("disagreementSummary", json.loads(report_json(self._report())))

    def test_reportContainsQualityMetrics(self):
        self.assertIn("qualityMetrics", json.loads(report_json(self._report())))

    def test_reportContainsExclusions(self):
        self.assertIn("exclusions", json.loads(report_json(self._report())))

    def test_reportWarningsAreBounded(self):
        report = self._report()
        report["warnings"] = [f"W{i}" for i in range(20)]

        self.assertLessEqual(len(json.loads(report_json(report))["warnings"]), 10)

    def test_reportDoesNotContainPerRecordIdentifiers(self):
        payload = report_json(self._report())

        self.assertNotIn("transactionReference", payload)
        self.assertNotIn("txnref-", payload)

    def test_reportDoesNotContainRawSensitiveFields(self):
        payload = report_json(self._report())

        for forbidden in ("customerId", "accountId", "cardId", "deviceId", "merchantId", "submittedBy", "token", "secret"):
            self.assertNotIn(forbidden, payload)

    def test_reportDoesNotContainGroundTruthTrainingLabelFinalDecision(self):
        payload = report_json(self._report())

        for forbidden in ("groundTruth", "modelTrainingLabel", "finalDecision", "paymentAuthorization"):
            self.assertNotIn(forbidden, payload)

    def test_reportDoesNotContainPromotionOrThresholdRecommendation(self):
        payload = report_json(self._report())

        self.assertNotIn("modelPromotion", payload)
        self.assertNotIn("thresholdRecommendation", payload)

    def _report(self):
        return build_evaluation_report(jsonl(record(projectionStatus="MISSING")), review_budget=1, top_k=1, generated_at=GENERATED_AT)


if __name__ == "__main__":
    unittest.main()
