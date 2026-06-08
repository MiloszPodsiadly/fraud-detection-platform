import json
import unittest
from pathlib import Path

from offline_evaluation.model_card_generator import REQUIRED_LIMITATIONS, build_model_card
from offline_evaluation.model_card_schema import ModelCardValidationError


GENERATED_AT = "2026-06-08T01:00:00Z"
FIXTURE_ROOT = Path(__file__).resolve().parent / "fixtures" / "model_card"
ALLOWED_METRICS = {
    "metricBasis",
    "diagnosticOnly",
    "datasetRecordsRead",
    "recordsAcceptedForEvaluation",
    "recordsExcludedNotEvaluationEligible",
    "missingMlCount",
    "missingRulesCount",
    "missingProjectionCount",
    "notEvaluationEligibleCount",
    "precisionAtBudget",
    "recallAtTopK",
    "falsePositiveRate",
    "mlCaughtRulesMissedCount",
    "rulesCaughtMlMissedCount",
    "disagreementSummary",
}


class ModelCardGeneratorTest(unittest.TestCase):
    def test_generatesModelCardFromFdp103Report(self):
        card = self.model_card()

        self.assertEqual("OFFLINE_MODEL_CARD_V1", card["cardType"])
        self.assertEqual("DIAGNOSTIC_ONLY", card["governanceStatus"])

    def test_generatedModelCardIsDeterministic(self):
        self.assertEqual(self.model_card(), self.model_card())

    def test_generatedModelCardIncludesModelIdentity(self):
        card = self.model_card()

        self.assertEqual("python-logistic-fraud-model", card["modelName"])
        self.assertEqual("2026-04-21.trained.v1", card["modelVersion"])
        self.assertEqual("LOGISTIC_REGRESSION", card["modelFamily"])
        self.assertEqual("2026-04-22.v1", card["featureContractVersion"])

    def test_generatedModelCardIncludesEvaluationReportReference(self):
        card = self.model_card()

        self.assertEqual("PYTHON_ML_EVALUATION_FOUNDATION", card["evaluationReportType"])
        self.assertEqual("FDP-103", card["evaluationReportVersion"])
        self.assertEqual("2026-06-08T00:00:00Z", card["evaluationReportGeneratedAt"])

    def test_generatedModelCardIncludesDatasetTimeBasis(self):
        self.assertEqual("FEEDBACK_SUBMITTED_AT", self.model_card()["datasetTimeBasis"])

    def test_generatedModelCardIncludesDatasetDeduplicationPolicy(self):
        self.assertEqual(
            "TRANSACTION_REFERENCE_NEWEST_SUBMITTED_AT_FEEDBACK_ID_ASC",
            self.model_card()["datasetDeduplicationPolicy"],
        )

    def test_generatedModelCardIncludesOfflineMetrics(self):
        metrics = self.model_card()["metricsSummary"]

        self.assertTrue(metrics["diagnosticOnly"])
        self.assertEqual(0.666667, metrics["precisionAtBudget"])
        self.assertEqual(0.5, metrics["recallAtTopK"])
        self.assertEqual(0.25, metrics["falsePositiveRate"])

    def test_rejectsUnknownQualityMetricField(self):
        report = self.report()
        report["qualityMetrics"]["unexpectedAuc"] = 0.99

        with self.assertRaises(ModelCardValidationError):
            build_model_card(report, self.metadata(), GENERATED_AT)

    def test_doesNotCopyUnexpectedInputSummaryFields(self):
        report = self.report()
        report["inputSummary"]["rawDatasetRows"] = [{"unsafe": True}]
        card = build_model_card(report, self.metadata(), GENERATED_AT)

        self.assertEqual(ALLOWED_METRICS, set(card["metricsSummary"]))
        self.assertNotIn("rawDatasetRows", json.dumps(card))

    def test_preservesMetricBasis(self):
        self.assertEqual("bucket_ordered_offline_diagnostic", self.model_card()["metricsSummary"]["metricBasis"])

    def test_preservesMissingMlRulesProjectionCounts(self):
        metrics = self.model_card()["metricsSummary"]

        self.assertEqual(1, metrics["missingMlCount"])
        self.assertEqual(1, metrics["missingRulesCount"])
        self.assertEqual(1, metrics["missingProjectionCount"])

    def test_preservesNotEvaluationEligibleCount(self):
        self.assertEqual(1, self.model_card()["metricsSummary"]["notEvaluationEligibleCount"])

    def test_includesRequiredLimitations(self):
        self.assertTrue(set(REQUIRED_LIMITATIONS).issubset(set(self.model_card()["limitations"])))

    def test_includesApprovedForShadowCompareOnly(self):
        self.assertEqual(["COMPARE", "SHADOW"], self.model_card()["approvedFor"])

    def test_rejectsOfflineEvaluationApprovedFor(self):
        metadata = self.metadata()
        metadata["approvedFor"] = ["OFFLINE_EVALUATION"]

        with self.assertRaises(ModelCardValidationError):
            build_model_card(self.report(), metadata, GENERATED_AT)

    def test_doesNotCopyRawReport(self):
        report = self.report()
        report["rawEvaluationReport"] = {"payload": "do-not-copy"}
        card = build_model_card(report, self.metadata(), GENERATED_AT)

        self.assertNotIn("rawEvaluationReport", json.dumps(card))
        self.assertNotIn("do-not-copy", json.dumps(card))

    def test_doesNotCopyPseudonymousIds(self):
        report = self.report()
        report["evaluationRecordId"] = "eval-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        report["transactionReference"] = "txnref-bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        payload = json.dumps(build_model_card(report, self.metadata(), GENERATED_AT))

        self.assertNotIn("evaluationRecordId", payload)
        self.assertNotIn("transactionReference", payload)
        self.assertNotIn("eval-", payload)
        self.assertNotIn("txnref-", payload)

    def test_doesNotInventPromotionApproval(self):
        self.assertNotIn("promotionApproved", json.dumps(self.model_card()))

    def test_doesNotInventThresholdRecommendation(self):
        self.assertNotIn("thresholdRecommendation", json.dumps(self.model_card()))

    def test_doesNotInventProductionDecisioningApproval(self):
        self.assertNotIn("productionApproved", json.dumps(self.model_card()))

    def test_metricsDoNotCreatePromotionApproval(self):
        card = build_model_card(self.excellent_report(), self.metadata(), GENERATED_AT)

        self.assertNotIn("promotionApproved", json.dumps(card))

    def test_metricsDoNotCreateThresholdRecommendation(self):
        card = build_model_card(self.excellent_report(), self.metadata(), GENERATED_AT)

        self.assertNotIn("thresholdRecommendation", json.dumps(card))

    def test_highPrecisionDoesNotCreateProductionApproval(self):
        card = build_model_card(self.excellent_report(), self.metadata(), GENERATED_AT)

        self.assertEqual(1.0, card["metricsSummary"]["precisionAtBudget"])
        self.assertNotIn("productionApproved", json.dumps(card))

    def test_rejectsUnknownModelMetadataFields(self):
        metadata = self.metadata()
        metadata["metadata"] = {"owner": "unsafe"}

        with self.assertRaises(ModelCardValidationError):
            build_model_card(self.report(), metadata, GENERATED_AT)

    def test_rejectsUnsupportedEvaluationReportType(self):
        report = self.report()
        report["reportType"] = "OTHER_REPORT"

        with self.assertRaises(ModelCardValidationError):
            build_model_card(report, self.metadata(), GENERATED_AT)

    def test_rejectsMissingEvaluationReportType(self):
        report = self.report()
        report.pop("reportType")

        with self.assertRaises(ModelCardValidationError):
            build_model_card(report, self.metadata(), GENERATED_AT)

    def test_rejectsUnsupportedMetricBasis(self):
        report = self.report()
        report["qualityMetrics"]["metricBasis"] = "roc_auc"

        with self.assertRaises(ModelCardValidationError):
            build_model_card(report, self.metadata(), GENERATED_AT)

    def test_rejectsMissingMetricBasis(self):
        report = self.report()
        report["qualityMetrics"].pop("metricBasis")

        with self.assertRaises(ModelCardValidationError):
            build_model_card(report, self.metadata(), GENERATED_AT)

    def test_rejectsUnsupportedDatasetTimeBasis(self):
        report = self.report()
        report["inputSummary"]["exportMetadata"]["timeBasis"] = "TRANSACTION_CREATED_AT"

        with self.assertRaises(ModelCardValidationError):
            build_model_card(report, self.metadata(), GENERATED_AT)

    def test_rejectsUnsupportedDatasetDeduplicationPolicy(self):
        report = self.report()
        report["inputSummary"]["exportMetadata"]["deduplicationPolicy"] = "LATEST_ONLY"

        with self.assertRaises(ModelCardValidationError):
            build_model_card(report, self.metadata(), GENERATED_AT)

    def test_rejectsUnknownDisagreementSummaryField(self):
        report = self.report()
        report["disagreementSummary"]["rawExamples"] = []

        with self.assertRaises(ModelCardValidationError):
            build_model_card(report, self.metadata(), GENERATED_AT)

    def test_preservesRequiredNotIntendedUseWhenCallerOverrides(self):
        metadata = self.metadata()
        metadata["notIntendedUse"] = ["OFFLINE_ONLY"]

        self.assertEqual(
            [
                "NO_AUTOMATIC_APPROVE_DECLINE_BLOCK",
                "NO_MODEL_PROMOTION_APPROVAL",
                "NO_PAYMENT_AUTHORIZATION",
                "NO_PRODUCTION_DECISIONING_APPROVAL",
                "NO_THRESHOLD_RECOMMENDATION",
                "OFFLINE_ONLY",
            ],
            build_model_card(self.report(), metadata, GENERATED_AT)["notIntendedUse"],
        )

    def test_generatedModelCardAlwaysIncludesRequiredNonGoals(self):
        self.assertTrue(
            set([
                "NO_AUTOMATIC_APPROVE_DECLINE_BLOCK",
                "NO_MODEL_PROMOTION_APPROVAL",
                "NO_PAYMENT_AUTHORIZATION",
                "NO_PRODUCTION_DECISIONING_APPROVAL",
                "NO_THRESHOLD_RECOMMENDATION",
            ]).issubset(set(self.model_card()["notIntendedUse"]))
        )

    def test_goldenModelCardFixtureMatchesGeneratedOutput(self):
        self.assertEqual(self.expected_card(), self.model_card())

    def report(self):
        return load_json("fdp103_report_valid.json")

    def metadata(self):
        return load_json("model_metadata_valid.json")

    def expected_card(self):
        return load_json("expected_model_card_v1.json")

    def model_card(self):
        return build_model_card(self.report(), self.metadata(), GENERATED_AT)

    def excellent_report(self):
        report = self.report()
        report["qualityMetrics"].update({
            "precisionAtBudget": 1.0,
            "recallAtTopK": 1.0,
            "falsePositiveRate": 0.0,
            "mlCaughtRulesMissedCount": 10,
            "rulesCaughtMlMissedCount": 0,
        })
        return report


class ModelCardFixtureSafetyTest(unittest.TestCase):
    def test_fixtureDoesNotContainRawIdentifiers(self):
        self.assertFixtureDoesNotContain("rawTransactionId", "customerId", "accountId", "cardId", "deviceId", "merchantId")

    def test_fixtureDoesNotContainPseudonymousReferences(self):
        self.assertFixtureDoesNotContain("evaluationRecordId", "transactionReference", "eval-", "txnref-")

    def test_fixtureDoesNotContainProductionApprovalTerms(self):
        self.assertFixtureDoesNotContain("productionApproved", "promotionApproved", "promotionReady", "approvedForProduction")

    def test_fixtureDoesNotContainThresholdRecommendationTerms(self):
        self.assertFixtureDoesNotContain("thresholdRecommendation", "thresholdRecommended", "deployRecommended")

    def assertFixtureDoesNotContain(self, *terms):
        text = "\n".join(path.read_text(encoding="utf-8") for path in FIXTURE_ROOT.glob("*.json"))
        for term in terms:
            self.assertNotIn(term, text)


def load_json(name):
    return json.loads((FIXTURE_ROOT / name).read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
