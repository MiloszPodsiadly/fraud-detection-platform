import copy
import unittest

from offline_evaluation.feedback_dataset_evaluation.dataset_reader import read_feedback_dataset_jsonl
from offline_evaluation.feedback_dataset_evaluation.model_card.schema import (
    EVALUATION_ARTIFACT_INTEGRITY_VERIFIED,
    EVALUATION_AVAILABLE,
    EVALUATION_NOT_AVAILABLE,
    EVALUATION_NOT_CONFIGURED,
    MODEL_CARD_SCHEMA_VERSION,
    MODEL_CARD_TYPE,
    MODEL_ARTIFACT_BYTES_VERIFIED,
    NO_AUTHORITY,
    INTENDED_USE,
    NOT_INTENDED_USE,
    PROMOTION_STATE,
    USE_LIMITATIONS,
    VERIFIED_USAGE_MODES,
    ModelCardValidationError,
    validate_model_card,
)
from offline_evaluation.feedback_dataset_evaluation.model_evaluation import (
    ModelEvaluationIdentity,
    build_model_specific_evaluation_summary,
)
from offline_evaluation.feedback_dataset_evaluation.model_evaluation_artifact_set import (
    ModelEvaluationArtifactVerificationLevel,
)
from offline_evaluation.feedback_dataset_evaluation.report_contract import (
    MODEL_EVALUATION_ARTIFACT_SET_VERSION,
    MODEL_EVALUATION_REPORT_TYPE,
)

try:
    from feedback_dataset_evaluation.feedback_dataset_fixtures import GENERATED_AT, jsonl, jsonl_file, record
except ModuleNotFoundError:
    from feedback_dataset_fixtures import GENERATED_AT, jsonl, jsonl_file, record


MODEL = ModelEvaluationIdentity(
    "python-logistic-fraud-model",
    "2026-06-25.release",
    "feature-contract-v2",
    "a" * 64,
)
CARD_GENERATED_AT = "2026-06-10T00:00:01Z"


