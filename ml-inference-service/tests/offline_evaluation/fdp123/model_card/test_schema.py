import unittest

from offline_evaluation.fdp123.model_card.schema import (
    EVALUATION_SUBJECT,
    MAX_COUNT_VALUE,
    MAX_FDP123_DATASET_RECORDS,
    METRIC_BASIS,
    METRICS_SUBJECT,
    REQUIRED_GOVERNANCE_BOUNDARY,
    REQUIRED_LIMITATIONS,
    REQUIRED_NOT_INTENDED_USE,
    Fdp123ModelCardValidationError,
    validate_model_card,
)


class Fdp123ModelCardSchemaTest(unittest.TestCase):
    def test_validModelCardAccepted(self):
        self.assertEqual("model-card-v1", validate_model_card(valid_model_card())["modelCardVersion"])

    def test_missingRequiredFieldRejected(self):
        card = valid_model_card()
        card.pop("evaluationSubject")

        with self.assertRaises(Fdp123ModelCardValidationError):
            validate_model_card(card)

    def test_unknownFieldRejected(self):
        self._assert_rejected(extra="unsafe")

    def test_unsupportedModelCardVersionRejected(self):
        self._assert_rejected(modelCardVersion="1.0")

    def test_oldFdp103ModelCardShapeRejected(self):
        with self.assertRaises(Fdp123ModelCardValidationError):
            validate_model_card({
                "modelCardVersion": "1.0",
                "cardType": "OFFLINE_MODEL_CARD_V1",
                "approvedFor": ["SHADOW"],
                "evaluationReportType": "PYTHON_ML_EVALUATION_FOUNDATION",
            })

    def test_approvedForRejected(self):
        self._assert_rejected(approvedFor=["SHADOW"])

    def test_unsupportedAllowedUsageModeRejected(self):
        self._assert_rejected(allowedUsageModes=["BANANA"])

    def test_modelIdentityFieldsRejected(self):
        for field in ("modelName", "modelVersion", "modelFamily", "trainingMode", "featureContractVersion", "referenceQuality"):
            with self.subTest(field=field):
                self._assert_rejected(**{field: "caller-controlled"})

    def test_evaluationSubjectMustMatchFdp124Contract(self):
        subject = dict(EVALUATION_SUBJECT)
        subject["sourceVersion"] = "OTHER"

        self._assert_rejected(evaluationSubject=subject)

    def test_metricsSubjectAndBasisMustMatchFdp124Contract(self):
        self._assert_rejected(metricsSubject="MODEL")
        self._assert_rejected(metricBasis="MODEL_PERFORMANCE")

    def test_recordLimitUsesFdp123DatasetMaximum(self):
        self.assertEqual(MAX_FDP123_DATASET_RECORDS, MAX_COUNT_VALUE)
        self._assert_rejected(evaluationEvidence=valid_evaluation_evidence(
            recordsEvaluated=MAX_FDP123_DATASET_RECORDS + 1,
            positiveClassCount=MAX_FDP123_DATASET_RECORDS + 1,
            negativeClassCount=0,
        ))

    def test_productionChampionAutoDeclinePaymentAuthorizationRejected(self):
        for value in ("PRODUCTION", "CHAMPION", "AUTO_DECLINE", "PAYMENT_AUTHORIZATION"):
            with self.subTest(value=value):
                self._assert_rejected(allowedUsageModes=[value])

    def test_productionApprovalMustRemainNotApproved(self):
        self._assert_rejected(productionApproval="PRODUCTION_APPROVED")

    def test_promotionStatusMustRemainNotEvaluated(self):
        self._assert_rejected(promotionStatus="PROMOTION_READY")

    def test_missingRequiredNotIntendedUseRejected(self):
        self._assert_rejected(notIntendedUse=["NO_PAYMENT_AUTHORIZATION"])

    def test_missingLimitationsRejected(self):
        self._assert_rejected(limitations=["SMALL_SAMPLE_SIZE_MAY_BE_INCONCLUSIVE"])

    def test_missingGovernanceBoundaryRejected(self):
        self._assert_rejected(governanceBoundary=["NO_MODEL_PROMOTION"])

    def test_unsupportedReferenceQualityRejected(self):
        self._assert_rejected(referenceQuality="GROUND_TRUTH")

    def test_trainingLabelsRejected(self):
        self._assert_rejected(warnings=["TRAINING_LABELS"])

    def test_metricUnavailableRequiresReason(self):
        metrics = valid_metrics()
        metrics["alertRecommendedRecall"] = {"available": False, "value": None, "reason": None}
        self._assert_rejected(metricsSummary=metrics)

    def test_metricUnavailableRequiresNullValue(self):
        metrics = valid_metrics()
        metrics["alertRecommendedRecall"] = {"available": False, "value": 0.0, "reason": "NO_ACTUAL_POSITIVES"}
        self._assert_rejected(metricsSummary=metrics)

    def test_metricAvailableRequiresNumericValueAndNullReason(self):
        metrics = valid_metrics()
        metrics["alertRecommendedRecall"] = {"available": True, "value": None, "reason": "NO_ACTUAL_POSITIVES"}
        self._assert_rejected(metricsSummary=metrics)

    def test_classCountsMustEqualRecordsEvaluated(self):
        card = valid_model_card(evaluationEvidence=valid_evaluation_evidence(
            recordsEvaluated=2,
            positiveClassCount=1,
            negativeClassCount=1,
        ))

        self.assertEqual(2, validate_model_card(card)["evaluationEvidence"]["recordsEvaluated"])

    def test_classCountSumBelowRecordsEvaluatedRejected(self):
        self._assert_rejected(evaluationEvidence=valid_evaluation_evidence(
            recordsEvaluated=100,
            positiveClassCount=10,
            negativeClassCount=10,
        ))

    def test_classCountSumAboveRecordsEvaluatedRejected(self):
        self._assert_rejected(evaluationEvidence=valid_evaluation_evidence(
            recordsEvaluated=1,
            positiveClassCount=1,
            negativeClassCount=1,
        ))

    def test_zeroClassCountsAcceptedForEmptyEvaluation(self):
        card = valid_model_card(evaluationEvidence=valid_evaluation_evidence(
            recordsEvaluated=0,
            positiveClassCount=0,
            negativeClassCount=0,
        ))

        self.assertEqual(0, validate_model_card(card)["evaluationEvidence"]["recordsEvaluated"])

    def test_booleanClassCountRejected(self):
        self._assert_rejected(evaluationEvidence=valid_evaluation_evidence(
            recordsEvaluated=1,
            positiveClassCount=True,
            negativeClassCount=0,
        ))

    def test_disagreementSummaryRejectedFromFdp126ModelCardV1(self):
        metrics = valid_metrics(disagreementSummary={"totalDisagreementRows": 1})

        self._assert_rejected(metricsSummary=metrics)

    def test_validZTimestampAccepted(self):
        card = validate_model_card(valid_model_card(generatedAt="2026-06-12T00:00:00Z"))

        self.assertEqual("2026-06-12T00:00:00Z", card["generatedAt"])

    def test_validExplicitOffsetTimestampAcceptedAndNormalized(self):
        card = validate_model_card(valid_model_card(
            generatedAt="2026-06-12T02:00:00+02:00",
            evaluationEvidence=valid_evaluation_evidence(evaluationGeneratedAt="2026-06-10T02:00:00+02:00"),
        ))

        self.assertEqual("2026-06-12T00:00:00Z", card["generatedAt"])
        self.assertEqual("2026-06-10T00:00:00Z", card["evaluationEvidence"]["evaluationGeneratedAt"])

    def test_arbitraryTimestampTextRejected(self):
        for value in ("yesterday", "banana", "not-a-date"):
            with self.subTest(value=value):
                self._assert_rejected(generatedAt=value)

    def test_dateOnlyTimestampRejected(self):
        self._assert_rejected(generatedAt="2026-06-12")

    def test_naiveTimestampRejected(self):
        self._assert_rejected(generatedAt="2026-06-12T00:00:00")

    def test_malformedCalendarTimestampRejected(self):
        self._assert_rejected(generatedAt="2026-13-40T00:00:00Z")

    def test_modelCardGeneratedBeforeEvaluationRejected(self):
        self._assert_rejected(
            generatedAt="2026-06-09T23:59:59Z",
            evaluationEvidence=valid_evaluation_evidence(evaluationGeneratedAt="2026-06-10T00:00:00Z"),
        )

    def test_modelCardGeneratedAtSameInstantAsEvaluationAccepted(self):
        card = validate_model_card(valid_model_card(
            generatedAt="2026-06-10T00:00:00Z",
            evaluationEvidence=valid_evaluation_evidence(evaluationGeneratedAt="2026-06-10T00:00:00Z"),
        ))

        self.assertEqual("2026-06-10T00:00:00Z", card["generatedAt"])

    def test_modelCardGeneratedAfterEvaluationAccepted(self):
        card = validate_model_card(valid_model_card(
            generatedAt="2026-06-10T00:00:01Z",
            evaluationEvidence=valid_evaluation_evidence(evaluationGeneratedAt="2026-06-10T00:00:00Z"),
        ))

        self.assertEqual("2026-06-10T00:00:01Z", card["generatedAt"])

    def test_rawIdsRejected(self):
        for field in ("evaluationRecordId", "transactionReference", "feedbackId", "customerId"):
            with self.subTest(field=field):
                self._assert_rejected(**{field: "unsafe"})

    def test_groundTruthTrainingLabelFinalDecisionPaymentDecisionRejected(self):
        for field in ("groundTruth", "trainingLabel", "finalDecision", "paymentDecision"):
            with self.subTest(field=field):
                self._assert_rejected(**{field: "unsafe"})

    def _assert_rejected(self, **overrides):
        with self.assertRaises(Fdp123ModelCardValidationError):
            validate_model_card(valid_model_card(**overrides))


