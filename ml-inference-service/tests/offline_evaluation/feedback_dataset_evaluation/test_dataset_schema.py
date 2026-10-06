import json
import unittest

from offline_evaluation.feedback_dataset_evaluation.dataset_reader import read_feedback_dataset_jsonl
from offline_evaluation.feedback_dataset_evaluation.dataset_schema import (
    FeedbackDatasetFormatError,
    FeedbackDatasetValidationError,
    evaluation_label_value,
)
try:
    from feedback_dataset_evaluation.feedback_dataset_fixtures import jsonl, jsonl_file, metadata, record
except ModuleNotFoundError:
    from feedback_dataset_fixtures import jsonl, jsonl_file, metadata, record


class FeedbackDatasetSchemaTest(unittest.TestCase):
    def test_positiveFraudMapsToPositiveClass(self):
        parsed = self._parse(record(feedbackLabel="CONFIRMED_FRAUD", evaluationLabel="POSITIVE_FRAUD"))

        self.assertEqual(1, evaluation_label_value(parsed.records[0]))
        self.assertTrue(parsed.records[0].is_positive_class)

    def test_negativeLegitimateMapsToNegativeClass(self):
        parsed = self._parse(record(feedbackLabel="CONFIRMED_LEGITIMATE", evaluationLabel="NEGATIVE_LEGITIMATE"))

        self.assertEqual(0, evaluation_label_value(parsed.records[0]))
        self.assertTrue(parsed.records[0].is_negative_class)

    def test_rejectsMismatchedFeedbackAndEvaluationLabel(self):
        self._assert_rejected(record(feedbackLabel="CONFIRMED_FRAUD", evaluationLabel="NEGATIVE_LEGITIMATE"))

    def test_rejectsInconclusiveFeedbackLabel(self):
        self._assert_rejected(record(feedbackLabel="INCONCLUSIVE", evaluationLabel="NEGATIVE_LEGITIMATE"))

    def test_rejectsNeedsMoreInfoFeedbackLabel(self):
        self._assert_rejected(record(feedbackLabel="NEEDS_MORE_INFO", evaluationLabel="NEGATIVE_LEGITIMATE"))

    def test_rejects_legacy_feedback_labels(self):
        self._assert_rejected(record(evaluationLabel="ANALYST_CONFIRMED_FRAUD"))

    def test_rejectsRawIds(self):
        self._assert_rejected(record(transactionId="raw-txn-1"))

    def test_rejectsForbiddenFields(self):
        self._assert_rejected(record(groundTruth=True))

    def test_rejectsUnknownRecordFields(self):
        self._assert_rejected(record(safeOptionalButUnknown="value"))

    def test_rejectsMissingRequiredRecordField(self):
        payload = record()
        payload.pop("feedbackCreatedAt")

        self._assert_rejected(payload)

    def test_rejectsElevenDecisionReasonCodes(self):
        self._assert_rejected(record(decisionReasonCodes=[f"CODE_{index}" for index in range(11)]))

    def test_rejectsEmptyDecisionReasonCodes(self):
        self._assert_rejected(record(decisionReasonCodes=[]))

    def test_rejectsFreeTextDecisionReasonCode(self):
        self._assert_rejected(record(decisionReasonCodes=["Free text"]))

    def test_rejectsBadEvaluationRecordId(self):
        self._assert_rejected(record(evaluationRecordId="eval-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"))

    def test_rejectsBadTransactionReference(self):
        self._assert_rejected(record(transactionReference="txnref-bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"))

    def test_preservesMissingScoreAsNone(self):
        parsed = self._parse(record(fraudScore=None))

        self.assertIsNone(parsed.records[0].fraud_score)

    def test_preservesMissingRiskAsNone(self):
        parsed = self._parse(record(riskLevel=None))

        self.assertIsNone(parsed.records[0].risk_level)

    def test_preservesMissingAlertRecommendedAsNone(self):
        parsed = self._parse(record(alertRecommended=None))

        self.assertIsNone(parsed.records[0].alert_recommended)

    def test_rejectsNoncanonicalOrInvalidTimestamps(self):
        timestamp_fields = (
            "feedbackCreatedAt",
            "mlPredictionExecutedAt",
            "analystRecommendationGeneratedAt",
            "scoredAt",
            "transactionTimestamp",
        )
        for field in timestamp_fields:
            with self.subTest(field=field):
                overrides = {field: "2026-06-03T14:00:00+02:00"}
                if field == "mlPredictionExecutedAt":
                    overrides.update({
                        "mlModelName": "python-logistic-fraud-model",
                        "mlModelVersion": "2026-06-25.v1",
                        "mlFeatureContractVersion": "feature-contract-v2",
                    })
                self._assert_rejected(record(**overrides))
        self._assert_rejected(record(feedbackCreatedAt="2026-02-30T12:00:00Z"))
        with jsonl_file(jsonl(record(), metadata_overrides={"builtAt": "2026-06-10T02:00:00+02:00"})) as path:
            with self.assertRaises(FeedbackDatasetValidationError):
                read_feedback_dataset_jsonl(path)

    def test_rejectsEnumsOutsideJavaContract(self):
        invalid_values = {
            "riskLevel": "UNKNOWN",
            "engineIntelligenceStatus": "PRESENT",
            "agreementStatus": "ENGINES_AGREE",
            "riskMismatchStatus": "NONE",
            "scoreDeltaBucket": "SMALL_DELTA",
            "analystRecommendationStatus": "GENERATED",
            "analystRecommendation": "REVIEW_TRANSACTION",
        }
        for field, value in invalid_values.items():
            with self.subTest(field=field):
                self._assert_rejected(record(**{field: value}))

    def test_rejectsInvalidPlatformScoreAndRecommendationVersion(self):
        for score in (-0.1, 1.1, 0.12345):
            with self.subTest(score=score):
                self._assert_rejected(record(fraudScore=score))
        for score in (float("nan"), float("inf")):
            with self.subTest(score=score), jsonl_file(jsonl(record(fraudScore=score))) as path:
                with self.assertRaises(FeedbackDatasetFormatError):
                    read_feedback_dataset_jsonl(path)
        self._assert_rejected(record(analystRecommendationVersion="v1/path"))
        self._assert_rejected(record(analystRecommendationVersion="v" * 65))

    def test_readsExactMlModelIdentity(self):
        parsed = self._parse(record(
            mlModelName="python-logistic-fraud-model",
            mlModelVersion="2026-06-25.v1",
            mlFeatureContractVersion="feature-contract-v2",
        ))

        self.assertEqual("python-logistic-fraud-model", parsed.records[0].ml_model_name)
        self.assertEqual("2026-06-25.v1", parsed.records[0].ml_model_version)
        self.assertEqual("feature-contract-v2", parsed.records[0].ml_feature_contract_version)
        self.assertEqual("AVAILABLE", parsed.records[0].ml_prediction_evidence_status)
        self.assertEqual(0.8123, parsed.records[0].ml_prediction_score)
        self.assertEqual("HIGH", parsed.records[0].ml_prediction_risk_level)
        self.assertEqual("2026-06-03T11:59:00Z", parsed.records[0].ml_prediction_executed_at)

    def test_explicitlyAbsentMlPredictionEvidenceRemainsValid(self):
        parsed = self._parse(record())

        self.assertEqual("LEGITIMATELY_ABSENT", parsed.records[0].ml_prediction_evidence_status)
        self.assertIsNone(parsed.records[0].ml_prediction_score)
        self.assertIsNone(parsed.records[0].ml_prediction_risk_level)
        self.assertIsNone(parsed.records[0].ml_prediction_executed_at)
        self.assertIsNone(parsed.records[0].ml_model_name)
        self.assertIsNone(parsed.records[0].ml_model_version)
        self.assertIsNone(parsed.records[0].ml_feature_contract_version)

    def test_explicitNullMlModelIdentityRemainsValid(self):
        parsed = self._parse(record(
            mlModelName=None,
            mlModelVersion=None,
            mlFeatureContractVersion=None,
        ))

        self.assertIsNone(parsed.records[0].ml_model_name)
        self.assertIsNone(parsed.records[0].ml_model_version)
        self.assertIsNone(parsed.records[0].ml_feature_contract_version)

    def test_rejectsPartialMlModelIdentityMatrix(self):
        partial_identities = (
            {"mlModelName": "python-logistic-fraud-model"},
            {"mlModelVersion": "2026-06-25.v1"},
            {"mlFeatureContractVersion": "feature-contract-v2"},
            {"mlModelName": "python-logistic-fraud-model", "mlModelVersion": "2026-06-25.v1"},
            {"mlModelName": "python-logistic-fraud-model", "mlFeatureContractVersion": "feature-contract-v2"},
            {"mlModelVersion": "2026-06-25.v1", "mlFeatureContractVersion": "feature-contract-v2"},
        )

        for identity in partial_identities:
            with self.subTest(identity=identity):
                self._assert_rejected(record(**identity))

    def test_rejectsMissingMlPredictionEvidenceContractFields(self):
        fields = (
            "mlPredictionEvidenceStatus",
            "mlPredictionScore",
            "mlPredictionRiskLevel",
            "mlPredictionExecutedAt",
            "mlModelName",
            "mlModelVersion",
            "mlFeatureContractVersion",
        )
        for field in fields:
            with self.subTest(field=field):
                payload = record()
                payload.pop(field)
                self._assert_rejected(payload)

    def test_rejectsMissingOrContradictoryRulesEvidence(self):
        for field in ("rulesEvidenceStatus", "rulesRiskLevel"):
            with self.subTest(field=field):
                payload = record()
                payload.pop(field)
                self._assert_rejected(payload)
        self._assert_rejected(record(rulesEvidenceStatus="AVAILABLE", rulesRiskLevel=None))
        self._assert_rejected(record(rulesEvidenceStatus="UNAVAILABLE", rulesRiskLevel="LOW"))
        self._assert_rejected(record(rulesEvidenceStatus="UNKNOWN", rulesRiskLevel=None))

    def test_rejectsAvailableMlPredictionEvidenceWithPartialSignal(self):
        available = {
            "mlModelName": "python-logistic-fraud-model",
            "mlModelVersion": "2026-06-25.v1",
            "mlFeatureContractVersion": "feature-contract-v2",
        }
        for field in ("mlPredictionScore", "mlPredictionRiskLevel", "mlPredictionExecutedAt"):
            with self.subTest(field=field):
                self._assert_rejected(record(**available, **{field: None}))

    def test_rejectsAbsentMlPredictionEvidenceWithAnyPredictionValue(self):
        overrides = (
            {"mlPredictionScore": 0.0},
            {"mlPredictionRiskLevel": "LOW"},
            {"mlPredictionExecutedAt": "2026-06-03T11:59:00Z"},
            {
                "mlModelName": "python-logistic-fraud-model",
                "mlModelVersion": "2026-06-25.v1",
                "mlFeatureContractVersion": "feature-contract-v2",
            },
        )
        for values in overrides:
            with self.subTest(values=values):
                self._assert_rejected(record(mlPredictionEvidenceStatus="LEGITIMATELY_ABSENT", **values))

    def test_rejectsUnsupportedEvidenceStatusRiskAndScore(self):
        self._assert_rejected(record(mlPredictionEvidenceStatus="MISSING_UNEXPECTEDLY"))
        self._assert_rejected(record(
            mlModelName="python-logistic-fraud-model",
            mlModelVersion="2026-06-25.v1",
            mlFeatureContractVersion="feature-contract-v2",
            mlPredictionRiskLevel="UNKNOWN",
        ))
        self._assert_rejected(record(
            mlModelName="python-logistic-fraud-model",
            mlModelVersion="2026-06-25.v1",
            mlFeatureContractVersion="feature-contract-v2",
            mlPredictionScore=1.1,
        ))
        self._assert_rejected(record(
            mlModelName="python-logistic-fraud-model",
            mlModelVersion="2026-06-25.v1",
            mlFeatureContractVersion="feature-contract-v2",
            mlPredictionScore=0.81234,
        ))

    def test_rejectsUnsafeMlModelIdentity(self):
        self._assert_rejected(record(mlModelName="s3://bucket/model"))
        self._assert_rejected(record(mlModelVersion="v1/token-secret"))
        self._assert_rejected(record(mlModelVersion="2026-06-25:v1"))
        self._assert_rejected(record(mlFeatureContractVersion="feature contract v2"))
        self._assert_rejected(record(mlFeatureContractVersion="f" * 97))

    def test_metadataAcceptsOptionalAndChronologicalEvaluationWindows(self):
        valid_windows = (
            ({}, (None, None)),
            ({"fromInclusive": None, "toInclusive": None}, (None, None)),
            ({"fromInclusive": "2026-06-01T00:00:00Z"}, ("2026-06-01T00:00:00Z", None)),
            ({"toInclusive": "2026-06-09T23:59:59Z"}, (None, "2026-06-09T23:59:59Z")),
            (
                {"fromInclusive": "2026-06-01T00:00:00Z", "toInclusive": "2026-06-09T23:59:59Z"},
                ("2026-06-01T00:00:00Z", "2026-06-09T23:59:59Z"),
            ),
            (
                {"fromInclusive": "2026-06-01T00:00:00Z", "toInclusive": "2026-06-01T00:00:00Z"},
                ("2026-06-01T00:00:00Z", "2026-06-01T00:00:00Z"),
            ),
            (
                {"fromInclusive": "2026-06-01T00:00:00Z", "toInclusive": "2026-06-01T00:00:00.1Z"},
                ("2026-06-01T00:00:00Z", "2026-06-01T00:00:00.1Z"),
            ),
            (
                {"fromInclusive": "2026-09-27T00:00:00.123456788Z", "toInclusive": "2026-09-27T00:00:00.123456789Z"},
                ("2026-09-27T00:00:00.123456788Z", "2026-09-27T00:00:00.123456789Z"),
            ),
        )
        for window, expected in valid_windows:
            with self.subTest(window=window):
                parsed = self._parse_with_metadata_window(window)
                self.assertEqual(expected, (parsed.metadata.from_inclusive, parsed.metadata.to_inclusive))

    def test_metadataRejectsInvalidEvaluationWindows(self):
        invalid_windows = (
            {"fromInclusive": "2026-06-10T00:00:00Z", "toInclusive": "2026-06-01T00:00:00Z"},
            {"fromInclusive": "not-a-timestamp", "toInclusive": None},
            {"fromInclusive": "2026-06-01T00:00:00", "toInclusive": None},
            {"fromInclusive": "2026-06-01T01:00:00+01:00", "toInclusive": None},
            {"fromInclusive": "2026-09-27T00:00:00.123456789Z", "toInclusive": "2026-09-27T00:00:00.123456788Z"},
        )
        for window in invalid_windows:
            with self.subTest(window=window):
                with self.assertRaises(FeedbackDatasetValidationError):
                    self._parse_with_metadata_window(window)

    def test_metadataPopulationCountsMustReconcile(self):
        invalid_counts = (
            {"rawRowsRead": 2},
            {"excludedUnresolvedCount": 1},
            {"truncated": True},
            {"rawRowsRead": 1002, "recordsReturned": 1, "truncated": True},
        )
        for overrides in invalid_counts:
            with self.subTest(overrides=overrides), jsonl_file(
                jsonl(record(), metadata_overrides=overrides)
            ) as path:
                with self.assertRaises(FeedbackDatasetValidationError):
                    read_feedback_dataset_jsonl(path)

        parsed = self._parse_metadata({"rawRowsRead": 2, "truncated": True})
        self.assertTrue(parsed.metadata.truncated)

    def _parse(self, payload):
        with jsonl_file(jsonl(payload)) as path:
            return read_feedback_dataset_jsonl(path)

    def _assert_rejected(self, payload):
        with jsonl_file(jsonl(payload)) as path:
            with self.assertRaises(FeedbackDatasetValidationError):
                read_feedback_dataset_jsonl(path)

    def _parse_with_metadata_window(self, window):
        metadata_payload = metadata()
        metadata_payload.pop("fromInclusive")
        metadata_payload.pop("toInclusive")
        metadata_payload.update(window)
        payload = "\n".join((
            json.dumps(metadata_payload, separators=(",", ":")),
            json.dumps({"type": "DATASET_RECORD", "record": record()}, separators=(",", ":")),
            "",
        ))
        with jsonl_file(payload) as path:
            return read_feedback_dataset_jsonl(path)

    def _parse_metadata(self, overrides):
        with jsonl_file(jsonl(record(), metadata_overrides=overrides)) as path:
            return read_feedback_dataset_jsonl(path)


if __name__ == "__main__":
    unittest.main()