class ModelCardSchemaTest(unittest.TestCase):
    def test_accepts_canonical_exact_artifact_card_without_recomputing_metrics(self):
        card = self._available_card()
        expected_summary = copy.deepcopy(card["evaluationSummary"])

        validated = validate_model_card(card)

        self.assertEqual(expected_summary, validated["evaluationSummary"])
        self.assertIsNot(card, validated)
        self.assertIsNot(card["evaluationSummary"], validated["evaluationSummary"])
        self.assertEqual(PROMOTION_STATE, validated["promotionState"])
        self.assertEqual(VERIFIED_USAGE_MODES, validated["verifiedUsageBoundary"]["allowedModes"])
        self.assertEqual(INTENDED_USE, validated["verifiedUsageBoundary"]["intendedUse"])
        self.assertEqual(NOT_INTENDED_USE, validated["verifiedUsageBoundary"]["notIntendedUse"])
        self.assertEqual(USE_LIMITATIONS, validated["verifiedUsageBoundary"]["useLimitations"])
        for authority in (
            "runtimeDecisionAuthority",
            "promotionAuthority",
            "thresholdChangeAuthority",
            "paymentAuthorizationAuthority",
            "workflowAuthority",
        ):
            self.assertEqual(NO_AUTHORITY, validated["verifiedUsageBoundary"][authority])

    def test_preserves_claim_only_dataset_verification_without_elevation(self):
        card = self._available_card()
        card["evaluationEvidence"]["sourceDatasetVerificationLevel"] = (
            ModelEvaluationArtifactVerificationLevel.CLAIM_ONLY.value
        )

        validated = validate_model_card(card)

        self.assertEqual(
            ModelEvaluationArtifactVerificationLevel.CLAIM_ONLY.value,
            validated["evaluationEvidence"]["sourceDatasetVerificationLevel"],
        )

    def test_accepts_explicit_not_configured_evaluation(self):
        card = self._available_card()
        card["evaluationEvidence"] = {
            "status": EVALUATION_NOT_AVAILABLE,
            "reason": EVALUATION_NOT_CONFIGURED,
        }
        card["evaluationSummary"] = None

        validated = validate_model_card(card)

        self.assertIsNone(validated["evaluationSummary"])

    def test_configured_evaluation_cannot_masquerade_as_not_configured(self):
        card = self._available_card()
        card["evaluationEvidence"] = {
            "status": EVALUATION_NOT_AVAILABLE,
            "reason": EVALUATION_NOT_CONFIGURED,
            "manifestSha256": "b" * 64,
        }
        card["evaluationSummary"] = None

        with self.assertRaisesRegex(ModelCardValidationError, "unsupported fields"):
            validate_model_card(card)

    def test_rejects_unknown_root_fields_and_promotion_claims(self):
        for field, value in (("unknown", True), ("approvedFor", ["PRODUCTION"]), ("champion", True)):
            with self.subTest(field=field):
                card = self._available_card()
                card[field] = value
                with self.assertRaises(ModelCardValidationError):
                    validate_model_card(card)

    def test_rejects_malformed_or_incomplete_exact_identity(self):
        cases = (
            ("subjectType", "MODEL"),
            ("modelName", "secret-model"),
            ("modelVersion", ""),
            ("featureContractVersion", "feature contract v2"),
            ("modelArtifactSha256", "A" * 64),
            ("identityCompleteness", "PARTIAL"),
        )
        for field, value in cases:
            with self.subTest(field=field):
                card = self._available_card()
                card["subject"][field] = value
                with self.assertRaises(ModelCardValidationError):
                    validate_model_card(card)

    def test_rejects_model_byte_verification_not_bound_to_subject(self):
        card = self._available_card()
        card["modelArtifactVerification"]["sha256"] = "c" * 64

        with self.assertRaisesRegex(ModelCardValidationError, "match the exact subject digest"):
            validate_model_card(card)

    def test_rejects_unverified_model_artifact_bytes(self):
        card = self._available_card()
        card["modelArtifactVerification"]["status"] = "CLAIM_ONLY"

        with self.assertRaisesRegex(ModelCardValidationError, "status unsupported"):
            validate_model_card(card)

    def test_rejects_evaluation_identity_mismatch(self):
        card = self._available_card()
        card["evaluationSummary"]["evaluationSubject"]["modelArtifactSha256"] = "c" * 64

        with self.assertRaises(ModelCardValidationError):
            validate_model_card(card)

    def test_rejects_malformed_evaluation_instead_of_treating_it_as_absent(self):
        card = self._available_card()
        card["evaluationSummary"]["population"]["recordsEvaluated"] = 99

        with self.assertRaisesRegex(ModelCardValidationError, "evaluationSummary invalid"):
            validate_model_card(card)

    def test_rejects_evaluation_provenance_drift(self):
        cases = (
            ("reportType", "OTHER_REPORT"),
            ("artifactSetVersion", "other-artifact-set"),
            ("reportGeneratedAt", "2026-06-10T00:00:00+00:00"),
            ("manifestSha256", "B" * 64),
            ("integrityVerification", "CLAIM_ONLY"),
            ("sourceDatasetVerificationLevel", "VERIFIED"),
        )
        for field, value in cases:
            with self.subTest(field=field):
                card = self._available_card()
                card["evaluationEvidence"][field] = value
                with self.assertRaises(ModelCardValidationError):
                    validate_model_card(card)

    def test_rejects_report_timestamp_mismatch_and_time_travel(self):
        card = self._available_card()
        card["evaluationEvidence"]["reportGeneratedAt"] = "2026-06-09T23:59:59Z"
        with self.assertRaisesRegex(ModelCardValidationError, "must match"):
            validate_model_card(card)

        card = self._available_card()
        card["generatedAt"] = "2026-06-09T23:59:59Z"
        with self.assertRaisesRegex(ModelCardValidationError, "must not precede"):
            validate_model_card(card)

    def test_missing_metric_must_remain_explicitly_unavailable(self):
        card = self._available_card(with_prediction_evidence=False)
        metrics = card["evaluationSummary"]["supportedMetrics"]["mlPredictionMetrics"]
        self.assertFalse(metrics["available"])
        self.assertIsNotNone(metrics["reason"])
        validated_metrics = validate_model_card(card)["evaluationSummary"]["supportedMetrics"]["mlPredictionMetrics"]
        self.assertIs(validated_metrics["available"], False)

        del metrics["precision"]
        with self.assertRaisesRegex(ModelCardValidationError, "evaluationSummary invalid"):
            validate_model_card(card)

    def test_rejects_nonfinite_sensitive_and_excessive_content(self):
        card = self._available_card()
        card["evaluationSummary"]["supportedMetrics"]["mlPredictionMetrics"]["precision"]["value"] = float("nan")
        with self.assertRaisesRegex(ModelCardValidationError, "strict finite JSON"):
            validate_model_card(card)

        card = self._available_card()
        card["evaluationSummary"]["transactionId"] = "txnref_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        with self.assertRaises(ModelCardValidationError):
            validate_model_card(card)

        card = self._available_card()
        card["oversized"] = "x" * 524_288
        with self.assertRaisesRegex(ModelCardValidationError, "maximum payload size"):
            validate_model_card(card)

    def test_rejects_weakened_usage_or_promotion_boundaries(self):
        card = self._available_card()
        card["promotionState"] = "CHAMPION"
        with self.assertRaises(ModelCardValidationError):
            validate_model_card(card)

        card = self._available_card()
        card["verifiedUsageBoundary"]["allowedModes"] = ["ML"]
        with self.assertRaisesRegex(ModelCardValidationError, "verifiedUsageBoundary unsupported"):
            validate_model_card(card)

        for field in ("intendedUse", "notIntendedUse", "useLimitations"):
            with self.subTest(field=field):
                card = self._available_card()
                del card["verifiedUsageBoundary"][field]
                with self.assertRaisesRegex(ModelCardValidationError, "missing required fields"):
                    validate_model_card(card)

        card = self._available_card()
        card["verifiedUsageBoundary"]["intendedUse"][0] = "[unsafe](https://example.invalid)"
        with self.assertRaisesRegex(ModelCardValidationError, "verifiedUsageBoundary unsupported"):
            validate_model_card(card)

    def _available_card(self, *, with_prediction_evidence: bool = True):
        summary = self._summary(with_prediction_evidence=with_prediction_evidence)
        return {
            "schemaVersion": MODEL_CARD_SCHEMA_VERSION,
            "cardType": MODEL_CARD_TYPE,
            "generatedAt": CARD_GENERATED_AT,
            "subject": MODEL.as_subject(),
            "promotionState": PROMOTION_STATE,
            "modelArtifactVerification": {
                "status": MODEL_ARTIFACT_BYTES_VERIFIED,
                "sha256": MODEL.model_artifact_sha256,
            },
            "evaluationEvidence": {
                "status": EVALUATION_AVAILABLE,
                "reportType": MODEL_EVALUATION_REPORT_TYPE,
                "artifactSetVersion": MODEL_EVALUATION_ARTIFACT_SET_VERSION,
                "reportGeneratedAt": GENERATED_AT,
                "manifestSha256": "b" * 64,
                "integrityVerification": EVALUATION_ARTIFACT_INTEGRITY_VERIFIED,
                "sourceDatasetVerificationLevel": (
                    ModelEvaluationArtifactVerificationLevel.VERIFIED_AGAINST_SOURCE_BYTES.value
                ),
            },
            "evaluationSummary": summary,
            "verifiedUsageBoundary": {
                "allowedModes": list(VERIFIED_USAGE_MODES),
                "intendedUse": list(INTENDED_USE),
                "notIntendedUse": list(NOT_INTENDED_USE),
                "useLimitations": list(USE_LIMITATIONS),
                "runtimeDecisionAuthority": NO_AUTHORITY,
                "promotionAuthority": NO_AUTHORITY,
                "thresholdChangeAuthority": NO_AUTHORITY,
                "paymentAuthorizationAuthority": NO_AUTHORITY,
                "workflowAuthority": NO_AUTHORITY,
            },
        }

    @staticmethod
    def _summary(*, with_prediction_evidence: bool):
        model_record = record(
            mlModelName=MODEL.model_name,
            mlModelVersion=MODEL.model_version,
            mlFeatureContractVersion=MODEL.feature_contract_version,
            mlModelArtifactSha256=MODEL.model_artifact_sha256,
        ) if with_prediction_evidence else record()
        with jsonl_file(jsonl(model_record)) as path:
            dataset = read_feedback_dataset_jsonl(path)
        return build_model_specific_evaluation_summary(dataset, MODEL, GENERATED_AT)


if __name__ == "__main__":
    unittest.main()
