from __future__ import annotations

import copy
import unittest

from offline_evaluation.feedback_dataset_evaluation.exact_model_shadow_performance.generator import (
    build_exact_model_shadow_performance,
)
from offline_evaluation.feedback_dataset_evaluation.exact_model_shadow_performance.schema import (
    FALLBACK_UNAVAILABLE,
    LATENCY_UNAVAILABLE,
    REASON_CODES_UNAVAILABLE,
    ExactModelShadowPerformanceValidationError,
    exact_report_selection_key,
    validate_exact_model_shadow_performance,
)

from feedback_dataset_evaluation.exact_model_shadow_performance.fixtures import (
    IDENTITY,
    OTHER_BYTES_IDENTITY,
    SHADOW_GENERATED_AT,
    evaluation_evidence,
    model_record,
    representative_evidence,
)


class ExactModelShadowPerformanceContractTest(unittest.TestCase):
    def test_builds_canonical_summary_without_recalculating_metrics(self):
        evidence = representative_evidence()

        summary = build_exact_model_shadow_performance(evidence, SHADOW_GENERATED_AT)

        self.assertEqual("EXACT_MODEL_SHADOW_PERFORMANCE_V1", summary["reportType"])
        self.assertEqual("exact-model-shadow-performance-v1", summary["schemaVersion"])
        self.assertEqual("LABELED_FEEDBACK", summary["populationType"])
        self.assertEqual(evidence.summary, summary["evaluationSummary"])
        disagreement = summary["evaluationSummary"]["supportedMetrics"]["rulesVsMlDisagreement"]
        self.assertEqual({"available": True, "reason": None, "value": 0.5}, disagreement["disagreementRate"])
        self.assertEqual(2, disagreement["analystLabeledFraudRecordsCompared"])
        self.assertEqual("VERIFIED_AGAINST_SOURCE_BYTES", summary["sourceEvaluation"]["sourceDatasetVerificationLevel"])

    def test_operational_metrics_remain_truthfully_unavailable(self):
        summary = self._summary()

        self.assertEqual(LATENCY_UNAVAILABLE, summary["operationalDiagnostics"]["mlLatencyP95"]["reason"])
        self.assertEqual(FALLBACK_UNAVAILABLE, summary["operationalDiagnostics"]["fallbackRate"]["reason"])
        self.assertEqual(REASON_CODES_UNAVAILABLE, summary["operationalDiagnostics"]["mlReasonCodeDistribution"]["reason"])
        self.assertIsNone(summary["operationalDiagnostics"]["fallbackRate"]["denominator"])

    def test_claim_only_is_not_upgraded(self):
        evidence = evaluation_evidence(model_record(1), verified_source=False)

        summary = build_exact_model_shadow_performance(evidence, SHADOW_GENERATED_AT)

        self.assertEqual("CLAIM_ONLY", summary["sourceEvaluation"]["sourceDatasetVerificationLevel"])
        self.assertEqual("NOT_PROVIDED", summary["sourceEvaluation"]["externalAttestation"])
        self.assertEqual("NOT_CLAIMED", summary["sourceEvaluation"]["immutableStorageGuarantee"])

    def test_same_logical_identity_with_different_sha_has_distinct_selection(self):
        first = self._summary(IDENTITY)
        second = self._summary(OTHER_BYTES_IDENTITY)

        self.assertNotEqual(self._key(first), self._key(second))
        self.assertNotEqual(
            first["evaluationSummary"]["evaluationSubject"]["modelArtifactSha256"],
            second["evaluationSummary"]["evaluationSubject"]["modelArtifactSha256"],
        )

    def test_no_data_is_distinct_from_available_zero(self):
        no_data = build_exact_model_shadow_performance(
            evaluation_evidence(identity=IDENTITY),
            SHADOW_GENERATED_AT,
        )
        available_zero = self._summary()

        self.assertEqual("NO_DATA", no_data["coverage"]["returnedCohortStatus"])
        self.assertFalse(no_data["evaluationSummary"]["supportedMetrics"]["mlPredictionMetrics"]["available"])
        self.assertTrue(available_zero["evaluationSummary"]["supportedMetrics"]["mlPredictionMetrics"]["falsePositiveRate"]["available"])
        self.assertEqual(0.0, available_zero["evaluationSummary"]["supportedMetrics"]["mlPredictionMetrics"]["falsePositiveRate"]["value"])

    def test_partial_and_truncated_coverage_are_explicit(self):
        evidence = evaluation_evidence(
            model_record(1),
            model_record(2, OTHER_BYTES_IDENTITY),
            identity=IDENTITY,
            metadata_overrides={"rawRowsRead": 3, "truncated": True},
        )

        summary = build_exact_model_shadow_performance(evidence, SHADOW_GENERATED_AT)

        self.assertEqual("PARTIAL_COVERAGE", summary["coverage"]["returnedCohortStatus"])
        self.assertEqual("BOUNDED_SOURCE_TRUNCATED", summary["coverage"]["sourcePopulationStatus"])

    def test_rejects_contract_and_cross_field_drift(self):
        mutations = (
            ("unsupported schema", lambda value: value.update(schemaVersion="v2")),
            ("unsupported field", lambda value: value.update(unexpected=True)),
            ("population semantics", lambda value: value.update(populationType="SCORING_OCCURRENCES")),
            ("invalid generated timestamp", lambda value: value.update(generatedAt="2026-06-10")),
            ("misrepresented verification", lambda value: value["sourceEvaluation"].update(sourceDatasetVerificationLevel="EXTERNALLY_ATTESTED")),
            ("contradictory operational availability", lambda value: value["operationalDiagnostics"]["fallbackRate"].update(available=True, value=0.0)),
            ("incorrect population", lambda value: value["evaluationSummary"]["population"].update(recordsEvaluated=999)),
        )
        for label, mutation in mutations:
            with self.subTest(label=label):
                summary = self._summary()
                mutation(summary)
                with self.assertRaises(ExactModelShadowPerformanceValidationError):
                    validate_exact_model_shadow_performance(summary)

    def test_rejects_partial_identity_invalid_sha_and_source_timestamp_mismatch(self):
        cases = []
        partial = self._summary()
        del partial["evaluationSummary"]["evaluationSubject"]["modelVersion"]
        cases.append(partial)
        invalid_sha = self._summary()
        invalid_sha["evaluationSummary"]["evaluationSubject"]["modelArtifactSha256"] = "A" * 64
        cases.append(invalid_sha)
        mismatched_source = self._summary()
        mismatched_source["sourceEvaluation"]["reportGeneratedAt"] = "2026-06-09T00:00:00Z"
        cases.append(mismatched_source)
        for value in cases:
            with self.assertRaises(ExactModelShadowPerformanceValidationError):
                validate_exact_model_shadow_performance(value)

    def test_selection_rejects_partial_identity_and_invalid_window(self):
        summary = self._summary()
        subject = copy.deepcopy(summary["evaluationSummary"]["evaluationSubject"])
        window = copy.deepcopy(summary["evaluationSummary"]["evaluationWindow"])
        del subject["modelVersion"]
        with self.assertRaises(ExactModelShadowPerformanceValidationError):
            exact_report_selection_key(subject, window)
        window["timeBasis"] = "SCORING_EVENT_TIME"
        with self.assertRaises(ExactModelShadowPerformanceValidationError):
            exact_report_selection_key(summary["evaluationSummary"]["evaluationSubject"], window)

    def test_selection_key_preserves_one_through_nine_fractional_digits(self):
        summary = self._summary()
        window = copy.deepcopy(summary["evaluationSummary"]["evaluationWindow"])
        window["fromInclusive"] = "2026-06-01T00:00:00.1Z"
        window["toInclusive"] = "2026-06-09T23:59:59.123456789Z"

        self.assertEqual(
            "5d3decdf6d63c681081974f1b451707e9840b0e4de3c302d8134f1cadb028d83",
            exact_report_selection_key(summary["evaluationSummary"]["evaluationSubject"], window),
        )

    def _summary(self, identity=IDENTITY):
        return build_exact_model_shadow_performance(representative_evidence(identity), SHADOW_GENERATED_AT)

    @staticmethod
    def _key(summary):
        evaluation = summary["evaluationSummary"]
        return exact_report_selection_key(evaluation["evaluationSubject"], evaluation["evaluationWindow"])


if __name__ == "__main__":
    unittest.main()

