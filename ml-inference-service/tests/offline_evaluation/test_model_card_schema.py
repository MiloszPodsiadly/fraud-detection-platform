import unittest

from offline_evaluation.model_card_generator import DEFAULT_NOT_INTENDED_USE, REQUIRED_LIMITATIONS
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

    def test_rejectsUnsupportedEvaluationReportType(self):
        self._assert_rejected(evaluationReportType="OTHER_REPORT")

    def test_rejectsMissingEvaluationReportType(self):
        self._assert_missing_required("evaluationReportType")

    def test_rejectsUnsupportedEvaluationReportVersion(self):
        self._assert_rejected(evaluationReportVersion="FDP-999")

    def test_rejectsUnsupportedMetricBasis(self):
        self._assert_metrics_rejected(metricBasis="roc_auc")

    def test_rejectsMissingMetricBasis(self):
        metrics = valid_metrics_summary()
        metrics.pop("metricBasis")
        self._assert_rejected(metricsSummary=metrics)

    def test_rejectsUnsupportedDatasetTimeBasis(self):
        self._assert_rejected(datasetTimeBasis="TRANSACTION_CREATED_AT")

    def test_rejectsUnsupportedDatasetDeduplicationPolicy(self):
        self._assert_rejected(datasetDeduplicationPolicy="LATEST_ONLY")

    def test_acceptsExpectedFdp103ReportIdentity(self):
        self.assertEqual("PYTHON_ML_EVALUATION_FOUNDATION", validate_model_card(valid_model_card())["evaluationReportType"])

    def test_acceptsExpectedFdp102DatasetBasis(self):
        card = validate_model_card(valid_model_card())

        self.assertEqual("FEEDBACK_SUBMITTED_AT", card["datasetTimeBasis"])
        self.assertEqual(
            "TRANSACTION_REFERENCE_NEWEST_SUBMITTED_AT_FEEDBACK_ID_ASC",
            card["datasetDeduplicationPolicy"],
        )

    def test_modelCardRequiresDiagnosticGovernanceStatus(self):
        self._assert_rejected(governanceStatus="APPROVED")

    def test_modelCardRequiresOfflineModelCardType(self):
        self._assert_rejected(cardType="PRODUCTION_MODEL_CARD")

    def _assert_missing_required(self, field):
        card = valid_model_card()
        card.pop(field)
        with self.assertRaises(ModelCardValidationError):
            validate_model_card(card)

    def _assert_rejected(self, **overrides):
        with self.assertRaises(ModelCardValidationError):
            validate_model_card(valid_model_card(**overrides))

    def _assert_metrics_rejected(self, **overrides):
        metrics = valid_metrics_summary(**overrides)
        self._assert_rejected(metricsSummary=metrics)


