import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from offline_evaluation.generate_promotion_readiness_report import (
    DEFAULT_OUTPUT,
    DEFAULT_OUTPUT_ROOT,
    DEFAULT_SHADOW_SUMMARY,
    PromotionReadinessGenerationError,
    _assert_allowed_output_path,
    generate_promotion_readiness_report,
    main,
    publish_promotion_readiness_report,
    validate_promotion_readiness_report_file,
)
from offline_evaluation.promotion_readiness_schema import (
    BANNER,
    GOVERNANCE_STATUS,
    READINESS_STATUSES,
    REPORT_TYPE,
    REPORT_VERSION,
    PromotionReadinessValidationError,
    build_promotion_review_readiness_report,
    promotion_review_readiness_report_json,
    validate_promotion_review_readiness_report,
)


ROOT = Path(__file__).resolve().parents[3]
FIXTURE = ROOT / "ml-inference-service" / "tests" / "offline_evaluation" / "fixtures" / "shadow_performance" / "expected_shadow_performance_summary_v1.json"
GENERATOR_SOURCE = ROOT / "ml-inference-service" / "offline_evaluation" / "generate_promotion_readiness_report.py"
SCHEMA_SOURCE = ROOT / "ml-inference-service" / "offline_evaluation" / "promotion_readiness_schema.py"
MAKEFILE = ROOT / "Makefile"
DOC = ROOT / "docs" / "architecture" / "promotion_review_readiness_report.md"
OPENAPI_ROOT = ROOT / "docs" / "openapi"
UI_ROOT = ROOT / "analyst-console-ui"


