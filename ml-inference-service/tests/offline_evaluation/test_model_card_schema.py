import unittest

from offline_evaluation.model_card_schema import ModelCardValidationError, validate_model_card


class ModelCardSchemaTest(unittest.TestCase):
    def test_modelCardRequiresModelName(self):
        self._assert_missing_required("modelName")

    def test_modelCardRequiresModelVersion(self):
        self._assert_missing_required("modelVersion")

    def test_modelCardRequiresModelFamily(self):
        self._assert_missing_required("modelFamily")

    def test_modelCardRequiresFeatureContractVersion(self):
        self._assert_missing_required("featureContractVersion")

    def test_modelCardRequiresEvaluationReportVersion(self):
        self._assert_missing_required("evaluationReportVersion")

    def test_modelCardRequiresGeneratedAt(self):
        self._assert_missing_required("generatedAt")

    def test_modelCardRequiresIntendedUse(self):
        self._assert_missing_required("intendedUse")

    def test_modelCardRequiresNotIntendedUse(self):
        self._assert_missing_required("notIntendedUse")

    def test_modelCardRequiresApprovedFor(self):
        self._assert_missing_required("approvedFor")

    def test_modelCardRejectsUnknownApprovedFor(self):
        self._assert_rejected(approvedFor=["BANANA"])

    def test_modelCardRejectsProductionDecisioningApproval(self):
        self._assert_rejected(approvedFor=["PRODUCTION_DECISIONING"])

    def test_modelCardRejectsAutoDeclineApproval(self):
        self._assert_rejected(approvedFor=["AUTO_DECLINE"])

    def test_modelCardRejectsPaymentAuthorizationApproval(self):
        self._assert_rejected(approvedFor=["PAYMENT_AUTHORIZATION"])

    def test_modelCardRejectsPromotionApproved(self):
        self._assert_rejected(approvedFor=["MODEL_PROMOTION_APPROVED"])

    def test_modelCardRejectsThresholdRecommendation(self):
        self._assert_rejected(limitations=["THRESHOLD_RECOMMENDATION"])

    def test_modelCardRejectsUnboundedMetadataMap(self):
        self._assert_rejected(metadata={"owner": "unsafe"})

    def test_modelCardRejectsRawEvaluationReport(self):
        self._assert_rejected(rawEvaluationReport={"reportType": "PYTHON_ML_EVALUATION_FOUNDATION"})

    def test_modelCardRejectsRawFeatureVector(self):
        self._assert_rejected(metricsSummary={**valid_model_card()["metricsSummary"], "rawFeatureVector": [1, 2]})

    def _assert_missing_required(self, field):
        card = valid_model_card()
        card.pop(field)
        with self.assertRaises(ModelCardValidationError):
            validate_model_card(card)

    def _assert_rejected(self, **overrides):
        with self.assertRaises(ModelCardValidationError):
            validate_model_card(valid_model_card(**overrides))


class ModelCardNoOverclaimTest(unittest.TestCase):
    def test_rejectsProductionApprovedField(self):
        self._assert_rejected(productionApproved=True)

    def test_rejectsApprovedForProductionValue(self):
        self._assert_rejected(intendedUse=["APPROVED_FOR_PRODUCTION"])

    def test_rejectsPromotionReadyField(self):
        self._assert_rejected(promotionReady=True)

    def test_rejectsPromotionApprovedField(self):
        self._assert_rejected(promotionApproved=True)

    def test_rejectsThresholdRecommendedField(self):
        self._assert_rejected(thresholdRecommended=True)

    def test_rejectsDeployRecommendedField(self):
        self._assert_rejected(deployRecommended=True)

    def test_rejectsChampionApprovedFor(self):
        self._assert_rejected(approvedFor=["CHAMPION"])

    def test_rejectsBankCertifiedWording(self):
        self._assert_rejected(warnings=["BANK_CERTIFIED"])

    def test_rejectsPaymentAuthorizationWording(self):
        self._assert_rejected(warnings=["PAYMENT_AUTHORIZATION"])

    def test_rejectsAutoDeclineApproveBlockWording(self):
        for value in ("AUTOMATIC_DECLINE", "AUTO_DECLINE", "AUTO_APPROVE", "AUTO_BLOCK"):
            with self.subTest(value=value):
                self._assert_rejected(warnings=[value])

    def _assert_rejected(self, **overrides):
        with self.assertRaises(ModelCardValidationError):
            validate_model_card(valid_model_card(**overrides))