def valid_model_card(**overrides):
    card = {
        "modelCardVersion": "model-card-v1",
        "cardType": "MODEL_CARD_V1",
        "generatedAt": "2026-06-12T00:00:00Z",
        "evaluationSubject": dict(EVALUATION_SUBJECT),
        "metricsSubject": METRICS_SUBJECT,
        "metricBasis": METRIC_BASIS,
        "allowedUsageModes": ["SHADOW", "COMPARE", "OFFLINE_EVALUATION"],
        "productionApproval": "NOT_APPROVED",
        "promotionStatus": "NOT_EVALUATED_FOR_PROMOTION",
        "intendedUse": ["SHADOW_FRAUD_RISK_REVIEW", "OFFLINE_DIAGNOSTIC_ANALYSIS"],
        "notIntendedUse": sorted(REQUIRED_NOT_INTENDED_USE),
        "evaluationEvidence": valid_evaluation_evidence(),
        "metricsSummary": valid_metrics(),
        "warnings": ["LOW_SAMPLE_SIZE"],
        "limitations": sorted(REQUIRED_LIMITATIONS),
        "governanceBoundary": sorted(REQUIRED_GOVERNANCE_BOUNDARY),
    }
    card.update(overrides)
    return card


def valid_evaluation_evidence(**overrides):
    evidence = {
        "evaluationReportType": "FDP123_FEEDBACK_DATASET_OFFLINE_EVALUATION_V1",
        "evaluationGeneratedAt": "2026-06-10T00:00:00Z",
        "evaluationArtifactSetVersion": "fdp123-report-artifact-set-v1",
        "datasetVersion": "feedback-dataset-v1",
        "datasetTimeBasis": "FEEDBACK_CREATED_AT",
        "recordsEvaluated": 2,
        "positiveClassCount": 1,
        "negativeClassCount": 1,
        "warnings": ["LOW_SAMPLE_SIZE"],
        "sourceManifestSha256": "a" * 64,
    }
    evidence.update(overrides)
    return evidence


def valid_metrics(**overrides):
    metrics = {
        "alertRecommendedPrecision": {"available": True, "value": 0.5, "reason": None},
        "alertRecommendedRecall": {"available": True, "value": 0.5, "reason": None},
        "falsePositiveRate": {"available": True, "value": 0.5, "reason": None},
        "falseNegativeRate": {"available": True, "value": 0.5, "reason": None},
    }
    metrics.update(overrides)
    return metrics


if __name__ == "__main__":
    unittest.main()