class ModelCardMetricsSummaryValidationTest(unittest.TestCase):
    def test_rejectsMissingPrecisionAtBudget(self):
        self._assert_missing_metric("precisionAtBudget")

    def test_rejectsNullPrecisionAtBudget(self):
        self._assert_metrics_rejected(precisionAtBudget=None)

    def test_rejectsStringPrecisionAtBudget(self):
        self._assert_metrics_rejected(precisionAtBudget="0.99")

    def test_rejectsPrecisionAtBudgetAboveOne(self):
        self._assert_metrics_rejected(precisionAtBudget=1.01)

    def test_rejectsPrecisionAtBudgetBelowZero(self):
        self._assert_metrics_rejected(precisionAtBudget=-0.01)

    def test_rejectsStringRecallAtTopK(self):
        self._assert_metrics_rejected(recallAtTopK="0.99")

    def test_rejectsRecallAtTopKAboveOne(self):
        self._assert_metrics_rejected(recallAtTopK=1.01)

    def test_rejectsNegativeFalsePositiveRate(self):
        self._assert_metrics_rejected(falsePositiveRate=-0.01)

    def test_rejectsStringFalsePositiveRate(self):
        self._assert_metrics_rejected(falsePositiveRate="excellent")

    def test_rejectsNegativeDatasetRecordsRead(self):
        self._assert_metrics_rejected(datasetRecordsRead=-1)

    def test_rejectsNonIntegerDatasetRecordsRead(self):
        self._assert_metrics_rejected(datasetRecordsRead=1.5)

    def test_rejectsBoolDatasetRecordsRead(self):
        self._assert_metrics_rejected(datasetRecordsRead=True)

    def test_rejectsNegativeMissingMlCount(self):
        self._assert_metrics_rejected(missingMlCount=-1)

    def test_rejectsStringMissingMlCount(self):
        self._assert_metrics_rejected(missingMlCount="many")

    def test_rejectsNullMlCaughtRulesMissedCount(self):
        self._assert_metrics_rejected(mlCaughtRulesMissedCount=None)

    def test_rejectsCountAboveDatasetBound(self):
        self._assert_metrics_rejected(datasetRecordsRead=501)

    def test_rejectsUnknownMetricsSummaryField(self):
        self._assert_metrics_rejected(unexpectedAuc=0.99)

    def test_rejectsAcceptedRecordsGreaterThanDatasetRecordsRead(self):
        self._assert_metrics_rejected(datasetRecordsRead=4, recordsAcceptedForEvaluation=5)

    def test_rejectsExcludedRecordsGreaterThanDatasetRecordsRead(self):
        self._assert_metrics_rejected(datasetRecordsRead=4, recordsExcludedNotEvaluationEligible=5)

    def test_rejectsAcceptedPlusExcludedGreaterThanDatasetRecordsRead(self):
        self._assert_metrics_rejected(
            datasetRecordsRead=4,
            recordsAcceptedForEvaluation=4,
            recordsExcludedNotEvaluationEligible=1,
            notEvaluationEligibleCount=1,
        )

    def test_rejectsMissingMlCountGreaterThanDatasetRecordsRead(self):
        self._assert_metrics_rejected(datasetRecordsRead=4, missingMlCount=5)

    def test_rejectsMissingRulesCountGreaterThanDatasetRecordsRead(self):
        self._assert_metrics_rejected(datasetRecordsRead=4, missingRulesCount=5)

    def test_rejectsMissingProjectionCountGreaterThanDatasetRecordsRead(self):
        self._assert_metrics_rejected(datasetRecordsRead=4, missingProjectionCount=5)

    def test_rejectsNotEvaluationEligibleMismatch(self):
        self._assert_metrics_rejected(recordsExcludedNotEvaluationEligible=1, notEvaluationEligibleCount=2)

    def test_rejectsDisagreementSummaryTotalGreaterThanDatasetRecordsRead(self):
        self._assert_metrics_rejected(datasetRecordsRead=4)

    def test_acceptsValidMetricsSummary(self):
        self.assertEqual(valid_metrics_summary(), validate_model_card(valid_model_card())["metricsSummary"])

    def _assert_missing_metric(self, field):
        metrics = valid_metrics_summary()
        metrics.pop(field)
        with self.assertRaises(ModelCardValidationError):
            validate_model_card(valid_model_card(metricsSummary=metrics))

    def _assert_metrics_rejected(self, **overrides):
        with self.assertRaises(ModelCardValidationError):
            validate_model_card(valid_model_card(metricsSummary=valid_metrics_summary(**overrides)))


class ModelCardDisagreementSummaryValidationTest(unittest.TestCase):
    def test_rejectsUnknownDisagreementSummaryField(self):
        self._assert_disagreement_rejected(unexpectedBreakdown=1)

    def test_rejectsNestedDisagreementSummaryObject(self):
        self._assert_disagreement_rejected(rulesHighMlHigh={"nested": 1})

    def test_rejectsStringDisagreementSummaryValue(self):
        self._assert_disagreement_rejected(rulesHighMlHigh="many")

    def test_rejectsNegativeDisagreementSummaryValue(self):
        self._assert_disagreement_rejected(rulesHighMlHigh=-1)

    def test_rejectsBoolDisagreementSummaryValue(self):
        self._assert_disagreement_rejected(rulesHighMlHigh=True)

    def test_rejectsOversizedDisagreementSummaryValue(self):
        self._assert_disagreement_rejected(rulesHighMlHigh=501)

    def test_rejectsPerRecordExamplesInDisagreementSummary(self):
        self._assert_disagreement_rejected(perRecordExamples=[{"bucket": "unsafe"}])

    def test_rejectsEvaluationRecordIdInDisagreementSummary(self):
        self._assert_disagreement_rejected(evaluationRecordId="eval-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")

    def test_rejectsTransactionReferenceInDisagreementSummary(self):
        self._assert_disagreement_rejected(transactionReference="txnref-bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")

    def test_copiesOnlyAllowlistedDisagreementSummaryFields(self):
        disagreement = validate_model_card(valid_model_card())["metricsSummary"]["disagreementSummary"]

        self.assertEqual(set(valid_disagreement_summary()), set(disagreement))

    def test_generatedModelCardDisagreementSummaryIsDeterministic(self):
        first = validate_model_card(valid_model_card())["metricsSummary"]["disagreementSummary"]
        second = validate_model_card(valid_model_card())["metricsSummary"]["disagreementSummary"]

        self.assertEqual(first, second)

    def _assert_disagreement_rejected(self, **overrides):
        disagreement = valid_disagreement_summary(**overrides)
        metrics = valid_metrics_summary(disagreementSummary=disagreement)
        with self.assertRaises(ModelCardValidationError):
            validate_model_card(valid_model_card(metricsSummary=metrics))