class PromotionReadinessReportGenerationTest(unittest.TestCase):
    def test_validCurrentSummaryCreatesReviewableReport(self):
        with workspace() as paths:
            report = generate(paths)

            self.assertEqual(REPORT_TYPE, report["reportType"])
            self.assertEqual(REPORT_VERSION, report["reportVersion"])
            self.assertEqual(GOVERNANCE_STATUS, report["governanceStatus"])
            self.assertEqual("REVIEWABLE", report["readinessStatus"])
            self.assertTrue(report["diagnosticOnly"])
            self.assertTrue(report["notPromotionApproval"])
            self.assertTrue(report["notThresholdRecommendation"])
            self.assertTrue(report["notProductionDecisioning"])
            self.assertTrue(report["notPaymentAuthorization"])
            self.assertTrue(report["notAutomaticDecisioning"])
            self.assertEqual(BANNER, report["banner"])

    def test_validReportPassesSchemaValidation(self):
        with workspace() as paths:
            report = generate(paths)

            self.assertEqual(report, validate_promotion_readiness_report_file(paths.output))
            self.assertEqual(report, validate_promotion_review_readiness_report(report))

    def test_reviewableOnlyWhenRequiredChecksPass(self):
        report = build_report(minimum_diagnostic_evidence_records=1)
        self.assertEqual("REVIEWABLE", report["readinessStatus"])

        insufficient = build_report(minimum_diagnostic_evidence_records=30)
        self.assertEqual("INSUFFICIENT_DATA", insufficient["readinessStatus"])
        self.assertIn("MINIMUM_DIAGNOSTIC_EVIDENCE_RECORDS_FAILED", insufficient["reasonCodes"])

    def test_missingCurrentSummaryExitsNonZeroAndDoesNotWriteFinalReport(self):
        with workspace() as paths:
            paths.summary.unlink()

            code = main([
                "--shadow-summary", str(paths.summary),
                "--output", str(paths.output),
                "--allow-output-root", str(paths.output.parent),
            ])

            self.assertEqual(1, code)
            self.assertFalse(paths.output.exists())

    def test_invalidCurrentSummaryExitsNonZeroAndDoesNotWriteFinalReport(self):
        with workspace() as paths:
            paths.summary.write_text("{not-json}", encoding="utf-8")

            code = main([
                "--shadow-summary", str(paths.summary),
                "--output", str(paths.output),
                "--allow-output-root", str(paths.output.parent),
            ])

            self.assertEqual(1, code)
            self.assertFalse(paths.output.exists())

    def test_unsupportedSummaryVersionDoesNotPublishReport(self):
        with workspace() as paths:
            summary = valid_summary()
            summary["summaryVersion"] = "2.0"
            paths.summary.write_text(json.dumps(summary), encoding="utf-8")

            with self.assertRaises(Exception):
                generate_promotion_readiness_report(paths.summary, paths.output, generated_at=generated_at(), allowed_output_root=paths.output.parent)

            self.assertFalse(paths.output.exists())

    def test_missingDiagnosticOnlyFlagsDoesNotPublishReport(self):
        with workspace() as paths:
            summary = valid_summary()
            summary["governance"]["diagnosticOnly"] = False
            paths.summary.write_text(json.dumps(summary), encoding="utf-8")

            with self.assertRaises(Exception):
                generate_promotion_readiness_report(paths.summary, paths.output, generated_at=generated_at(), allowed_output_root=paths.output.parent)

            self.assertFalse(paths.output.exists())

    def test_metricsMissingDoesNotPublishReport(self):
        with workspace() as paths:
            summary = valid_summary()
            del summary["metrics"]
            paths.summary.write_text(json.dumps(summary), encoding="utf-8")

            with self.assertRaises(Exception):
                generate_promotion_readiness_report(paths.summary, paths.output, generated_at=generated_at(), allowed_output_root=paths.output.parent)

            self.assertFalse(paths.output.exists())

    def test_writesTempFileBeforeFinalReport(self):
        with workspace() as paths:
            payload = valid_payload()
            seen = []

            def validate(path):
                seen.append(path.name == "promotion-review-readiness-report.json.tmp" and not paths.output.exists())
                return validate_promotion_readiness_report_file(path)

            with patch("offline_evaluation.generate_promotion_readiness_report.validate_promotion_readiness_report_file", validate):
                publish_promotion_readiness_report(payload, paths.output, allowed_output_root=paths.output.parent)

            self.assertEqual([True], seen)

    def test_validatesTempFileBeforePublish(self):
        with workspace() as paths:
            calls = []

            def validate(path):
                calls.append(path)
                return validate_promotion_readiness_report_file(path)

            with patch("offline_evaluation.generate_promotion_readiness_report.validate_promotion_readiness_report_file", validate):
                publish_promotion_readiness_report(valid_payload(), paths.output, allowed_output_root=paths.output.parent)

            self.assertEqual([paths.output.with_name("promotion-review-readiness-report.json.tmp")], calls)

    def test_atomicMovePublishesOnlyAfterValidation(self):
        with workspace() as paths:
            state = []

            def validate(path):
                state.append((path.exists(), paths.output.exists()))
                return validate_promotion_readiness_report_file(path)

            with patch("offline_evaluation.generate_promotion_readiness_report.validate_promotion_readiness_report_file", validate):
                publish_promotion_readiness_report(valid_payload(), paths.output, allowed_output_root=paths.output.parent)

            self.assertEqual([(True, False)], state)
            self.assertTrue(paths.output.exists())
            self.assertFalse(paths.output.with_name("promotion-review-readiness-report.json.tmp").exists())

    def test_validationFailureDoesNotOverwritePreviousValidReport(self):
        with workspace() as paths:
            paths.output.parent.mkdir(parents=True, exist_ok=True)
            existing = valid_payload()
            paths.output.write_text(existing, encoding="utf-8")

            with self.assertRaises(Exception):
                publish_promotion_readiness_report("{not-json}", paths.output, allowed_output_root=paths.output.parent)

            self.assertEqual(existing, paths.output.read_text(encoding="utf-8"))
            self.assertFalse(paths.output.with_name("promotion-review-readiness-report.json.tmp").exists())

    def test_rejectsOutputPathOutsideLocalGeneratedPromotionReadiness(self):
        with workspace() as paths:
            outside = paths.root / "outside" / "promotion-review-readiness-report.json"

            with self.assertRaises(PromotionReadinessGenerationError):
                publish_promotion_readiness_report(valid_payload(), outside, allowed_output_root=paths.output.parent)

    def test_rejectsOutputPathNotNamedReviewReadinessReport(self):
        with workspace() as paths:
            with self.assertRaises(PromotionReadinessGenerationError):
                publish_promotion_readiness_report(valid_payload(), paths.output.with_name("promotion-readiness-report.json"), allowed_output_root=paths.output.parent)

    def test_allowsDefaultLocalGeneratedReportPath(self):
        self.assertEqual(DEFAULT_OUTPUT, _assert_allowed_output_path(DEFAULT_OUTPUT))
        self.assertEqual(DEFAULT_OUTPUT_ROOT, DEFAULT_OUTPUT.parent)
        self.assertIn("current-summary.json", str(DEFAULT_SHADOW_SUMMARY))

    def test_readinessStatusAllowsOnlyReviewStatuses(self):
        self.assertEqual({"INSUFFICIENT_DATA", "NOT_REVIEWABLE", "REVIEWABLE"}, READINESS_STATUSES)
        report = build_report()
        for status in ("DIAGNOSTIC_ONLY", "APPROVED", "PROMOTED", "READY_FOR_PRODUCTION", "DEPLOYABLE"):
            broken = dict(report)
            broken["readinessStatus"] = status
            with self.assertRaises(PromotionReadinessValidationError):
                validate_promotion_review_readiness_report(broken)

    def test_reportDoesNotContainPromotionApprovalLanguage(self):
        self.assertMaskedPayloadDoesNotContain("APPROVED", "PROMOTED", "READY_FOR_PRODUCTION", "DEPLOYABLE")

    def test_reportDoesNotContainThresholdRecommendationLanguage(self):
        self.assertMaskedPayloadDoesNotContain("CHANGE_THRESHOLD", "RECOMMENDED_THRESHOLD", "THRESHOLD_RECOMMENDATION")

    def test_reviewableStatusDoesNotContainPromotionApprovalLanguage(self):
        payload = valid_payload()
        self.assertIn('"readinessStatus":"REVIEWABLE"', payload)
        self.assertNotIn("APPROVED", masked_payload(payload))
        self.assertNotIn("PROMOTED", masked_payload(payload))

    def test_reportDoesNotContainDecisioningPaymentOrAnalystActionLanguage(self):
        self.assertMaskedPayloadDoesNotContain(
            "PAYMENT_AUTHORIZED",
            "AUTO_APPROVE",
            "AUTO_DECLINE",
            "BLOCK_TRANSACTION",
            "ANALYST_RECOMMENDATION",
        )

    def test_minimumPopulationCheckDoesNotUseThresholdRecommendationNaming(self):
        payload = valid_payload(minimum_diagnostic_evidence_records=30)
        self.assertIn("minimumDiagnosticEvidenceRecords", payload)
        for term in ("promotionThreshold", "approvalThreshold", "modelThreshold", "decisionThreshold", "recommendedThreshold", "thresholdRecommendation"):
            self.assertNotIn(term, payload)

    def test_reportDoesNotLeakRawIdentifiersOrLabels(self):
        self.assertPayloadDoesNotContain(
            "transactionReference",
            "evaluationRecordId",
            "customerId",
            "accountId",
            "cardId",
            "deviceId",
            "merchantId",
            "analystId",
            "rawPayload",
            "rawFeatureVector",
            "rawMlRequest",
            "rawMlResponse",
            "groundTruth",
            "trainingLabel",
            "finalDecision",
        )

    def test_sourceDoesNotIntroduceRuntimeOrDecisioningCreep(self):
        source = fdp111_source()
        for term in (
            "KafkaTemplate",
            "KafkaProducer",
            "@Scheduled",
            "cron",
            "APScheduler",
            "ModelRegistry",
            "saveModelArtifact",
            "paymentAuthorization",
            "approveTransaction",
            "declineTransaction",
            "blockTransaction",
            "thresholdRecommendation",
            "recommendedThreshold",
            "MongoClient",
            "pymongo",
            "read_fdp102_jsonl",
            "build_evaluation_report",
        ):
            self.assertNotIn(term, source)

    def test_makefileTargetUsesGeneratorWithoutStartingComposeOrApi(self):
        makefile = MAKEFILE.read_text(encoding="utf-8")
        target = make_target(makefile, "promotion-readiness-report")

        self.assertIn("promotion-readiness-report: check-python", target)
        self.assertIn("python -m offline_evaluation.generate_promotion_readiness_report", target)
        self.assertIn("--shadow-summary ../deployment/local-generated/shadow-performance/current-summary.json", target)
        self.assertIn("--output ../deployment/local-generated/promotion-readiness/promotion-review-readiness-report.json", target)
        self.assertNotIn("docker compose", target)
        self.assertNotIn("curl", target)
        self.assertNotIn("npm", target)

    def test_docsDescribeDiagnosticOnlyBoundary(self):
        doc = DOC.read_text(encoding="utf-8")
        for text in (
            "FDP-111 is a diagnostic report only.",
            "FDP-111 consumes existing bounded artifacts.",
            "FDP-111 does not recompute metrics from raw data.",
            "FDP-111 does not approve promotion.",
            "FDP-111 does not recommend threshold changes.",
            "FDP-111 does not change scoring.",
            "FDP-111 does not authorize payments.",
            "FDP-111 does not recommend analyst action.",
            "FDP-111 does not add API, OpenAPI, UI, workflow, scheduler, or Kafka triggers.",
            "Minimum diagnostic evidence is a review sufficiency check, not a model threshold and not a promotion threshold.",
        ):
            self.assertIn(text, doc)

    def test_doesNotAddApiOpenApiOrUiSurface(self):
        self.assertFalse(any("promotion-readiness" in path.read_text(encoding="utf-8") for path in OPENAPI_ROOT.rglob("*.yaml")))
        self.assertFalse(any("PromotionReviewReadiness" in path.read_text(encoding="utf-8") for path in UI_ROOT.rglob("*.jsx") if path.is_file()))

    def assertPayloadDoesNotContain(self, *terms):
        payload = valid_payload()
        for term in terms:
            self.assertNotIn(term, payload)

    def assertMaskedPayloadDoesNotContain(self, *terms):
        payload = masked_payload(valid_payload())
        for term in terms:
            self.assertNotIn(term, payload)


