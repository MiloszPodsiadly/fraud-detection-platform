import unittest

from offline_evaluation.dataset_reader import read_fdp102_jsonl
from offline_evaluation.dataset_schema import DatasetRecord, DatasetValidationError, evaluation_label_value, ml_ranking_score, risk_category
from fdp103_fixtures import jsonl, record


class DatasetSchemaValidationTest(unittest.TestCase):
    def test_analystConfirmedFraudIsEvaluationPositive(self):
        parsed = read_fdp102_jsonl(jsonl(record(evaluationLabel="ANALYST_CONFIRMED_FRAUD")))

        self.assertEqual(1, evaluation_label_value(parsed.records[0]))
        self.assertTrue(parsed.records[0].is_evaluation_positive)

    def test_analystMarkedLegitimateIsEvaluationNegative(self):
        parsed = read_fdp102_jsonl(jsonl(record(evaluationLabel="ANALYST_MARKED_LEGITIMATE")))

        self.assertEqual(0, evaluation_label_value(parsed.records[0]))
        self.assertTrue(parsed.records[0].is_evaluation_negative)

    def test_notEvaluationEligibleIsExcluded(self):
        parsed = read_fdp102_jsonl(jsonl(record(evaluationLabel="NOT_EVALUATION_ELIGIBLE")))

        self.assertFalse(parsed.records[0].is_evaluation_eligible)
        self.assertEqual((), parsed.evaluation_records)

    def test_notEvaluationEligibleNeverNegative(self):
        parsed = read_fdp102_jsonl(jsonl(record(evaluationLabel="NOT_EVALUATION_ELIGIBLE")))

        self.assertIsNone(evaluation_label_value(parsed.records[0]))
        self.assertFalse(parsed.records[0].is_evaluation_negative)

    def test_rejectsUnknownLabel(self):
        with self.assertRaises(DatasetValidationError):
            read_fdp102_jsonl(jsonl(record(evaluationLabel="CONFIRMED_FRAUD")))

    def test_rejectsGroundTruthField(self):
        with self.assertRaises(DatasetValidationError):
            read_fdp102_jsonl(jsonl(record(groundTruth=True)))

    def test_rejectsModelTrainingLabelField(self):
        with self.assertRaises(DatasetValidationError):
            read_fdp102_jsonl(jsonl(record(modelTrainingLabel=1)))

    def test_rejectsFinalDecisionField(self):
        with self.assertRaises(DatasetValidationError):
            read_fdp102_jsonl(jsonl(record(finalDecision="APPROVE")))

    def test_missingMlScoreDoesNotBecomeZero(self):
        parsed = read_fdp102_jsonl(jsonl(record(mlScoreBucket=None, mlRiskLevel="HIGH")))

        self.assertIsNone(parsed.records[0].ml_score_bucket)

    def test_missingMlRiskDoesNotBecomeLow(self):
        parsed = read_fdp102_jsonl(jsonl(record(mlRiskLevel=None, mlScoreBucket="HIGH")))

        self.assertIsNone(parsed.records[0].ml_risk_level)

    def test_missingRulesScoreDoesNotBecomeZero(self):
        parsed = read_fdp102_jsonl(jsonl(record(rulesScoreBucket=None, rulesRiskLevel="HIGH")))

        self.assertIsNone(parsed.records[0].rules_score_bucket)

    def test_missingRulesRiskDoesNotBecomeLow(self):
        parsed = read_fdp102_jsonl(jsonl(record(rulesRiskLevel=None, rulesScoreBucket="HIGH")))

        self.assertIsNone(parsed.records[0].rules_risk_level)

    def test_missingProjectionRemainsExplicit(self):
        parsed = read_fdp102_jsonl(jsonl(record(projectionStatus="MISSING")))

        self.assertTrue(parsed.records[0].projection_missing)

    def test_rejectsRawSensitiveFields(self):
        with self.assertRaises(DatasetValidationError):
            read_fdp102_jsonl(jsonl(record(customerId="cust-1")))

    def test_rejectsUnknownProjectionStatus(self):
        self._assert_record_rejected(projectionStatus="BROKEN_ENUM")

    def test_rejectsPresentProjectionStatus(self):
        self._assert_record_rejected(projectionStatus="PRESENT")

    def test_acceptsAvailableProjectionStatus(self):
        parsed = read_fdp102_jsonl(jsonl(record(projectionStatus="AVAILABLE")))

        self.assertEqual("AVAILABLE", parsed.records[0].projection_status)

    def test_acceptsMissingProjectionStatus(self):
        parsed = read_fdp102_jsonl(jsonl(record(projectionStatus="MISSING")))

        self.assertEqual("MISSING", parsed.records[0].projection_status)

    def test_rejectsUnknownLabelSource(self):
        self._assert_record_rejected(labelSource="BROKEN_ENUM")

    def test_rejectsUnknownMlRiskLevel(self):
        self._assert_record_rejected(mlRiskLevel="BROKEN_ENUM")

    def test_rejectsUnknownRulesRiskLevel(self):
        self._assert_record_rejected(rulesRiskLevel="BROKEN_ENUM")

    def test_rejectsUnknownMlScoreBucket(self):
        self._assert_record_rejected(mlScoreBucket="BROKEN_ENUM")

    def test_rejectsUnknownRulesScoreBucket(self):
        self._assert_record_rejected(rulesScoreBucket="BROKEN_ENUM")

    def test_unknownRiskDoesNotBecomeLowOrMedium(self):
        with self.assertRaises(DatasetValidationError):
            risk_category("BANANA", None)

    def test_unavailableScoreBucketDoesNotBecomeLowOrMedium(self):
        self.assertEqual("missing", risk_category(None, "UNAVAILABLE"))

    def test_noneScoreBucketDoesNotBecomeLowOrMedium(self):
        self.assertEqual("missing", risk_category(None, "NONE"))

    def test_unknownScoreBucketDoesNotEnterRanking(self):
        corrupt = self._record_object(ml_risk_level=None, ml_score_bucket="BANANA")

        with self.assertRaises(DatasetValidationError):
            ml_ranking_score(corrupt)

    def test_unavailableScoreBucketDoesNotEnterRanking(self):
        record_object = self._record_object(ml_risk_level=None, ml_score_bucket="UNAVAILABLE")

        self.assertEqual(-1, ml_ranking_score(record_object))

    def test_unavailableStatusDoesNotBecomeLowOrMedium(self):
        self._assert_record_rejected(mlEngineStatus="UNAVAILABLE", mlRiskLevel="LOW")

    def test_corruptedRiskValueDoesNotProduceMetrics(self):
        self._assert_record_rejected(mlRiskLevel="CORRUPTED")

    def test_storesMlEngineStatus(self):
        parsed = read_fdp102_jsonl(jsonl(record(mlEngineStatus="AVAILABLE")))

        self.assertEqual("AVAILABLE", parsed.records[0].ml_engine_status)

    def test_storesRulesEngineStatus(self):
        parsed = read_fdp102_jsonl(jsonl(record(rulesEngineStatus="AVAILABLE")))

        self.assertEqual("AVAILABLE", parsed.records[0].rules_engine_status)

    def test_rejectsUnknownMlEngineStatus(self):
        self._assert_record_rejected(mlEngineStatus="MODEL_ERROR")

    def test_rejectsUnknownRulesEngineStatus(self):
        self._assert_record_rejected(rulesEngineStatus="MODEL_ERROR")

    def test_availableMlWithRiskIsUsable(self):
        parsed = read_fdp102_jsonl(jsonl(record(mlEngineStatus="AVAILABLE", mlRiskLevel="HIGH")))

        self.assertFalse(parsed.records[0].ml_signal_missing)

    def test_mlTimeoutWithRiskRejected(self):
        self._assert_record_rejected(mlEngineStatus="TIMEOUT", mlRiskLevel="HIGH")

    def test_mlUnavailableWithRiskRejected(self):
        self._assert_record_rejected(mlEngineStatus="UNAVAILABLE", mlRiskLevel="HIGH")

    def test_mlSkippedWithRiskRejected(self):
        self._assert_record_rejected(mlEngineStatus="SKIPPED", mlRiskLevel="HIGH")

    def test_rulesTimeoutWithRiskRejected(self):
        self._assert_record_rejected(rulesEngineStatus="TIMEOUT", rulesRiskLevel="HIGH")

    def test_rulesUnavailableWithRiskRejected(self):
        self._assert_record_rejected(rulesEngineStatus="UNAVAILABLE", rulesRiskLevel="HIGH")

    def test_timeoutDoesNotBecomeLowRisk(self):
        parsed = read_fdp102_jsonl(jsonl(record(mlEngineStatus="TIMEOUT", mlRiskLevel=None, mlScoreBucket=None)))

        self.assertTrue(parsed.records[0].ml_signal_missing)

    def test_unavailableDoesNotEnterRanking(self):
        parsed = read_fdp102_jsonl(jsonl(record(mlEngineStatus="UNAVAILABLE", mlRiskLevel=None, mlScoreBucket=None)))

        self.assertEqual(-1, ml_ranking_score(parsed.records[0]))

    def test_nonAvailableEngineDoesNotProduceHighLowBucket(self):
        parsed = read_fdp102_jsonl(jsonl(record(
            mlEngineStatus="UNAVAILABLE",
            mlRiskLevel=None,
            mlScoreBucket=None,
            rulesRiskLevel="HIGH",
        )))

        self.assertTrue(parsed.records[0].ml_signal_missing)

    def test_fixtureUsesAvailableProjectionStatus(self):
        self.assertEqual("AVAILABLE", record()["projectionStatus"])

    def test_fixtureDoesNotUsePresentProjectionStatus(self):
        self.assertNotEqual("PRESENT", record()["projectionStatus"])

    def test_goldenFdp102FixtureParsesSuccessfully(self):
        parsed = read_fdp102_jsonl(jsonl(record()))

        self.assertEqual(1, len(parsed.records))

    def test_goldenFdp102FixtureUsesOnlyAllowedValues(self):
        parsed = read_fdp102_jsonl(jsonl(record()))

        self.assertEqual("AVAILABLE", parsed.records[0].projection_status)

    def test_datasetReaderAcceptsPseudonymousEvaluationRecordId(self):
        parsed = read_fdp102_jsonl(jsonl(record(evaluationRecordId="eval-0123456789abcdef0123456789abcdef")))

        self.assertEqual("eval-0123456789abcdef0123456789abcdef", parsed.records[0].evaluation_record_id)

    def test_datasetReaderAcceptsPseudonymousTransactionReference(self):
        parsed = read_fdp102_jsonl(jsonl(record(transactionReference="txnref-0123456789abcdef0123456789abcdef")))

        self.assertEqual("txnref-0123456789abcdef0123456789abcdef", parsed.records[0].transaction_reference)

    def test_rejectsInvalidEvaluationRecordId(self):
        self._assert_record_rejected(evaluationRecordId="eval-not-hex")

    def test_rejectsRawTransactionReference(self):
        self._assert_record_rejected(transactionReference="txn-raw-123")

    def test_acceptsMachineCodeReasonCode(self):
        parsed = read_fdp102_jsonl(jsonl(record(reasonCodes=["SAFE_CODE_1"])))

        self.assertEqual(("SAFE_CODE_1",), parsed.records[0].reason_codes)

    def test_acceptsMachineCodeDiagnosticSignal(self):
        parsed = read_fdp102_jsonl(jsonl(record(diagnosticSignals=["SAFE_CODE_1"])))

        self.assertEqual(("SAFE_CODE_1",), parsed.records[0].diagnostic_signals)

    def test_rejectsTooManyReasonCodes(self):
        self._assert_record_rejected(reasonCodes=[f"CODE_{index}" for index in range(11)])

    def test_rejectsTooManyDiagnosticSignals(self):
        self._assert_record_rejected(diagnosticSignals=[f"CODE_{index}" for index in range(11)])

    def test_rejectsOversizedReasonCode(self):
        self._assert_record_rejected(reasonCodes=["A" * 65])

    def test_rejectsOversizedDiagnosticSignal(self):
        self._assert_record_rejected(diagnosticSignals=["A" * 65])

    def test_rejectsFreeTextReasonCode(self):
        self._assert_record_rejected(reasonCodes=["Free text"])

    def test_rejectsReasonCodeWithRawIdentifierPattern(self):
        self._assert_record_rejected(reasonCodes=["CUSTOMER_ID_123"])

    def test_rejectsDiagnosticSignalWithDeviceIdPattern(self):
        self._assert_record_rejected(diagnosticSignals=["DEVICE_ID_ABC"])

    def test_rejectsDiagnosticSignalWithCustomerIdPattern(self):
        self._assert_record_rejected(diagnosticSignals=["CUSTOMER_ID_ABC"])

    def test_rejectsDiagnosticSignalWithEmailLikeValue(self):
        self._assert_record_rejected(diagnosticSignals=["ANALYST@BANK"])

    def test_rejectsDiagnosticSignalWithUrlValue(self):
        self._assert_record_rejected(diagnosticSignals=["HTTP_ENDPOINT"])

    def test_rejectsReasonCodeWithTokenSecretValue(self):
        self._assert_record_rejected(reasonCodes=["TOKEN_SECRET"])

    def test_rejectsReasonCodeWithGroundTruthTerm(self):
        self._assert_record_rejected(reasonCodes=["GROUND_TRUTH"])

    def test_rejectsStacktraceLikeDiagnosticSignal(self):
        self._assert_record_rejected(diagnosticSignals=["STACKTRACE_LINE"])

    def _assert_record_rejected(self, **overrides):
        with self.assertRaises(DatasetValidationError):
            read_fdp102_jsonl(jsonl(record(**overrides)))

    def _record_object(self, **overrides) -> DatasetRecord:
        values = {
            "evaluation_record_id": "eval-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            "transaction_reference": "txnref-bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
            "feedback_submitted_at": "2026-06-01T01:00:00Z",
            "evaluation_label": "ANALYST_CONFIRMED_FRAUD",
            "projection_status": "AVAILABLE",
            "ml_engine_status": "AVAILABLE",
            "rules_engine_status": "AVAILABLE",
            "ml_risk_level": "HIGH",
            "ml_score_bucket": "HIGH",
            "rules_risk_level": "LOW",
            "rules_score_bucket": "LOW",
            "reason_codes": (),
            "diagnostic_signals": (),
        }
        values.update(overrides)
        return DatasetRecord(**values)


if __name__ == "__main__":
    unittest.main()