class ModelCardIdentityValidationTest(unittest.TestCase):
    def test_rejectsModelNameUrl(self):
        self._assert_rejected(modelName="https://models.example/model")

    def test_rejectsModelNameRegistryUrl(self):
        self._assert_rejected(modelName="registry.example/model")

    def test_rejectsModelNameEmailLikeValue(self):
        self._assert_rejected(modelName="owner@example.com")

    def test_rejectsModelNameWithWhitespace(self):
        self._assert_rejected(modelName="python model")

    def test_rejectsModelNamePath(self):
        self._assert_rejected(modelName="models/python-logistic")

    def test_rejectsModelVersionPath(self):
        self._assert_rejected(modelVersion="../model")

    def test_rejectsModelVersionS3Uri(self):
        self._assert_rejected(modelVersion="s3://bucket/model")

    def test_rejectsModelVersionRegistryUrl(self):
        self._assert_rejected(modelVersion="registry/model:latest")

    def test_rejectsFeatureContractVersionPathTraversal(self):
        self._assert_rejected(featureContractVersion="../contract")

    def test_rejectsFeatureContractVersionUrl(self):
        self._assert_rejected(featureContractVersion="https://example.com/contract")

    def test_acceptsSafeModelName(self):
        self.assertEqual("model_1.alpha-2", validate_model_card(valid_model_card(modelName="model_1.alpha-2"))["modelName"])

    def test_acceptsSafeModelVersion(self):
        self.assertEqual("2026.06.08-v1", validate_model_card(valid_model_card(modelVersion="2026.06.08-v1"))["modelVersion"])

    def test_acceptsSafeFeatureContractVersion(self):
        self.assertEqual(
            "feature-contract_2026.06",
            validate_model_card(valid_model_card(featureContractVersion="feature-contract_2026.06"))["featureContractVersion"],
        )

    def _assert_rejected(self, **overrides):
        with self.assertRaises(ModelCardValidationError):
            validate_model_card(valid_model_card(**overrides))


class ModelCardUseValidationTest(unittest.TestCase):
    def test_rejectsUnknownIntendedUse(self):
        self._assert_rejected(intendedUse=["UNKNOWN_USE"])

    def test_rejectsProductionDecisioningIntendedUse(self):
        self._assert_rejected(intendedUse=["PRODUCTION_DECISIONING"])

    def test_rejectsLiveDecisionSupportIntendedUse(self):
        self._assert_rejected(intendedUse=["LIVE_DECISION_SUPPORT"])

    def test_rejectsRealtimeRiskActioningIntendedUse(self):
        self._assert_rejected(intendedUse=["REALTIME_RISK_ACTIONING"])

    def test_rejectsPaymentAuthorizationIntendedUse(self):
        self._assert_rejected(intendedUse=["PAYMENT_AUTHORIZATION"])

    def test_rejectsAnalystRecommendationIntendedUse(self):
        self._assert_rejected(intendedUse=["ANALYST_RECOMMENDATION"])

    def test_rejectsNotIntendedUseMissingRequiredNonGoals(self):
        self._assert_rejected(notIntendedUse=["NO_MODEL_PROMOTION_APPROVAL"])

    def test_requiredNotIntendedUseDoesNotSelfReject(self):
        self.assertEqual(DEFAULT_NOT_INTENDED_USE, validate_model_card(valid_model_card())["notIntendedUse"])

    def _assert_rejected(self, **overrides):
        with self.assertRaises(ModelCardValidationError):
            validate_model_card(valid_model_card(**overrides))


