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

    def test_preservesEvaluationPopulationFromModelCard(self):
        model_card = self.model_card()
        population = self.summary()["evaluationPopulation"]
        metrics = model_card["metricsSummary"]

        self.assertEqual(metrics["datasetRecordsRead"], population["datasetRecordsRead"])
        self.assertEqual(metrics["recordsAcceptedForEvaluation"], population["recordsAcceptedForEvaluation"])
        self.assertEqual(
            metrics["recordsExcludedNotEvaluationEligible"],
            population["recordsExcludedNotEvaluationEligible"],
        )

    def test_highPrecisionStillShowsSampleSize(self):
        summary = build_shadow_performance_summary(self.small_excellent_model_card(), GENERATED_AT)
        payload = write_shadow_performance_summary(summary)

        self.assertEqual(
            {
                "datasetRecordsRead": 3,
                "recordsAcceptedForEvaluation": 2,
                "recordsExcludedNotEvaluationEligible": 1,
            },
            summary["evaluationPopulation"],
        )
        self.assertNoApprovalRecommendationOrRuntimeTerms(payload)

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

    def test_summaryAlwaysContainsNonApprovalBanner(self):
        payload = write_shadow_performance_summary(self.summary())

        self.assertIn(BANNER, payload)
        self.assertIn("offline diagnostics only", BANNER)
        self.assertIn("not model promotion approval", BANNER)
        self.assertIn("not threshold recommendation", BANNER)
        self.assertIn("not production decisioning approval", BANNER)
        self.assertIn("not payment authorization", BANNER)
        self.assertIn("not automatic approve / decline / block", BANNER)
        self.assertIn("not analyst recommendation logic", BANNER)

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

        self.assertNoApprovalRecommendationOrRuntimeTerms(payload)

    def test_excellentMetricsDoNotCreatePromotionApproval(self):
        summary = build_shadow_performance_summary(self.small_excellent_model_card(), GENERATED_AT)
        payload = write_shadow_performance_summary(summary)

        self.assertEqual(1.0, summary["metrics"]["precisionAtBudget"])
        self.assertEqual(1.0, summary["metrics"]["recallAtTopK"])
        self.assertEqual(0.0, summary["metrics"]["falsePositiveRate"])
        self.assertNoApprovalRecommendationOrRuntimeTerms(payload)

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

    def test_rejectsSummaryWithoutEvaluationPopulation(self):
        summary = self.summary()
        summary.pop("evaluationPopulation")

        with self.assertRaises(ShadowPerformanceValidationError):
            write_shadow_performance_summary(summary)

    def test_rejectsMissingDatasetRecordsRead(self):
        summary = self.summary()
        summary["evaluationPopulation"].pop("datasetRecordsRead")

        with self.assertRaises(ShadowPerformanceValidationError):
            write_shadow_performance_summary(summary)

    def test_rejectsAcceptedRecordsGreaterThanDatasetRecordsRead(self):
        summary = self.summary()
        summary["evaluationPopulation"]["datasetRecordsRead"] = 2
        summary["evaluationPopulation"]["recordsAcceptedForEvaluation"] = 3

        with self.assertRaises(ShadowPerformanceValidationError):
            write_shadow_performance_summary(summary)

    def test_rejectsExcludedRecordsGreaterThanDatasetRecordsRead(self):
        summary = self.summary()
        summary["evaluationPopulation"]["datasetRecordsRead"] = 2
        summary["evaluationPopulation"]["recordsExcludedNotEvaluationEligible"] = 3

        with self.assertRaises(ShadowPerformanceValidationError):
            write_shadow_performance_summary(summary)

    def test_rejectsPopulationTotalGreaterThanDatasetRecordsRead(self):
        summary = self.summary()
        summary["evaluationPopulation"]["datasetRecordsRead"] = 3
        summary["evaluationPopulation"]["recordsAcceptedForEvaluation"] = 3
        summary["evaluationPopulation"]["recordsExcludedNotEvaluationEligible"] = 1

        with self.assertRaises(ShadowPerformanceValidationError):
            write_shadow_performance_summary(summary)

    def test_rejectsNotEvaluationEligibleMismatch(self):
        summary = self.summary()
        summary["metrics"]["notEvaluationEligibleCount"] = 0

        with self.assertRaises(ShadowPerformanceValidationError):
            write_shadow_performance_summary(summary)

    def test_rejectsMetricCountGreaterThanDatasetRecordsRead(self):
        summary = self.summary()
        summary["evaluationPopulation"]["datasetRecordsRead"] = 2
        summary["metrics"]["missingMlCount"] = 3

        with self.assertRaises(ShadowPerformanceValidationError):
            write_shadow_performance_summary(summary)

    def test_rejectsDisagreementTotalGreaterThanDatasetRecordsRead(self):
        summary = self.summary()
        summary["evaluationPopulation"]["datasetRecordsRead"] = 2
        summary["disagreementSummary"]["rulesHighMlHigh"] = 3

        with self.assertRaises(ShadowPerformanceValidationError):
            write_shadow_performance_summary(summary)

    def test_rejectsStringPopulationCount(self):
        summary = self.summary()
        summary["evaluationPopulation"]["datasetRecordsRead"] = "5"

        with self.assertRaises(ShadowPerformanceValidationError):
            write_shadow_performance_summary(summary)

    def test_rejectsBoolPopulationCount(self):
        summary = self.summary()
        summary["evaluationPopulation"]["datasetRecordsRead"] = True

        with self.assertRaises(ShadowPerformanceValidationError):
            write_shadow_performance_summary(summary)

    def test_rejectsNegativePopulationCount(self):
        summary = self.summary()
        summary["evaluationPopulation"]["datasetRecordsRead"] = -1

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

    def test_rejectsModelNameUrl(self):
        summary = self.summary()
        summary["model"]["modelName"] = "https://models.example/model"

        with self.assertRaises(ShadowPerformanceValidationError):
            write_shadow_performance_summary(summary)

    def test_rejectsModelNameRegistryUrl(self):
        summary = self.summary()
        summary["model"]["modelName"] = "registry.example/model"

        with self.assertRaises(ShadowPerformanceValidationError):
            write_shadow_performance_summary(summary)

    def test_rejectsModelVersionPath(self):
        summary = self.summary()
        summary["model"]["modelVersion"] = "models/python-logistic"

        with self.assertRaises(ShadowPerformanceValidationError):
            write_shadow_performance_summary(summary)

    def test_rejectsFeatureContractPathTraversal(self):
        summary = self.summary()
        summary["model"]["featureContractVersion"] = "../contract"

        with self.assertRaises(ShadowPerformanceValidationError):
            write_shadow_performance_summary(summary)

    def test_rejectsEmailLikeModelName(self):
        summary = self.summary()
        summary["model"]["modelName"] = "owner@example.com"

        with self.assertRaises(ShadowPerformanceValidationError):
            write_shadow_performance_summary(summary)

    def test_acceptsSafeModelIdentifiers(self):
        summary = self.summary()
        summary["model"] = {
            "modelName": "python-logistic-fraud-model",
            "modelVersion": "2026-04-21.trained.v1",
            "modelFamily": "LOGISTIC_REGRESSION",
            "featureContractVersion": "2026-04-22.v1",
        }

        self.assertEqual(summary, json.loads(write_shadow_performance_summary(summary)))

    def test_positiveApprovalFieldsRejectedEvenWithNegatedFieldsPresent(self):
        for field in ("productionApproved", "promotionApproved", "thresholdRecommendation", "paymentAuthorization"):
            with self.subTest(field=field):
                summary = self.summary()
                summary["governance"]["notProductionApproval"] = True
                summary["governance"]["notPromotionApproval"] = True
                summary["governance"][field] = False

                with self.assertRaises(ShadowPerformanceValidationError):
                    write_shadow_performance_summary(summary)

    def test_positiveApprovalTermsRejectedInWarningsAndLimitations(self):
        for field in ("warnings", "limitations"):
            for value in ("PRODUCTION_APPROVED", "PROMOTION_READY", "THRESHOLD_RECOMMENDATION", "PAYMENT_AUTHORIZATION"):
                with self.subTest(field=field, value=value):
                    summary = self.summary()
                    summary[field] = [value]

                    with self.assertRaises(ShadowPerformanceValidationError):
                        write_shadow_performance_summary(summary)

    def model_card(self):
        return load_json("model_card", "expected_model_card_v1.json")

    def expected_summary(self):
        return load_json("shadow_performance", "expected_shadow_performance_summary_v1.json")

    def summary(self):
        return build_shadow_performance_summary(self.model_card(), GENERATED_AT)

    def small_excellent_model_card(self):
        card = self.model_card()
        metrics = card["metricsSummary"]
        metrics.update({
            "datasetRecordsRead": 3,
            "recordsAcceptedForEvaluation": 2,
            "recordsExcludedNotEvaluationEligible": 1,
            "missingMlCount": 0,
            "missingRulesCount": 0,
            "missingProjectionCount": 0,
            "notEvaluationEligibleCount": 1,
            "precisionAtBudget": 1.0,
            "recallAtTopK": 1.0,
            "falsePositiveRate": 0.0,
            "mlCaughtRulesMissedCount": 2,
            "rulesCaughtMlMissedCount": 0,
            "disagreementSummary": {
                "rulesHighMlHigh": 2,
                "rulesHighMlLowOrMedium": 0,
                "rulesLowOrMediumMlHigh": 0,
                "rulesLowOrMediumMlLowOrMedium": 0,
                "rulesMissingMlPresent": 0,
                "mlMissingRulesPresent": 0,
                "bothMissing": 0,
                "notEvaluationEligibleExcluded": 1,
            },
        })
        return card

    def assertNoApprovalRecommendationOrRuntimeTerms(self, payload):
        for term in (
            "productionApproved",
            "promotionApproved",
            "promotionReady",
            "championCandidate",
            "deployRecommendation",
            "thresholdRecommendation",
            "recommendedThreshold",
            "finalDecision",
            "paymentAuthorization",
            "analystRecommendation",
        ):
            self.assertNotIn(term, payload)


def load_json(*path_parts):
    return json.loads((FIXTURE_ROOT / Path(*path_parts)).read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
