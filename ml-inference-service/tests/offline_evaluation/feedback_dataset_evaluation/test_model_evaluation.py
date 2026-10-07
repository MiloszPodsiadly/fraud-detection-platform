import json
import unittest
from dataclasses import replace

from offline_evaluation.feedback_dataset_evaluation.classification_policy import RISK_CLASSIFICATION_POLICY
from offline_evaluation.feedback_dataset_evaluation.dataset_reader import read_feedback_dataset_jsonl
from offline_evaluation.feedback_dataset_evaluation.dataset_schema import (
    MAX_DATASET_RECORDS,
    FeedbackDatasetValidationError,
)
from offline_evaluation.feedback_dataset_evaluation.evaluation_runner import build_feedback_dataset_evaluation_reports
from offline_evaluation.feedback_dataset_evaluation.model_evaluation import (
    INSUFFICIENT_MODEL_LINEAGE_RECORDS,
    INVALID_ML_EVIDENCE_PRESENT,
    MODEL_EVALUATION_REPORT_TYPE,
    MODEL_EVALUATION_PARTIAL_COVERAGE,
    MODEL_PREDICTION_SIGNAL_UNAVAILABLE,
    ML_SCORE_RANKING_POLICY,
    RULES_SIGNAL_UNAVAILABLE,
    SINGLE_CLASS_MODEL_LINEAGE_RECORDS,
    SOURCE_DATASET_INVALID_ROWS_SKIPPED,
    SOURCE_DATASET_REQUIRED_FIELDS_MISSING,
    SOURCE_DATASET_TRUNCATED,
    UNEXPECTED_ML_EVIDENCE_LOSS,
    ModelEvaluationIdentity,
    build_model_specific_evaluation_summary,
    validate_model_evaluation_summary,
)
from offline_evaluation.feedback_dataset_evaluation.metrics import (
    DEFAULT_TOP_K_VALUES,
    build_binary_classification_metrics_from_counts,
    build_ranked_metric_rows,
)
from offline_evaluation.feedback_dataset_evaluation.report_writer import report_json

try:
    from feedback_dataset_evaluation.feedback_dataset_fixtures import GENERATED_AT, jsonl, jsonl_file, record
except ModuleNotFoundError:
    from feedback_dataset_fixtures import GENERATED_AT, jsonl, jsonl_file, record


MODEL_X = ModelEvaluationIdentity(
    "python-logistic-fraud-model",
    "2026-06-25.v1",
    "feature-contract-v2",
)
MODEL_Y = ModelEvaluationIdentity(
    "python-logistic-fraud-model",
    "2026-07-01.v1",
    "feature-contract-v2",
)
SHADOW_MODEL = ModelEvaluationIdentity(
    "python-logistic-fraud-model",
    "ml-shadow-2026-06-01",
    "feature-contract-v2",
)