class ModelCardApprovedForValidationTest(unittest.TestCase):
    def test_rejectsOfflineEvaluationApprovedFor(self):
        self._assert_rejected(approvedFor=["OFFLINE_EVALUATION"])

    def test_approvedForAllowsOnlyShadowAndCompare(self):
        self.assertEqual(["COMPARE", "SHADOW"], validate_model_card(valid_model_card())["approvedFor"])

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

    def test_safeNegatedGovernanceCodesAreAllowed(self):
        card = valid_model_card(limitations=REQUIRED_LIMITATIONS, notIntendedUse=DEFAULT_NOT_INTENDED_USE)

        self.assertEqual(REQUIRED_LIMITATIONS, validate_model_card(card)["limitations"])

    def test_requiredLimitationsDoNotSelfReject(self):
        self.assertEqual(REQUIRED_LIMITATIONS, validate_model_card(valid_model_card())["limitations"])

    def test_rejectsGroundTruthPositiveTerm(self):
        self._assert_rejected(warnings=["GROUND_TRUTH"])

    def test_rejectsModelPromotionApprovedPositiveTerm(self):
        self._assert_rejected(warnings=["MODEL_PROMOTION_APPROVED"])

    def test_rejectsThresholdRecommendationPositiveTerm(self):
        self._assert_rejected(warnings=["THRESHOLD_RECOMMENDATION"])

    def test_rejectsProductionApprovedPositiveTerm(self):
        self._assert_rejected(warnings=["PRODUCTION_APPROVED"])

    def test_rejectsPaymentAuthorizationPositiveTerm(self):
        self._assert_rejected(warnings=["PAYMENT_AUTHORIZATION"])

    def test_rejectsAutomaticDeclinePositiveTerm(self):
        self._assert_rejected(warnings=["AUTOMATIC_DECLINE"])

    def test_rejectsBankCertifiedPositiveTerm(self):
        self._assert_rejected(warnings=["BANK_CERTIFIED"])

    def _assert_rejected(self, **overrides):
        with self.assertRaises(ModelCardValidationError):
            validate_model_card(valid_model_card(**overrides))


class ModelCardWarningValidationTest(unittest.TestCase):
    def test_allowsEmptyWarnings(self):
        self.assertEqual([], validate_model_card(valid_model_card(warnings=[]))["warnings"])

    def test_rejectsWarningsAsString(self):
        self._assert_rejected(warnings="MISSING_ML_SIGNAL_PRESENT")

    def test_rejectsWarningsAsDict(self):
        self._assert_rejected(warnings={"warning": "MISSING_ML_SIGNAL_PRESENT"})

    def test_rejectsWarningsAsNumber(self):
        self._assert_rejected(warnings=1)

    def test_rejectsTooManyWarnings(self):
        self._assert_rejected(warnings=[f"WARNING_{index}" for index in range(11)])

    def test_rejectsOversizedWarning(self):
        self._assert_rejected(warnings=["A" * 257])

    def test_rejectsUnsafeWarningTerm(self):
        self._assert_rejected(warnings=["FINAL_DECISION"])

    def test_rejectsWarningWithEvalPrefix(self):
        self._assert_rejected(warnings=["EVAL_BAD"])

    def test_rejectsWarningWithTxnrefPrefix(self):
        self._assert_rejected(warnings=["TXNREF_BAD"])

    def test_rejectsWarningWithRawIdentifier(self):
        self._assert_rejected(warnings=["CUSTOMER_ID"])

    def test_rejectsWarningWithGroundTruthTerm(self):
        self._assert_rejected(warnings=["GROUND_TRUTH"])

    def test_rejectsWarningWithPaymentAuthorizationTerm(self):
        self._assert_rejected(warnings=["PAYMENT_AUTHORIZATION"])

    def test_rejectsWarningWithFreeText(self):
        self._assert_rejected(warnings=["free text warning"])

    def test_acceptsSafeWarningMachineCode(self):
        self.assertEqual(["MISSING_ML_SIGNAL_PRESENT"], validate_model_card(valid_model_card())["warnings"])

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
        "notIntendedUse": DEFAULT_NOT_INTENDED_USE,
        "metricsSummary": valid_metrics_summary(),
        "limitations": REQUIRED_LIMITATIONS,
        "warnings": ["MISSING_ML_SIGNAL_PRESENT"],
        "governanceStatus": "DIAGNOSTIC_ONLY",
    }
    card.update(overrides)
    return card


def valid_metrics_summary(**overrides):
    summary = {
        "metricBasis": "bucket_ordered_offline_diagnostic",
        "diagnosticOnly": True,
        "datasetRecordsRead": 5,
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
        "disagreementSummary": valid_disagreement_summary(),
    }
    summary.update(overrides)
    return summary


def valid_disagreement_summary(**overrides):
    summary = {
        "rulesHighMlHigh": 1,
        "rulesHighMlLowOrMedium": 0,
        "rulesLowOrMediumMlHigh": 1,
        "rulesLowOrMediumMlLowOrMedium": 1,
        "rulesMissingMlPresent": 0,
        "mlMissingRulesPresent": 1,
        "bothMissing": 0,
        "notEvaluationEligibleExcluded": 1,
    }
    summary.update(overrides)
    return summary


if __name__ == "__main__":
    unittest.main()