def valid_model_card(**overrides):
    card = {
        "modelCardVersion": "1.0",
        "cardType": "OFFLINE_MODEL_CARD_V1",
        "generatedAt": "2026-06-08T01:00:00Z",
        "modelName": "python-logistic-fraud-model",
        "modelVersion": "2026-04-21.trained.v1",
        "modelFamily": "LOGISTIC_REGRESSION",
        "featureContractVersion": "2026-04-22.v1",
        "evaluationReportType": "PYTHON_ML_EVALUATION_FOUNDATION",
        "evaluationReportVersion": "FDP-103",
        "evaluationReportGeneratedAt": "2026-06-08T00:00:00Z",
        "datasetTimeBasis": "FEEDBACK_SUBMITTED_AT",
        "datasetDeduplicationPolicy": "TRANSACTION_REFERENCE_NEWEST_SUBMITTED_AT_FEEDBACK_ID_ASC",
        "approvedFor": ["SHADOW", "COMPARE"],
        "intendedUse": ["SHADOW_FRAUD_RISK_DIAGNOSTICS", "COMPARE_MODE_ANALYSIS"],
        "notIntendedUse": [
            "NO_AUTOMATIC_APPROVE_DECLINE_BLOCK",
            "NO_MODEL_PROMOTION_APPROVAL",
            "NO_PAYMENT_AUTHORIZATION",
            "NO_PRODUCTION_DECISIONING_APPROVAL",
            "NO_THRESHOLD_RECOMMENDATION",
        ],
        "metricsSummary": {
            "metricBasis": "bucket_ordered_offline_diagnostic",
            "diagnosticOnly": True,
            "datasetRecordsRead": 4,
            "recordsAcceptedForEvaluation": 3,
            "recordsExcludedNotEvaluationEligible": 1,
            "missingMlCount": 1,
            "missingRulesCount": 1,
            "missingProjectionCount": 1,
            "notEvaluationEligibleCount": 1,
            "precisionAtBudget": 0.5,
            "recallAtTopK": 0.5,
            "falsePositiveRate": 0.25,
            "mlCaughtRulesMissedCount": 1,
            "rulesCaughtMlMissedCount": 1,
            "disagreementSummary": {"rulesHighMlHigh": 1},
        },
        "limitations": [
            "OFFLINE_ONLY",
            "DIAGNOSTIC_ONLY",
            "ANALYST_LABELS_ARE_EVALUATION_SIGNALS_NOT_GROUND_TRUTH",
            "NOT_EVALUATION_ELIGIBLE_EXCLUDED_FROM_QUALITY_METRICS",
            "BUCKET_ORDERED_METRICS_NOT_CALIBRATED_PROBABILITIES",
            "NO_MODEL_PROMOTION_APPROVAL",
            "NO_THRESHOLD_RECOMMENDATION",
            "NO_PRODUCTION_DECISIONING_APPROVAL",
            "NO_PAYMENT_AUTHORIZATION",
            "NO_AUTOMATIC_APPROVE_DECLINE_BLOCK",
        ],
        "warnings": ["MISSING_ML_SIGNAL_PRESENT"],
        "governanceStatus": "DIAGNOSTIC_ONLY",
    }
    card.update(overrides)
    return card


if __name__ == "__main__":
    unittest.main()