class ModelSpecificEvaluationTest(unittest.TestCase):
    def test_allRecordsForModelXSucceeds(self):
        summary = self._model_summary(
            self._model_record("eval_11111111111111111111111111111111", MODEL_X),
            self._model_record(
                "eval_22222222222222222222222222222222",
                MODEL_X,
                feedbackLabel="CONFIRMED_LEGITIMATE",
                evaluationLabel="NEGATIVE_LEGITIMATE",
            ),
        )

        self.assertEqual(MODEL_EVALUATION_REPORT_TYPE, summary["reportType"])
        self.assertEqual(MODEL_X.as_subject(), summary["evaluationSubject"])
        self.assertEqual(2, summary["population"]["recordsEvaluated"])
        self.assertEqual(0, summary["population"]["recordsExcludedIdentityMismatch"])
        self.assertEqual({"positiveClassCount": 1, "negativeClassCount": 1}, summary["classBalance"])

    def test_mixedModelVersionsAreNotSilentlyCombined(self):
        summary = self._model_summary(
            self._model_record("eval_11111111111111111111111111111111", MODEL_X),
            self._model_record("eval_22222222222222222222222222222222", MODEL_Y),
        )

        self.assertEqual(2, summary["population"]["recordsConsidered"])
        self.assertEqual(1, summary["population"]["recordsEvaluated"])
        self.assertEqual(1, summary["population"]["recordsExcludedIdentityMismatch"])
        self.assertEqual("EXCLUDE", summary["lineagePolicy"]["identityMismatchBehavior"])

    def test_legitimatelyAbsentPredictionIsNotAttributedToRequestedModel(self):
        summary = self._model_summary(record(), self._model_record("eval_22222222222222222222222222222222", MODEL_X))

        self.assertEqual(1, summary["population"]["recordsEvaluated"])
        self.assertEqual(1, summary["population"]["recordsExcludedMissingPredictionEvidence"])

    def test_partialLineageFailsInsteadOfBecomingUnavailableLineage(self):
        with jsonl_file(jsonl(record(
            mlModelName="python-logistic-fraud-model",
            mlFeatureContractVersion="feature-contract-v2",
        ))) as path:
            with self.assertRaises(FeedbackDatasetValidationError):
                read_feedback_dataset_jsonl(path)

    def test_absentMlEvidenceRecordRemainsValidForPlatformEvaluation(self):
        reports = self._reports(record())

        self.assertNotIn("modelEvaluationSummary", reports)
        self.assertEqual("PLATFORM_RECOMMENDATION", reports["evaluationSummary"]["evaluationSubject"]["subjectType"])
        self.assertEqual(1, reports["evaluationSummary"]["qualityMetrics"]["datasetSummary"]["recordsEvaluated"])

    def test_modelSpecificEvaluationDoesNotChangePlatformMetrics(self):
        dataset = self._dataset(self._model_record("eval_11111111111111111111111111111111", MODEL_X), record())

        platform_only = build_feedback_dataset_evaluation_reports(dataset, generated_at=GENERATED_AT)
        with_model = build_feedback_dataset_evaluation_reports(dataset, generated_at=GENERATED_AT, model_identity=MODEL_X)

        self.assertEqual(platform_only["evaluationSummary"], with_model["evaluationSummary"])

    def test_fullEvidenceOutcomePopulationIsRetainedAndReconciled(self):
        records = (
            *(self._model_record(f"eval_{index:032d}", MODEL_X) for index in range(1, 5)),
            *(record(
                evaluationRecordId=f"eval_{index:032d}",
                transactionReference=f"txnref_{index:032d}",
                mlPredictionEvidenceStatus="MISSING_UNEXPECTEDLY",
                mlPredictionEvidenceOmissionReason="ML_ENGINE_UNAVAILABLE",
            ) for index in range(5, 8)),
            *(record(
                evaluationRecordId=f"eval_{index:032d}",
                transactionReference=f"txnref_{index:032d}",
                mlPredictionEvidenceStatus="IDENTITY_MISMATCH",
                mlPredictionEvidenceOmissionReason=None,
            ) for index in range(8, 10)),
            record(
                evaluationRecordId="eval_00000000000000000000000000000010",
                transactionReference="txnref_00000000000000000000000000000010",
                mlPredictionEvidenceStatus="MALFORMED",
                mlPredictionEvidenceOmissionReason="INVALID_SCORE",
            ),
        )

        reports = self._reports(*records, model_identity=MODEL_X)

        self.assertEqual(10, reports["evaluationSummary"]["qualityMetrics"]["datasetSummary"]["recordsEvaluated"])
        self.assertEqual({
            "recordsConsidered": 10,
            "recordsWithPredictionEvidence": 4,
            "recordsEvaluated": 4,
            "recordsExcludedIdentityMismatch": 0,
            "recordsExcludedSourceIdentityMismatch": 2,
            "recordsExcludedMissingPredictionEvidence": 0,
            "recordsExcludedUnexpectedMissingPredictionEvidence": 3,
            "recordsExcludedInvalidPredictionEvidence": 1,
        }, reports["modelEvaluationSummary"]["population"])
        self.assertEqual([
            INVALID_ML_EVIDENCE_PRESENT,
            MODEL_EVALUATION_PARTIAL_COVERAGE,
            SINGLE_CLASS_MODEL_LINEAGE_RECORDS,
            UNEXPECTED_ML_EVIDENCE_LOSS,
        ], reports["modelEvaluationSummary"]["warnings"])

    def test_sourceAndModelPopulationsRemainFullyReconciledWithDerivedWarnings(self):
        records = (
            self._model_record("eval_00000000000000000000000000000001", MODEL_X),
            self._model_record(
                "eval_00000000000000000000000000000002",
                MODEL_X,
                feedbackLabel="CONFIRMED_LEGITIMATE",
                evaluationLabel="NEGATIVE_LEGITIMATE",
            ),
            self._model_record("eval_00000000000000000000000000000003", MODEL_X),
            self._model_record(
                "eval_00000000000000000000000000000004",
                MODEL_X,
                feedbackLabel="CONFIRMED_LEGITIMATE",
                evaluationLabel="NEGATIVE_LEGITIMATE",
            ),
            *(record(
                evaluationRecordId=f"eval_{index:032d}",
                transactionReference=f"txnref_{index:032d}",
                mlPredictionEvidenceStatus="MISSING_UNEXPECTEDLY",
                mlPredictionEvidenceOmissionReason="ML_ENGINE_UNAVAILABLE",
            ) for index in range(5, 8)),
            record(
                evaluationRecordId="eval_00000000000000000000000000000008",
                transactionReference="txnref_00000000000000000000000000000008",
                mlPredictionEvidenceStatus="MALFORMED",
                mlPredictionEvidenceOmissionReason="INVALID_SCORE",
            ),
            record(
                evaluationRecordId="eval_00000000000000000000000000000009",
                transactionReference="txnref_00000000000000000000000000000009",
                mlPredictionEvidenceStatus="MALFORMED",
                mlPredictionEvidenceOmissionReason="IDENTITY_VALIDATION_FAILURE",
            ),
            record(
                evaluationRecordId="eval_00000000000000000000000000000010",
                transactionReference="txnref_00000000000000000000000000000010",
                mlPredictionEvidenceStatus="IDENTITY_MISMATCH",
                mlPredictionEvidenceOmissionReason=None,
            ),
        )
        payload = jsonl(*records, metadata_overrides={
            "rawRowsRead": 12,
            "recordsReturned": 10,
            "excludedUnresolvedCount": 1,
            "excludedGovernanceReviewCount": 1,
        })
        with jsonl_file(payload) as path:
            dataset = read_feedback_dataset_jsonl(path)

        summary = build_model_specific_evaluation_summary(dataset, MODEL_X, GENERATED_AT)

        self.assertEqual(12, summary["sourceDataset"]["rawRowsRead"])
        self.assertEqual(10, summary["sourceDataset"]["recordsReturned"])
        self.assertEqual(1, summary["sourceDataset"]["excludedUnresolvedCount"])
        self.assertEqual(1, summary["sourceDataset"]["excludedGovernanceReviewCount"])
        self.assertEqual({
            "recordsConsidered": 10,
            "recordsWithPredictionEvidence": 4,
            "recordsEvaluated": 4,
            "recordsExcludedIdentityMismatch": 0,
            "recordsExcludedSourceIdentityMismatch": 1,
            "recordsExcludedMissingPredictionEvidence": 0,
            "recordsExcludedUnexpectedMissingPredictionEvidence": 3,
            "recordsExcludedInvalidPredictionEvidence": 2,
        }, summary["population"])
        self.assertEqual([
            INVALID_ML_EVIDENCE_PRESENT,
            MODEL_EVALUATION_PARTIAL_COVERAGE,
            UNEXPECTED_ML_EVIDENCE_LOSS,
        ], summary["warnings"])

        summary["warnings"].remove(UNEXPECTED_ML_EVIDENCE_LOSS)
        with self.assertRaisesRegex(ValueError, "warnings must match evaluated population"):
            validate_model_evaluation_summary(summary)

    def test_identityValidationFailureIsMalformedWithoutPredictionEvidenceOrMismatch(self):
        summary = self._model_summary(record(
            mlPredictionEvidenceStatus="MALFORMED",
            mlPredictionEvidenceOmissionReason="IDENTITY_VALIDATION_FAILURE",
        ))

        self.assertEqual(0, summary["population"]["recordsWithPredictionEvidence"])
        self.assertEqual(0, summary["population"]["recordsExcludedIdentityMismatch"])
        self.assertEqual(0, summary["population"]["recordsExcludedSourceIdentityMismatch"])
        self.assertEqual(1, summary["population"]["recordsExcludedInvalidPredictionEvidence"])

    def test_sourceDatasetPreservesExactIdentityAndUpstreamPopulationAccounting(self):
        records = (
            self._model_record("eval_11111111111111111111111111111111", MODEL_X),
            self._model_record("eval_22222222222222222222222222222222", MODEL_X),
        )
        payload = jsonl(*records, metadata_overrides={
            "rawRowsRead": 7,
            "recordsReturned": 2,
            "excludedUnresolvedCount": 1,
            "excludedGovernanceReviewCount": 1,
            "skippedMissingRequiredFieldCount": 1,
            "skippedInvalidSourceRecordCount": 1,
            "truncated": True,
        })
        with jsonl_file(payload) as path:
            dataset = read_feedback_dataset_jsonl(path)

        summary = build_model_specific_evaluation_summary(dataset, MODEL_X, GENERATED_AT)

        self.assertEqual({
            "datasetVersion": "feedback-dataset-v2",
            "sha256": dataset.source_sha256,
            "rawRowsRead": 7,
            "recordsReturned": 2,
            "excludedUnresolvedCount": 1,
            "excludedGovernanceReviewCount": 1,
            "skippedMissingRequiredFieldCount": 1,
            "skippedInvalidSourceRecordCount": 1,
            "truncated": True,
        }, summary["sourceDataset"])
        self.assertEqual(
            summary["sourceDataset"]["recordsReturned"],
            summary["population"]["recordsConsidered"],
        )
        serialized_lineage = json.dumps(summary["sourceDataset"], sort_keys=True).lower()
        for forbidden in (
            "feedbackid",
            "transactionid",
            "sourceeventid",
            "correlationid",
            "customerid",
            "accountid",
        ):
            self.assertNotIn(forbidden, serialized_lineage)

    def test_sourceDatasetQualityLossProducesDeterministicWarnings(self):
        cases = (
            (
                SOURCE_DATASET_TRUNCATED,
                {"rawRowsRead": 3, "truncated": True},
            ),
            (
                SOURCE_DATASET_REQUIRED_FIELDS_MISSING,
                {"rawRowsRead": 3, "skippedMissingRequiredFieldCount": 1},
            ),
            (
                SOURCE_DATASET_INVALID_ROWS_SKIPPED,
                {"rawRowsRead": 3, "skippedInvalidSourceRecordCount": 1},
            ),
        )
        for expected_warning, metadata_overrides in cases:
            with self.subTest(expected_warning=expected_warning):
                summary = self._model_summary_with_metadata(metadata_overrides)

                self.assertEqual([expected_warning], summary["warnings"])
                self.assertIs(summary, validate_model_evaluation_summary(summary))

    def test_cleanSourceDatasetHasNoSourceQualityWarnings(self):
        summary = self._model_summary_with_metadata({})

        self.assertEqual([], summary["warnings"])

    def test_policyExclusionsAloneDoNotProduceSourceQualityWarnings(self):
        summary = self._model_summary_with_metadata({
            "rawRowsRead": 4,
            "excludedUnresolvedCount": 1,
            "excludedGovernanceReviewCount": 1,
        })

        self.assertEqual([], summary["warnings"])

    def test_sourceDatasetWarningsCannotContradictSourceMetadata(self):
        summary = self._model_summary_with_metadata({})
        summary["warnings"] = [SOURCE_DATASET_TRUNCATED]

        with self.assertRaisesRegex(ValueError, "warnings must match evaluated population"):
            validate_model_evaluation_summary(summary)

    def test_shadowMlIdentityVersionRemainsTheModelSpecificEvaluationSubject(self):
        summary = self._model_summary(
            self._model_record("eval_11111111111111111111111111111111", SHADOW_MODEL),
            requested=SHADOW_MODEL,
        )

        self.assertEqual("ml-shadow-2026-06-01", summary["evaluationSubject"]["modelVersion"])
        self.assertEqual(1, summary["population"]["recordsEvaluated"])

    def test_modelNameMismatchIsDetected(self):
        requested = ModelEvaluationIdentity("python-xgboost-fraud-model", MODEL_X.model_version, MODEL_X.feature_contract_version)
        summary = self._model_summary(self._model_record("eval_11111111111111111111111111111111", MODEL_X), requested=requested)

        self.assertEqual(0, summary["population"]["recordsEvaluated"])
        self.assertEqual(1, summary["population"]["recordsExcludedIdentityMismatch"])

    def test_modelVersionMismatchIsDetected(self):
        summary = self._model_summary(self._model_record("eval_11111111111111111111111111111111", MODEL_Y))

        self.assertEqual(0, summary["population"]["recordsEvaluated"])
        self.assertEqual(1, summary["population"]["recordsExcludedIdentityMismatch"])

    def test_featureContractVersionMismatchIsDetected(self):
        other_contract = ModelEvaluationIdentity(MODEL_X.model_name, MODEL_X.model_version, "feature-contract-v3")
        summary = self._model_summary(
            self._model_record("eval_11111111111111111111111111111111", other_contract)
        )

        self.assertEqual(0, summary["population"]["recordsEvaluated"])
        self.assertEqual(1, summary["population"]["recordsExcludedIdentityMismatch"])

    def test_zeroEligibleRecordsProducesUnavailableResultNotFakeMetrics(self):
        summary = self._model_summary(record())

        self.assertEqual(0, summary["population"]["recordsEvaluated"])
        self.assertFalse(summary["supportedMetrics"]["classBalance"]["available"])
        self.assertEqual(INSUFFICIENT_MODEL_LINEAGE_RECORDS, summary["supportedMetrics"]["classBalance"]["reason"])
        self.assertEqual(
            [
                INSUFFICIENT_MODEL_LINEAGE_RECORDS,
                MODEL_EVALUATION_PARTIAL_COVERAGE,
                MODEL_PREDICTION_SIGNAL_UNAVAILABLE,
            ],
            summary["warnings"],
        )
        self.assertIs(summary, validate_model_evaluation_summary(summary))

    def test_zeroEligibleRecordsMissingRequiredWarningIsRejected(self):
        for missing_warning in (
                INSUFFICIENT_MODEL_LINEAGE_RECORDS,
                MODEL_EVALUATION_PARTIAL_COVERAGE,
                MODEL_PREDICTION_SIGNAL_UNAVAILABLE,
        ):
            with self.subTest(missing_warning=missing_warning):
                summary = self._model_summary(record())
                summary["warnings"].remove(missing_warning)

                with self.assertRaisesRegex(ValueError, "warnings must match evaluated population"):
                    validate_model_evaluation_summary(summary)

    def test_positiveOnlyPopulationRequiresSingleClassWarning(self):
        summary = self._model_summary(self._model_record("eval_11111111111111111111111111111111", MODEL_X))

        self.assertEqual(
            [SINGLE_CLASS_MODEL_LINEAGE_RECORDS],
            summary["warnings"],
        )
        metrics = summary["supportedMetrics"]["mlPredictionMetrics"]
        self.assertFalse(metrics["falsePositiveRate"]["available"])
        self.assertEqual("NO_ACTUAL_NEGATIVES", metrics["falsePositiveRate"]["reason"])
        self.assertIs(summary, validate_model_evaluation_summary(summary))

        summary["warnings"].remove(SINGLE_CLASS_MODEL_LINEAGE_RECORDS)
        with self.assertRaisesRegex(ValueError, "warnings must match evaluated population"):
            validate_model_evaluation_summary(summary)

    def test_negativeOnlyPopulationRequiresSingleClassWarning(self):
        summary = self._model_summary(self._model_record(
            "eval_11111111111111111111111111111111",
            MODEL_X,
            feedbackLabel="CONFIRMED_LEGITIMATE",
            evaluationLabel="NEGATIVE_LEGITIMATE",
        ))

        self.assertEqual(
            [SINGLE_CLASS_MODEL_LINEAGE_RECORDS],
            summary["warnings"],
        )
        metrics = summary["supportedMetrics"]["mlPredictionMetrics"]
        self.assertFalse(metrics["recall"]["available"])
        self.assertFalse(metrics["truePositiveRate"]["available"])
        self.assertFalse(metrics["falseNegativeRate"]["available"])
        self.assertIs(summary, validate_model_evaluation_summary(summary))

        summary["warnings"].remove(SINGLE_CLASS_MODEL_LINEAGE_RECORDS)
        with self.assertRaisesRegex(ValueError, "warnings must match evaluated population"):
            validate_model_evaluation_summary(summary)

    def test_bothClassesWithPredictionSignalRequireNoWarning(self):
        summary = self._model_summary(
            self._model_record("eval_11111111111111111111111111111111", MODEL_X),
            self._model_record(
                "eval_22222222222222222222222222222222",
                MODEL_X,
                feedbackLabel="CONFIRMED_LEGITIMATE",
                evaluationLabel="NEGATIVE_LEGITIMATE",
            ),
        )

        self.assertEqual([], summary["warnings"])
        self.assertIs(summary, validate_model_evaluation_summary(summary))

        summary["warnings"].append(SINGLE_CLASS_MODEL_LINEAGE_RECORDS)
        with self.assertRaisesRegex(ValueError, "warnings must match evaluated population"):
            validate_model_evaluation_summary(summary)

    def test_warningSetRejectsDuplicatesUnknownCodesAndIncorrectOrdering(self):
        invalid_warning_sets = (
            ([MODEL_PREDICTION_SIGNAL_UNAVAILABLE, MODEL_PREDICTION_SIGNAL_UNAVAILABLE], "duplicates"),
            (["UNKNOWN_MODEL_WARNING"], "unsupported"),
            ([MODEL_PREDICTION_SIGNAL_UNAVAILABLE, INSUFFICIENT_MODEL_LINEAGE_RECORDS], "sorted"),
        )
        for warnings, expected_error in invalid_warning_sets:
            with self.subTest(warnings=warnings):
                summary = self._model_summary(record())
                summary["warnings"] = warnings

                with self.assertRaisesRegex(ValueError, expected_error):
                    validate_model_evaluation_summary(summary)

    def test_modelEvaluationWindowAcceptsOptionalAndChronologicalBoundaries(self):
        valid_windows = (
            {"timeBasis": "FEEDBACK_CREATED_AT"},
            {"timeBasis": "FEEDBACK_CREATED_AT", "fromInclusive": None, "toInclusive": None},
            {"timeBasis": "FEEDBACK_CREATED_AT", "fromInclusive": "2026-06-01T00:00:00Z"},
            {"timeBasis": "FEEDBACK_CREATED_AT", "toInclusive": "2026-06-09T23:59:59Z"},
            {
                "timeBasis": "FEEDBACK_CREATED_AT",
                "fromInclusive": "2026-06-01T00:00:00Z",
                "toInclusive": "2026-06-09T23:59:59Z",
            },
            {
                "timeBasis": "FEEDBACK_CREATED_AT",
                "fromInclusive": "2026-06-01T00:00:00Z",
                "toInclusive": "2026-06-01T00:00:00Z",
            },
            {
                "timeBasis": "FEEDBACK_CREATED_AT",
                "fromInclusive": "2026-06-01T00:00:00Z",
                "toInclusive": "2026-06-01T00:00:00.1Z",
            },
            {
                "timeBasis": "FEEDBACK_CREATED_AT",
                "fromInclusive": "2026-09-27T00:00:00.123456788Z",
                "toInclusive": "2026-09-27T00:00:00.123456789Z",
            },
        )
        for window in valid_windows:
            with self.subTest(window=window):
                summary = self._model_summary(record())
                summary["evaluationWindow"] = window
                self.assertIs(summary, validate_model_evaluation_summary(summary))

    def test_modelEvaluationWindowRejectsReversedMalformedAndNoncanonicalBoundaries(self):
        invalid_windows = (
            {
                "timeBasis": "FEEDBACK_CREATED_AT",
                "fromInclusive": "2026-06-10T00:00:00Z",
                "toInclusive": "2026-06-01T00:00:00Z",
            },
            {"timeBasis": "FEEDBACK_CREATED_AT", "fromInclusive": "not-a-timestamp"},
            {"timeBasis": "FEEDBACK_CREATED_AT", "fromInclusive": "2026-06-01T00:00:00"},
            {"timeBasis": "FEEDBACK_CREATED_AT", "fromInclusive": "2026-06-01T01:00:00+01:00"},
            {
                "timeBasis": "FEEDBACK_CREATED_AT",
                "fromInclusive": "2026-09-27T00:00:00.123456789Z",
                "toInclusive": "2026-09-27T00:00:00.123456788Z",
            },
        )
        for window in invalid_windows:
            with self.subTest(window=window):
                summary = self._model_summary(record())
                summary["evaluationWindow"] = window
                with self.assertRaises(ValueError):
                    validate_model_evaluation_summary(summary)

    def test_positiveNegativeCountsMaintainStrictIntegrity(self):
        summary = self._model_summary(
            self._model_record("eval_11111111111111111111111111111111", MODEL_X),
            self._model_record(
                "eval_22222222222222222222222222222222",
                MODEL_X,
                feedbackLabel="CONFIRMED_LEGITIMATE",
                evaluationLabel="NEGATIVE_LEGITIMATE",
            ),
            record(),
        )

        class_balance = summary["classBalance"]
        self.assertEqual(
            summary["population"]["recordsEvaluated"],
            class_balance["positiveClassCount"] + class_balance["negativeClassCount"],
        )

    def test_outputContainsNoPseudonymousRecordIdentifiers(self):
        summary = self._model_summary(
            self._model_record("eval_11111111111111111111111111111111", MODEL_X),
        )

        payload = report_json(summary)
        self.assertNotIn("evaluationRecordId", payload)
        self.assertNotIn("transactionReference", payload)
        self.assertNotIn("eval_", payload)
        self.assertNotIn("txnref_", payload)

    def test_modelSpecificOutputIsDeterministic(self):
        first = report_json(self._model_summary(self._model_record("eval_11111111111111111111111111111111", MODEL_X)))
        second = report_json(self._model_summary(self._model_record("eval_11111111111111111111111111111111", MODEL_X)))

        self.assertEqual(first, second)
        self.assertEqual(json.loads(first)["generatedAt"], GENERATED_AT)

    def test_mlPredictionMetricsAreExplicitlyUnavailableWithoutExactEvidence(self):
        summary = self._model_summary(record())

        self.assertFalse(summary["supportedMetrics"]["mlPredictionMetrics"]["available"])
        self.assertEqual(
            "MODEL_PREDICTION_SIGNAL_UNAVAILABLE",
            summary["supportedMetrics"]["mlPredictionMetrics"]["reason"],
        )

    def test_balancedPredictionMetricsUseExactMlRiskAndMatchHandCalculatedMatrix(self):
        summary = self._model_summary(
            self._model_record("eval_11111111111111111111111111111111", MODEL_X, mlPredictionRiskLevel="HIGH"),
            self._model_record(
                "eval_22222222222222222222222222222222",
                MODEL_X,
                feedbackLabel="CONFIRMED_LEGITIMATE",
                evaluationLabel="NEGATIVE_LEGITIMATE",
                mlPredictionRiskLevel="CRITICAL",
            ),
            self._model_record(
                "eval_33333333333333333333333333333333",
                MODEL_X,
                feedbackLabel="CONFIRMED_LEGITIMATE",
                evaluationLabel="NEGATIVE_LEGITIMATE",
                mlPredictionRiskLevel="LOW",
            ),
            self._model_record("eval_44444444444444444444444444444444", MODEL_X, mlPredictionRiskLevel="MEDIUM"),
        )

        metrics = summary["supportedMetrics"]["mlPredictionMetrics"]
        self.assertTrue(metrics["available"])
        self.assertEqual(RISK_CLASSIFICATION_POLICY, metrics["classificationPolicy"])
        self.assertEqual(
            {"truePositive": 1, "falsePositive": 1, "trueNegative": 1, "falseNegative": 1},
            {field: metrics[field] for field in ("truePositive", "falsePositive", "trueNegative", "falseNegative")},
        )
        for field in ("precision", "recall", "truePositiveRate", "falsePositiveRate", "falseNegativeRate"):
            self.assertEqual({"available": True, "reason": None, "value": 0.5}, metrics[field])

    def test_allPredictionsCorrect(self):
        summary = self._model_summary(
            self._model_record("eval_11111111111111111111111111111111", MODEL_X, mlPredictionRiskLevel="CRITICAL"),
            self._model_record(
                "eval_22222222222222222222222222222222",
                MODEL_X,
                feedbackLabel="CONFIRMED_LEGITIMATE",
                evaluationLabel="NEGATIVE_LEGITIMATE",
                mlPredictionRiskLevel="LOW",
            ),
        )

        metrics = summary["supportedMetrics"]["mlPredictionMetrics"]
        self.assertEqual((1, 0, 1, 0), self._confusion_counts(metrics))
        self.assertEqual(1.0, metrics["precision"]["value"])
        self.assertEqual(1.0, metrics["recall"]["value"])

    def test_allPredictionsWrong(self):
        summary = self._model_summary(
            self._model_record("eval_11111111111111111111111111111111", MODEL_X, mlPredictionRiskLevel="LOW"),
            self._model_record(
                "eval_22222222222222222222222222222222",
                MODEL_X,
                feedbackLabel="CONFIRMED_LEGITIMATE",
                evaluationLabel="NEGATIVE_LEGITIMATE",
                mlPredictionRiskLevel="HIGH",
            ),
        )

        metrics = summary["supportedMetrics"]["mlPredictionMetrics"]
        self.assertEqual((0, 1, 0, 1), self._confusion_counts(metrics))
        self.assertEqual(0.0, metrics["precision"]["value"])
        self.assertEqual(0.0, metrics["recall"]["value"])

    def test_modelMetricsIgnorePlatformDecisionFields(self):
        summary = self._model_summary(self._model_record(
            "eval_11111111111111111111111111111111",
            MODEL_X,
            mlPredictionRiskLevel="HIGH",
            fraudScore=0.0,
            riskLevel="LOW",
            alertRecommended=False,
        ))

        metrics = summary["supportedMetrics"]["mlPredictionMetrics"]
        self.assertEqual((1, 0, 0, 0), self._confusion_counts(metrics))

    def test_directEvaluatorRejectsMissingLineageAsInvalidPredictionEvidence(self):
        dataset = self._dataset(self._model_record("eval_11111111111111111111111111111111", MODEL_X))
        without_lineage = replace(
            dataset.records[0],
            ml_model_name=None,
            ml_model_version=None,
            ml_feature_contract_version=None,
        )

        summary = build_model_specific_evaluation_summary(
            replace(dataset, records=(without_lineage,)),
            MODEL_X,
            GENERATED_AT,
        )

        self.assertEqual(0, summary["population"]["recordsWithPredictionEvidence"])
        self.assertEqual(1, summary["population"]["recordsExcludedInvalidPredictionEvidence"])
        self.assertEqual(0, summary["population"]["recordsEvaluated"])

    def test_directEvaluatorAccountsForInvalidPredictionEvidenceDefensively(self):
        dataset = self._dataset(self._model_record("eval_11111111111111111111111111111111", MODEL_X))
        partial_evidence = replace(dataset.records[0], ml_prediction_score=None)

        summary = build_model_specific_evaluation_summary(
            replace(dataset, records=(partial_evidence,)),
            MODEL_X,
            GENERATED_AT,
        )

        self.assertEqual(1, summary["population"]["recordsExcludedInvalidPredictionEvidence"])
        self.assertEqual(0, summary["population"]["recordsWithPredictionEvidence"])
        self.assertEqual(0, summary["population"]["recordsEvaluated"])

    def test_directEvaluatorRejectsPartialOrUnsafeModelIdentityDefensively(self):
        dataset = self._dataset(self._model_record("eval_11111111111111111111111111111111", MODEL_X))
        invalid_records = (
            replace(dataset.records[0], ml_model_version=None),
            replace(dataset.records[0], ml_model_version="token-secret"),
        )

        for invalid_record in invalid_records:
            with self.subTest(model_version=invalid_record.ml_model_version):
                summary = build_model_specific_evaluation_summary(
                    replace(dataset, records=(invalid_record,)),
                    MODEL_X,
                    GENERATED_AT,
                )

                self.assertEqual(1, summary["population"]["recordsExcludedInvalidPredictionEvidence"])
                self.assertEqual(0, summary["population"]["recordsWithPredictionEvidence"])

    def test_directEvaluatorRejectsNoncanonicalPredictionScoreScaleDefensively(self):
        dataset = self._dataset(self._model_record("eval_11111111111111111111111111111111", MODEL_X))
        excessive_scale = replace(dataset.records[0], ml_prediction_score=0.12345)

        summary = build_model_specific_evaluation_summary(
            replace(dataset, records=(excessive_scale,)),
            MODEL_X,
            GENERATED_AT,
        )

        self.assertEqual(1, summary["population"]["recordsExcludedInvalidPredictionEvidence"])
        self.assertEqual(0, summary["population"]["recordsWithPredictionEvidence"])

    def test_mlScoreRankingMatchesHandCalculatedPrecisionAndRecall(self):
        summary = self._ranked_summary(
            (
                self._model_record(
                    "eval_11111111111111111111111111111111",
                    MODEL_X,
                    mlPredictionScore=0.9,
                    fraudScore=0.1,
                ),
                self._model_record(
                    "eval_22222222222222222222222222222222",
                    MODEL_X,
                    feedbackLabel="CONFIRMED_LEGITIMATE",
                    evaluationLabel="NEGATIVE_LEGITIMATE",
                    mlPredictionScore=0.8,
                    fraudScore=0.95,
                ),
                self._model_record(
                    "eval_33333333333333333333333333333333",
                    MODEL_X,
                    mlPredictionScore=0.7,
                    fraudScore=0.05,
                ),
                self._model_record(
                    "eval_44444444444444444444444444444444",
                    MODEL_X,
                    feedbackLabel="CONFIRMED_LEGITIMATE",
                    evaluationLabel="NEGATIVE_LEGITIMATE",
                    mlPredictionScore=0.6,
                    fraudScore=1.0,
                ),
            ),
            (1, 2, 3, 10),
        )

        metrics = summary["supportedMetrics"]["mlPredictionMetrics"]
        self.assertEqual(1.0, metrics["precisionAtK"]["1"]["value"]["value"])
        self.assertEqual(0.5, metrics["recallAtK"]["1"]["value"]["value"])
        self.assertEqual(0.5, metrics["precisionAtK"]["2"]["value"]["value"])
        self.assertEqual(0.5, metrics["recallAtK"]["2"]["value"]["value"])
        self.assertEqual(0.666667, metrics["precisionAtK"]["3"]["value"]["value"])
        self.assertEqual(1.0, metrics["recallAtK"]["3"]["value"]["value"])
        self.assertEqual(4, metrics["precisionAtK"]["10"]["actualK"])
        self.assertEqual(0.5, metrics["precisionAtK"]["10"]["value"]["value"])

    def test_mlScoreRankingTieBreakIsDeterministicByEvaluationRecordId(self):
        negative = self._model_record(
            "eval_11111111111111111111111111111111",
            MODEL_X,
            feedbackLabel="CONFIRMED_LEGITIMATE",
            evaluationLabel="NEGATIVE_LEGITIMATE",
            mlPredictionScore=0.5,
        )
        positive = self._model_record(
            "eval_22222222222222222222222222222222",
            MODEL_X,
            mlPredictionScore=0.5,
        )

        first = self._ranked_summary((positive, negative), (1,))
        second = self._ranked_summary((negative, positive), (1,))

        self.assertEqual(first["supportedMetrics"]["mlPredictionMetrics"], second["supportedMetrics"]["mlPredictionMetrics"])
        self.assertEqual(0.0, first["supportedMetrics"]["mlPredictionMetrics"]["precisionAtK"]["1"]["value"]["value"])

    def test_mlScoreRankingRejectsInvalidOrUnboundedK(self):
        records = (self._model_record("eval_11111111111111111111111111111111", MODEL_X),)
        for top_k_values in ((), (0,), (-1,), (MAX_DATASET_RECORDS + 1,), (1, 1), (True,)):
            with self.subTest(top_k_values=top_k_values):
                with self.assertRaises(ValueError):
                    self._ranked_summary(records, top_k_values)

    def test_mlScoreRankingIsUnavailableWithoutUsablePredictionEvidence(self):
        summary = self._ranked_summary((record(),), (1,))

        ranking = summary["supportedMetrics"]["mlPredictionMetrics"]
        self.assertEqual(1, summary["population"]["recordsExcludedMissingPredictionEvidence"])
        self.assertEqual(0, ranking["precisionAtK"]["1"]["actualK"])
        self.assertEqual("NO_SCORED_RECORDS", ranking["precisionAtK"]["1"]["value"]["reason"])
        self.assertEqual("NO_SCORED_RECORDS", ranking["recallAtK"]["1"]["value"]["reason"])

    def test_mlScoreRankingExcludesOtherModelIdentityEvenWithHigherScore(self):
        summary = self._ranked_summary(
            (
                self._model_record(
                    "eval_11111111111111111111111111111111",
                    MODEL_X,
                    feedbackLabel="CONFIRMED_LEGITIMATE",
                    evaluationLabel="NEGATIVE_LEGITIMATE",
                    mlPredictionScore=0.1,
                ),
                self._model_record(
                    "eval_22222222222222222222222222222222",
                    MODEL_Y,
                    mlPredictionScore=0.99,
                ),
            ),
            (1,),
        )

        ranking = summary["supportedMetrics"]["mlPredictionMetrics"]
        self.assertEqual(1, summary["population"]["recordsExcludedIdentityMismatch"])
        self.assertEqual(1, ranking["precisionAtK"]["1"]["actualK"])
        self.assertEqual(0.0, ranking["precisionAtK"]["1"]["value"]["value"])

    def test_modelEvaluationSummaryValidatorRejectsRootContractDrift(self):
        summary = self._model_summary(self._model_record("eval_11111111111111111111111111111111", MODEL_X))
        summary["unexpected"] = "field"

        with self.assertRaisesRegex(ValueError, "unsupported fields"):
            validate_model_evaluation_summary(summary)

    def test_modelEvaluationSummaryValidatorRejectsRetiredSourceDatasetVersionField(self):
        summary = self._model_summary(self._model_record("eval_11111111111111111111111111111111", MODEL_X))
        del summary["sourceDataset"]
        summary["sourceDatasetVersion"] = "feedback-dataset-v2"

        with self.assertRaisesRegex(ValueError, "sourceDatasetVersion"):
            validate_model_evaluation_summary(summary)

    def test_modelEvaluationSummaryValidatorRejectsInvalidSourceDatasetIdentity(self):
        summary = self._model_summary(self._model_record("eval_11111111111111111111111111111111", MODEL_X))
        summary["sourceDataset"]["sha256"] = "A" * 64

        with self.assertRaisesRegex(ValueError, "sourceDataset sha256 must be lowercase hex"):
            validate_model_evaluation_summary(summary)

    def test_modelEvaluationSummaryValidatorRejectsUnknownSourceDatasetField(self):
        summary = self._model_summary(self._model_record("eval_11111111111111111111111111111111", MODEL_X))
        summary["sourceDataset"]["unexpected"] = "value"

        with self.assertRaisesRegex(ValueError, "sourceDataset contains unsupported fields"):
            validate_model_evaluation_summary(summary)

    def test_modelEvaluationSummaryValidatorRejectsBrokenSourcePopulationAccounting(self):
        summary = self._model_summary(self._model_record("eval_11111111111111111111111111111111", MODEL_X))
        summary["sourceDataset"]["excludedUnresolvedCount"] = 1

        with self.assertRaisesRegex(ValueError, "sourceDataset population counts must reconcile"):
            validate_model_evaluation_summary(summary)

    def test_modelEvaluationSummaryValidatorReconcilesSourceAndModelPopulations(self):
        summary = self._model_summary(self._model_record("eval_11111111111111111111111111111111", MODEL_X))
        summary["sourceDataset"]["recordsReturned"] = 0
        summary["sourceDataset"]["rawRowsRead"] = 0

        with self.assertRaisesRegex(ValueError, "recordsConsidered must match source dataset recordsReturned"):
            validate_model_evaluation_summary(summary)

    def test_modelEvaluationSummaryValidatorRejectsBrokenPopulationAccounting(self):
        summary = self._model_summary(
            self._model_record("eval_11111111111111111111111111111111", MODEL_X),
            record(),
        )
        summary["population"]["recordsConsidered"] = 999

        with self.assertRaisesRegex(ValueError, "population counts"):
            validate_model_evaluation_summary(summary)

    def test_modelEvaluationSummaryValidatorRejectsBrokenClassBalanceAccounting(self):
        summary = self._model_summary(self._model_record("eval_11111111111111111111111111111111", MODEL_X))
        summary["classBalance"]["positiveClassCount"] = 0

        with self.assertRaisesRegex(ValueError, "class balance"):
            validate_model_evaluation_summary(summary)

    def test_modelEvaluationSummaryValidatorAcceptsBoundedCounts(self):
        cases = (
            (0, 0, 0, 0, 0),
            (MAX_DATASET_RECORDS, 0, 0, 0, 0),
            (MAX_DATASET_RECORDS, 0, MAX_DATASET_RECORDS, 0, 0),
            (MAX_DATASET_RECORDS, MAX_DATASET_RECORDS, 0, MAX_DATASET_RECORDS, 0),
            (MAX_DATASET_RECORDS, MAX_DATASET_RECORDS, 0, 0, MAX_DATASET_RECORDS),
        )

        for accounting in cases:
            with self.subTest(accounting=accounting):
                summary = self._model_summary(
                    self._model_record("eval_11111111111111111111111111111111", MODEL_X)
                )
                self._set_accounting(summary, *accounting)

                validate_model_evaluation_summary(summary)

    def test_modelEvaluationSummaryValidatorRejectsEveryOutOfRangeCount(self):
        locations = (
            ("population", "recordsConsidered"),
            ("population", "recordsWithPredictionEvidence"),
            ("population", "recordsEvaluated"),
            ("population", "recordsExcludedIdentityMismatch"),
            ("population", "recordsExcludedSourceIdentityMismatch"),
            ("population", "recordsExcludedMissingPredictionEvidence"),
            ("population", "recordsExcludedUnexpectedMissingPredictionEvidence"),
            ("population", "recordsExcludedInvalidPredictionEvidence"),
            ("classBalance", "positiveClassCount"),
            ("classBalance", "negativeClassCount"),
        )
        invalid_values = (-1, MAX_DATASET_RECORDS + 1, True, 10 ** 100)

        for section, field in locations:
            for invalid_value in invalid_values:
                with self.subTest(field=field, invalid_value=invalid_value):
                    summary = self._model_summary(
                        self._model_record("eval_11111111111111111111111111111111", MODEL_X)
                    )
                    self._set_accounting(summary, 0, 0, 0, 0, 0)
                    summary[section][field] = invalid_value

                    with self.assertRaisesRegex(
                            ValueError,
                            rf"{section}\.{field} must be an integer between 0 and {MAX_DATASET_RECORDS}",
                    ):
                        validate_model_evaluation_summary(summary)

    def test_modelEvaluationSummaryValidatorRejectsReconciledOversizedPopulation(self):
        summary = self._model_summary(
            self._model_record("eval_11111111111111111111111111111111", MODEL_X)
        )
        self._set_accounting(
            summary,
            MAX_DATASET_RECORDS + 1,
            MAX_DATASET_RECORDS + 1,
            0,
            MAX_DATASET_RECORDS,
            1,
        )

        with self.assertRaisesRegex(ValueError, f"between 0 and {MAX_DATASET_RECORDS}"):
            validate_model_evaluation_summary(summary)

    def test_rulesVsMlDisagreementClassifiesAllFourSameOccurrenceOutcomes(self):
        summary = self._model_summary(
            self._model_record(
                "eval_11111111111111111111111111111111",
                MODEL_X,
                mlPredictionRiskLevel="HIGH",
                rulesRiskLevel="LOW",
            ),
            self._model_record(
                "eval_22222222222222222222222222222222",
                MODEL_X,
                mlPredictionRiskLevel="LOW",
                mlPredictionScore=0.2,
                rulesRiskLevel="HIGH",
            ),
            self._model_record(
                "eval_33333333333333333333333333333333",
                MODEL_X,
                mlPredictionRiskLevel="CRITICAL",
                rulesRiskLevel="HIGH",
            ),
            self._model_record(
                "eval_44444444444444444444444444444444",
                MODEL_X,
                mlPredictionRiskLevel="MEDIUM",
                mlPredictionScore=0.4,
                rulesRiskLevel="LOW",
            ),
        )

        disagreement = summary["supportedMetrics"]["rulesVsMlDisagreement"]
        self.assertEqual(4, disagreement["recordsCompared"])
        self.assertEqual({
            "BOTH_HIGH": 1,
            "BOTH_LOW": 1,
            "ML_HIGH_RULES_LOW": 1,
            "RULES_HIGH_ML_LOW": 1,
        }, disagreement["categoryCounts"])

    def test_rulesVsMlDisagreementKeepsUnavailableSignalsOutOfLowRisk(self):
        summary = self._model_summary(
            self._model_record(
                "eval_11111111111111111111111111111111",
                MODEL_X,
                rulesEvidenceStatus="UNAVAILABLE",
                rulesRiskLevel=None,
            ),
            record(
                evaluationRecordId="eval_22222222222222222222222222222222",
                transactionReference="txnref_22222222222222222222222222222222",
                rulesEvidenceStatus="AVAILABLE",
                rulesRiskLevel="LOW",
            ),
        )

        disagreement = summary["supportedMetrics"]["rulesVsMlDisagreement"]
        self.assertFalse(disagreement["available"])
        self.assertEqual(RULES_SIGNAL_UNAVAILABLE, disagreement["reason"])
        self.assertEqual(1, disagreement["recordsWithExactMlEvidence"])
        self.assertEqual(0, disagreement["recordsCompared"])
        self.assertEqual(1, disagreement["recordsExcludedRulesEvidenceUnavailable"])
        self.assertEqual(0, sum(disagreement["categoryCounts"].values()))

    def test_rulesVsMlDisagreementDoesNotCombineRulesSignalFromAnotherModelOccurrence(self):
        summary = self._model_summary(
            self._model_record(
                "eval_11111111111111111111111111111111",
                MODEL_X,
                rulesEvidenceStatus="UNAVAILABLE",
                rulesRiskLevel=None,
            ),
            self._model_record(
                "eval_22222222222222222222222222222222",
                MODEL_Y,
                rulesRiskLevel="LOW",
            ),
        )

        disagreement = summary["supportedMetrics"]["rulesVsMlDisagreement"]
        self.assertEqual(1, summary["population"]["recordsExcludedIdentityMismatch"])
        self.assertEqual(0, disagreement["recordsCompared"])
        self.assertEqual(1, disagreement["recordsExcludedRulesEvidenceUnavailable"])

    def test_rulesVsMlDisagreementValidatorRejectsNonBooleanAvailability(self):
        summary = self._model_summary(
            self._model_record("eval_11111111111111111111111111111111", MODEL_X)
        )
        summary["supportedMetrics"]["rulesVsMlDisagreement"]["available"] = 1

        with self.assertRaisesRegex(ValueError, "available must be boolean"):
            validate_model_evaluation_summary(summary)

    def test_requestedModelIdentityRejectsSecretLikeValues(self):
        with self.assertRaises(ValueError):
            ModelEvaluationIdentity("python-logistic-fraud-model", "tokenized-model-version", "feature-contract-v2")
        with self.assertRaises(ValueError):
            ModelEvaluationIdentity("python-logistic-fraud-model", "2026-06-25.v1", "secret-feature-contract")

    def test_requestedModelIdentityRejectsUnsafeSyntaxAndBounds(self):
        with self.assertRaises(ValueError):
            ModelEvaluationIdentity("python/logistic-fraud-model", "2026-06-25.v1", "feature-contract-v2")
        with self.assertRaises(ValueError):
            ModelEvaluationIdentity("python-logistic-fraud-model", "2026-06-25:v1", "feature-contract-v2")
        with self.assertRaises(ValueError):
            ModelEvaluationIdentity("python-logistic-fraud-model", "2026-06-25.v1", "feature contract v2")
        with self.assertRaises(ValueError):
            ModelEvaluationIdentity("python-logistic-fraud-model", "2026-06-25.v1", "f" * 97)

    def _model_summary(self, *records, requested=MODEL_X):
        return self._reports(*records, model_identity=requested)["modelEvaluationSummary"]

    def _model_summary_with_metadata(self, metadata_overrides):
        records = (
            self._model_record("eval_11111111111111111111111111111111", MODEL_X),
            self._model_record(
                "eval_22222222222222222222222222222222",
                MODEL_X,
                feedbackLabel="CONFIRMED_LEGITIMATE",
                evaluationLabel="NEGATIVE_LEGITIMATE",
            ),
        )
        with jsonl_file(jsonl(*records, metadata_overrides=metadata_overrides)) as path:
            dataset = read_feedback_dataset_jsonl(path)
        return build_model_specific_evaluation_summary(dataset, MODEL_X, GENERATED_AT)

    def _ranked_summary(self, records, top_k_values):
        return build_model_specific_evaluation_summary(
            self._dataset(*records),
            MODEL_X,
            GENERATED_AT,
            top_k_values=top_k_values,
        )

    @staticmethod
    def _set_accounting(summary, considered, evaluated, mismatch, positives, negatives):
        missing_prediction = considered - evaluated - mismatch
        summary["population"] = {
            "recordsConsidered": considered,
            "recordsWithPredictionEvidence": evaluated + mismatch,
            "recordsEvaluated": evaluated,
            "recordsExcludedIdentityMismatch": mismatch,
            "recordsExcludedSourceIdentityMismatch": 0,
            "recordsExcludedMissingPredictionEvidence": missing_prediction,
            "recordsExcludedUnexpectedMissingPredictionEvidence": 0,
            "recordsExcludedInvalidPredictionEvidence": 0,
        }
        summary["classBalance"] = {
            "positiveClassCount": positives,
            "negativeClassCount": negatives,
        }
        summary["sourceDataset"].update({
            "rawRowsRead": considered,
            "recordsReturned": considered,
            "excludedUnresolvedCount": 0,
            "excludedGovernanceReviewCount": 0,
            "skippedMissingRequiredFieldCount": 0,
            "skippedInvalidSourceRecordCount": 0,
            "truncated": False,
        })
        summary["supportedMetrics"]["classBalance"] = {
            "available": bool(evaluated),
            "reason": None if evaluated else INSUFFICIENT_MODEL_LINEAGE_RECORDS,
        }
        prediction_metrics = build_binary_classification_metrics_from_counts(positives, 0, negatives, 0)
        precision_at_k = {}
        recall_at_k = {}
        for top_k in DEFAULT_TOP_K_VALUES:
            actual_k = min(top_k, evaluated)
            precision_row, recall_row = build_ranked_metric_rows(
                min(positives, actual_k),
                positives,
                top_k,
                actual_k,
                evaluated > 0,
            )
            precision_at_k[str(top_k)] = precision_row
            recall_at_k[str(top_k)] = recall_row
        prediction_metrics.update({
            "available": bool(evaluated),
            "reason": None if evaluated else MODEL_PREDICTION_SIGNAL_UNAVAILABLE,
            "classificationPolicy": RISK_CLASSIFICATION_POLICY,
            "rankingPolicy": ML_SCORE_RANKING_POLICY,
            "precisionAtK": precision_at_k,
            "recallAtK": recall_at_k,
        })
        summary["supportedMetrics"]["mlPredictionMetrics"] = prediction_metrics
        summary["supportedMetrics"]["rulesVsMlDisagreement"] = {
            "available": bool(evaluated),
            "reason": None if evaluated else RULES_SIGNAL_UNAVAILABLE,
            "classificationPolicy": RISK_CLASSIFICATION_POLICY,
            "recordsWithExactMlEvidence": evaluated,
            "recordsCompared": evaluated,
            "recordsExcludedRulesEvidenceUnavailable": 0,
            "categoryCounts": {
                "BOTH_HIGH": evaluated,
                "BOTH_LOW": 0,
                "ML_HIGH_RULES_LOW": 0,
                "RULES_HIGH_ML_LOW": 0,
            },
        }
        warnings = []
        if evaluated == 0:
            warnings.extend((INSUFFICIENT_MODEL_LINEAGE_RECORDS, MODEL_PREDICTION_SIGNAL_UNAVAILABLE))
        elif positives == 0 or negatives == 0:
            warnings.append(SINGLE_CLASS_MODEL_LINEAGE_RECORDS)
        if evaluated < considered:
            warnings.append(MODEL_EVALUATION_PARTIAL_COVERAGE)
        summary["warnings"] = sorted(warnings)

    @staticmethod
    def _confusion_counts(metrics):
        return tuple(metrics[field] for field in (
            "truePositive",
            "falsePositive",
            "trueNegative",
            "falseNegative",
        ))

    def _reports(self, *records, model_identity=None):
        return build_feedback_dataset_evaluation_reports(
            self._dataset(*records),
            generated_at=GENERATED_AT,
            model_identity=model_identity,
        )

    def _dataset(self, *records):
        with jsonl_file(jsonl(*records)) as path:
            return read_feedback_dataset_jsonl(path)

    def _model_record(self, evaluation_record_id, identity, **overrides):
        return record(
            evaluationRecordId=evaluation_record_id,
            transactionReference="txnref_" + evaluation_record_id.removeprefix("eval_"),
            mlModelName=identity.model_name,
            mlModelVersion=identity.model_version,
            mlFeatureContractVersion=identity.feature_contract_version,
            **overrides,
        )


if __name__ == "__main__":
    unittest.main()