def valid_summary():
    return json.loads(FIXTURE.read_text(encoding="utf-8"))


def build_report(**kwargs):
    return build_promotion_review_readiness_report(valid_summary(), generated_at=generated_at(), **kwargs)


def valid_payload(**kwargs):
    return promotion_review_readiness_report_json(build_report(**kwargs))


def masked_payload(payload):
    allowed_terms = (
        "APPROVED_FOR_COMPARE_AND_SHADOW",
        "NOT_PRODUCTION_APPROVAL_TRUE",
        "NOT_PROMOTION_APPROVAL_TRUE",
        "NOT_THRESHOLD_RECOMMENDATION_TRUE",
        "NOT_PAYMENT_AUTHORIZATION_TRUE",
        "notPromotionApproval",
        "notThresholdRecommendation",
        "notPaymentAuthorization",
    )
    masked = payload
    for term in allowed_terms:
        masked = masked.replace(term, "")
    return masked


def generate(paths):
    generate_promotion_readiness_report(
        paths.summary,
        paths.output,
        generated_at=generated_at(),
        allowed_output_root=paths.output.parent,
    )
    return json.loads(paths.output.read_text(encoding="utf-8"))


def generated_at():
    return "2026-06-13T00:00:00Z"


def fdp111_source():
    return "\n".join(path.read_text(encoding="utf-8") for path in (GENERATOR_SOURCE, SCHEMA_SOURCE))


def make_target(makefile, target):
    marker = f"{target}:"
    start = makefile.index(marker)
    end = makefile.find("\n\n", start)
    return makefile[start:] if end < 0 else makefile[start:end]


class workspace:
    def __enter__(self):
        self._temp = tempfile.TemporaryDirectory()
        self.root = Path(self._temp.name)
        self.summary = self.root / "local-generated" / "shadow-performance" / "current-summary.json"
        self.output = self.root / "local-generated" / "promotion-readiness" / "promotion-review-readiness-report.json"
        self.summary.parent.mkdir(parents=True)
        self.summary.write_text(json.dumps(valid_summary(), sort_keys=True), encoding="utf-8")
        return self

    def __exit__(self, exc_type, exc, tb):
        self._temp.cleanup()


if __name__ == "__main__":
    unittest.main()
