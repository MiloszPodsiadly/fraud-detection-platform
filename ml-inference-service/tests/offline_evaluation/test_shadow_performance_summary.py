import json
import unittest
from pathlib import Path

from offline_evaluation.shadow_performance_schema import BANNER, ShadowPerformanceValidationError
from offline_evaluation.shadow_performance_summary import build_shadow_performance_summary
from offline_evaluation.shadow_performance_writer import write_shadow_performance_summary


GENERATED_AT = "2026-06-08T02:00:00Z"
FIXTURE_ROOT = Path(__file__).resolve().parent / "fixtures"


class ShadowPerformanceSummaryTest(unittest.TestCase):
    def test_mapsValidatedModelCardToShadowPerformanceSummary(self):
        self.assertEqual(self.expected_summary(), self.summary())

    def test_sourceOfTruthIsValidatedModelCardV1(self):
        card = self.model_card()
        card["metricsSummary"]["precisionAtBudget"] = 0.75

        self.assertEqual(0.75, build_shadow_performance_summary(card, GENERATED_AT)["metrics"]["precisionAtBudget"])

    def test_preservesModelIdentityFromModelCard(self):
        summary = self.summary()

        self.assertEqual("python-logistic-fraud-model", summary["model"]["modelName"])
        self.assertEqual("2026-04-21.trained.v1", summary["model"]["modelVersion"])
        self.assertEqual("LOGISTIC_REGRESSION", summary["model"]["modelFamily"])
        self.assertEqual("2026-04-22.v1", summary["model"]["featureContractVersion"])

    def test_preservesEvaluationContextFromModelCard(self):
        summary = self.summary()

        self.assertEqual("PYTHON_ML_EVALUATION_FOUNDATION", summary["evaluation"]["evaluationReportType"])
        self.assertEqual("FDP-103", summary["evaluation"]["evaluationReportVersion"])
        self.assertEqual("bucket_ordered_offline_diagnostic", summary["evaluation"]["metricBasis"])
        self.assertEqual("FEEDBACK_SUBMITTED_AT", summary["evaluation"]["datasetTimeBasis"])
        self.assertEqual(
            "TRANSACTION_REFERENCE_NEWEST_SUBMITTED_AT_FEEDBACK_ID_ASC",
            summary["evaluation"]["datasetDeduplicationPolicy"],
        )

    def test_includesDiagnosticGovernanceAndRequiredNonGoals(self):
        governance = self.summary()["governance"]

        self.assertEqual("DIAGNOSTIC_ONLY", governance["governanceStatus"])
        self.assertEqual(["COMPARE", "SHADOW"], governance["approvedFor"])
        self.assertTrue(governance["diagnosticOnly"])
        self.assertTrue(governance["notProductionApproval"])
        self.assertTrue(governance["notPromotionApproval"])
        self.assertTrue(governance["notThresholdRecommendation"])
        self.assertTrue(governance["notPaymentAuthorization"])
        self.assertTrue(governance["notAutomaticDecisioning"])

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

    def test_writerDoesNotEmitRawIdentifiersOrSourcePayloads(self):
        payload = write_shadow_performance_summary(self.summary())

        for term in (
            "evaluationRecordId",
            "transactionReference",
            "eval-",
            "txnref-",
            "rawPayload",
            "rawFeatureVector",
            "rawEvaluationReport",
            "rawModelCard",
            "perRecordExamples",
        ):
            self.assertNotIn(term, payload)

    def test_doesNotInventApprovalRecommendationOrRuntimeFields(self):
        payload = write_shadow_performance_summary(self.summary())

        for term in (
            "productionApproved",
            "promotionApproved",
            "promotionReady",
            "thresholdRecommendation",
            "recommendedThreshold",
            "deployRecommendation",
            "finalDecision",
            "paymentAuthorization",
            "analystRecommendation",
        ):
            self.assertNotIn(term, payload)

    def test_rejectsUnvalidatedModelCardVersion(self):
        card = self.model_card()
        card["modelCardVersion"] = "2.0"

        with self.assertRaises(ShadowPerformanceValidationError):
            build_shadow_performance_summary(card, GENERATED_AT)

    def test_rejectsUnsupportedApprovedForValue(self):
        card = self.model_card()
        card["approvedFor"] = ["SHADOW", "PRODUCTION_DECISIONING"]

        with self.assertRaises(ShadowPerformanceValidationError):
            build_shadow_performance_summary(card, GENERATED_AT)

    def test_rejectsUnknownSummaryField(self):
        summary = self.summary()
        summary["rawModelCard"] = self.model_card()

        with self.assertRaises(ShadowPerformanceValidationError):
            write_shadow_performance_summary(summary)

    def test_rejectsUnsafeFreeTextWarning(self):
        summary = self.summary()
        summary["warnings"] = ["PAYMENT_AUTHORIZATION"]

        with self.assertRaises(ShadowPerformanceValidationError):
            write_shadow_performance_summary(summary)

    def test_rejectsOutOfRangeRates(self):
        summary = self.summary()
        summary["metrics"]["precisionAtBudget"] = 1.01

        with self.assertRaises(ShadowPerformanceValidationError):
            write_shadow_performance_summary(summary)

    def model_card(self):
        return load_json("model_card", "expected_model_card_v1.json")

    def expected_summary(self):
        return load_json("shadow_performance", "expected_shadow_performance_summary_v1.json")

    def summary(self):
        return build_shadow_performance_summary(self.model_card(), GENERATED_AT)


def load_json(*path_parts):
    return json.loads((FIXTURE_ROOT / Path(*path_parts)).read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
