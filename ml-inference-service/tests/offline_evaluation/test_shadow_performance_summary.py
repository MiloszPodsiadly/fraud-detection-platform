import json
import unittest

from offline_evaluation.shadow_performance_schema import (
    BANNER,
    REPORT_TYPE,
    SUMMARY_VERSION,
    ShadowPerformanceValidationError,
)
from offline_evaluation.shadow_performance_summary import build_shadow_performance_summary
from offline_evaluation.shadow_performance_writer import write_shadow_performance_summary
from fdp123.evaluation_card.test_schema import valid_evaluation_card, valid_evaluation_evidence, valid_metrics


GENERATED_AT = "2026-06-13T02:00:00Z"
CARD_MANIFEST_SHA256 = "b" * 64


class ShadowPerformanceSummaryTest(unittest.TestCase):
    def test_mapsValidatedEvaluationCardToShadowPerformanceSummaryV2(self):
        summary = self.summary()

        self.assertEqual(REPORT_TYPE, summary["reportType"])
        self.assertEqual(SUMMARY_VERSION, summary["summaryVersion"])
        self.assertEqual("PLATFORM_RECOMMENDATION", summary["evaluationSubject"]["subjectType"])
        self.assertEqual("2026-06-10T00:00:00Z", summary["evaluation"]["evaluationReportGeneratedAt"])
        self.assertEqual("2026-06-12T00:00:00Z", summary["evaluation"]["evaluationCardGeneratedAt"])
        self.assertEqual(CARD_MANIFEST_SHA256, summary["evaluation"]["sourceEvaluationCardManifestSha256"])
        self.assertNotIn("model", summary)
        self.assertNotIn("summaryType", summary)

    def test_preservesMetricObjectsWithoutZeroFallback(self):
        card = self.evaluation_card()
        card["metricsSummary"]["alertRecommendedPrecision"] = {
            "available": False,
            "value": None,
            "reason": "NO_PREDICTED_POSITIVES",
        }
        card["metricsSummary"]["falsePositiveRate"] = {"available": True, "value": 0.0, "reason": None}

        metrics = build_shadow_performance_summary(
            card,
            GENERATED_AT,
            source_evaluation_card_manifest_sha256=CARD_MANIFEST_SHA256,
        )["metrics"]

        self.assertEqual(
            {"available": False, "value": None, "reason": "NO_PREDICTED_POSITIVES"},
            metrics["alertRecommendedPrecision"],
        )
        self.assertEqual({"available": True, "value": 0.0, "reason": None}, metrics["falsePositiveRate"])

    def test_doesNotUseBudgetOrTopKMetricAliases(self):
        payload = write_shadow_performance_summary(self.summary())

        self.assertIn("alertRecommendedPrecision", payload)
        self.assertIn("alertRecommendedRecall", payload)
        self.assertNotIn("precisionAtBudget", payload)
        self.assertNotIn("recallAtTopK", payload)

    def test_doesNotEmitFabricatedLegacyCounters(self):
        payload = write_shadow_performance_summary(self.summary())

        for term in (
            "recordsExcludedNotEvaluationEligible",
            "mlCaughtRulesMissedCount",
            "rulesCaughtMlMissedCount",
            "missingMlCount",
            "missingRulesCount",
            "missingProjectionCount",
            "notEvaluationEligibleCount",
            "disagreementSummary",
        ):
            self.assertNotIn(term, payload)

    def test_preservesEvaluationPopulationFromEvaluationCard(self):
        summary = self.summary()

        self.assertEqual(
            {"recordsEvaluated": 2, "positiveClassCount": 1, "negativeClassCount": 1},
            summary["evaluationPopulation"],
        )

    def test_includesDiagnosticGovernanceAndRequiredNonGoals(self):
        governance = self.summary()["governance"]

        self.assertEqual("DIAGNOSTIC_ONLY", governance["governanceStatus"])
        self.assertTrue(governance["diagnosticOnly"])
        self.assertTrue(governance["notProductionApproval"])
        self.assertTrue(governance["notPromotionApproval"])
        self.assertTrue(governance["notThresholdRecommendation"])
        self.assertTrue(governance["notPaymentAuthorization"])
        self.assertTrue(governance["notAutomaticDecisioning"])
        self.assertNotIn("approvedFor", governance)

    def test_includesRequiredOfflineDiagnosticsBanner(self):
        self.assertEqual(BANNER, self.summary()["banner"])
        self.assertIn("offline diagnostics only", self.summary()["banner"])

    def test_outputIsDeterministicAndCompactJson(self):
        first = write_shadow_performance_summary(self.summary())
        second = write_shadow_performance_summary(self.summary())

        self.assertEqual(first, second)
        self.assertEqual(json.loads(first), self.summary())
        self.assertTrue(first.endswith("\n"))
        self.assertNotIn("\n ", first)

    def test_rejectsLegacyV1ModelObject(self):
        summary = self.summary()
        summary["model"] = {
            "modelName": "NOT_AVAILABLE",
            "modelVersion": "NOT_AVAILABLE",
            "modelFamily": "PLATFORM_RECOMMENDATION",
        }

        with self.assertRaises(ShadowPerformanceValidationError):
            write_shadow_performance_summary(summary)

    def test_rejectsFlattenedMetric(self):
        summary = self.summary()
        summary["metrics"]["alertRecommendedPrecision"] = 0.5

        with self.assertRaises(ShadowPerformanceValidationError):
            write_shadow_performance_summary(summary)

    def test_rejectsUnavailableMetricWithNumericValue(self):
        summary = self.summary()
        summary["metrics"]["alertRecommendedRecall"] = {
            "available": False,
            "value": 0.0,
            "reason": "NO_ACTUAL_POSITIVES",
        }

        with self.assertRaises(ShadowPerformanceValidationError):
            write_shadow_performance_summary(summary)

    def test_rejectsAvailableMetricWithReason(self):
        summary = self.summary()
        summary["metrics"]["falseNegativeRate"] = {
            "available": True,
            "value": 0.0,
            "reason": "NO_ACTUAL_POSITIVES",
        }

        with self.assertRaises(ShadowPerformanceValidationError):
            write_shadow_performance_summary(summary)

    def test_rejectsUnsupportedMetricName(self):
        summary = self.summary()
        summary["metrics"]["precisionAtBudget"] = {"available": True, "value": 0.5, "reason": None}

        with self.assertRaises(ShadowPerformanceValidationError):
            write_shadow_performance_summary(summary)

    def test_rejectsFdp103LineageLabelledAsCurrent(self):
        summary = self.summary()
        summary["evaluation"]["evaluationReportType"] = "PYTHON_ML_EVALUATION_FOUNDATION"

        with self.assertRaises(ShadowPerformanceValidationError):
            write_shadow_performance_summary(summary)

    def test_rejectsWrongPopulationArithmetic(self):
        summary = self.summary()
        summary["evaluationPopulation"]["negativeClassCount"] = 99

        with self.assertRaises(ShadowPerformanceValidationError):
            write_shadow_performance_summary(summary)

    def test_rejectsRawIdentifiersOrSourcePayloads(self):
        summary = self.summary()
        summary["rawPayload"] = {"transactionReference": "txnref-unsafe"}

        with self.assertRaises(ShadowPerformanceValidationError):
            write_shadow_performance_summary(summary)

    def evaluation_card(self):
        return valid_evaluation_card()

    def summary(self):
        return build_shadow_performance_summary(
            self.evaluation_card(),
            GENERATED_AT,
            source_evaluation_card_manifest_sha256=CARD_MANIFEST_SHA256,
        )

    def small_excellent_evaluation_card(self):
        card = self.evaluation_card()
        card["evaluationEvidence"] = valid_evaluation_evidence(recordsEvaluated=3, positiveClassCount=2, negativeClassCount=1)
        card["metricsSummary"] = valid_metrics(
            alertRecommendedPrecision={"available": True, "value": 1.0, "reason": None},
            alertRecommendedRecall={"available": True, "value": 1.0, "reason": None},
            falsePositiveRate={"available": True, "value": 0.0, "reason": None},
            falseNegativeRate={"available": True, "value": 0.0, "reason": None},
        )
        return card


if __name__ == "__main__":
    unittest.main()
