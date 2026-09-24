import json
import unittest

from offline_evaluation.feedback_dataset_evaluation.dataset_reader import read_feedback_dataset_jsonl
from offline_evaluation.feedback_dataset_evaluation.dataset_schema import FeedbackDatasetValidationError
from offline_evaluation.feedback_dataset_evaluation.evaluation_runner import build_feedback_dataset_evaluation_reports
from offline_evaluation.feedback_dataset_evaluation.model_evaluation import (
    MODEL_EVALUATION_REPORT_TYPE,
    ModelEvaluationIdentity,
    validate_model_evaluation_summary,
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
        self.assertEqual(0, summary["population"]["recordsExcludedMissingLineage"])
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

    def test_unknownLineageIsNotAttributedToRequestedModel(self):
        summary = self._model_summary(record(), self._model_record("eval_22222222222222222222222222222222", MODEL_X))

        self.assertEqual(1, summary["population"]["recordsEvaluated"])
        self.assertEqual(1, summary["population"]["recordsExcludedMissingLineage"])
        self.assertEqual("MODEL_LINEAGE_UNAVAILABLE", summary["lineagePolicy"]["missingLineageReason"])

    def test_partialLineageFailsInsteadOfBecomingUnavailableLineage(self):
        with jsonl_file(jsonl(record(
            mlModelName="python-logistic-fraud-model",
            mlFeatureContractVersion="feature-contract-v2",
        ))) as path:
            with self.assertRaises(FeedbackDatasetValidationError):
                read_feedback_dataset_jsonl(path)

    def test_legacyDatasetRemainsValidForPlatformEvaluation(self):
        reports = self._reports(record())

        self.assertNotIn("modelEvaluationSummary", reports)
        self.assertEqual("PLATFORM_RECOMMENDATION", reports["evaluationSummary"]["evaluationSubject"]["subjectType"])
        self.assertEqual(1, reports["evaluationSummary"]["qualityMetrics"]["datasetSummary"]["recordsEvaluated"])

    def test_modelSpecificEvaluationDoesNotChangePlatformMetrics(self):
        dataset = self._dataset(self._model_record("eval_11111111111111111111111111111111", MODEL_X), record())

        platform_only = build_feedback_dataset_evaluation_reports(dataset, generated_at=GENERATED_AT)
        with_model = build_feedback_dataset_evaluation_reports(dataset, generated_at=GENERATED_AT, model_identity=MODEL_X)

        self.assertEqual(platform_only["evaluationSummary"], with_model["evaluationSummary"])

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
        self.assertEqual("INSUFFICIENT_MODEL_LINEAGE_RECORDS", summary["supportedMetrics"]["classBalance"]["reason"])
        self.assertIn("INSUFFICIENT_MODEL_LINEAGE_RECORDS", summary["warnings"])

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

    def test_mlPredictionMetricsAreExplicitlyUnavailable(self):
        summary = self._model_summary(self._model_record("eval_11111111111111111111111111111111", MODEL_X))

        self.assertFalse(summary["supportedMetrics"]["mlPredictionMetrics"]["available"])
        self.assertEqual(
            "MODEL_PREDICTION_SIGNAL_UNAVAILABLE",
            summary["supportedMetrics"]["mlPredictionMetrics"]["reason"],
        )

    def test_modelEvaluationSummaryValidatorRejectsRootContractDrift(self):
        summary = self._model_summary(self._model_record("eval_11111111111111111111111111111111", MODEL_X))
        summary["unexpected"] = "field"

        with self.assertRaisesRegex(ValueError, "unsupported fields"):
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
