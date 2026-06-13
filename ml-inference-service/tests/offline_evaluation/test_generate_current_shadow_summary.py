import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from fdp103_fixtures import GENERATED_AT, jsonl, record
from offline_evaluation.generate_current_shadow_summary import (
    CurrentSummaryGenerationError,
    DEFAULT_DATASET_JSONL,
    DEFAULT_MODEL_METADATA,
    DEFAULT_OUTPUT,
    DEFAULT_OUTPUT_ROOT,
    _assert_allowed_output_path,
    generate_current_shadow_summary,
    main,
    publish_current_summary,
    validate_current_summary_file,
)
from offline_evaluation.shadow_performance_schema import BANNER, SUMMARY_TYPE, SUMMARY_VERSION
from offline_evaluation.shadow_performance_writer import write_shadow_performance_summary


ROOT = Path(__file__).resolve().parents[3]
GENERATOR_SOURCE = ROOT / "ml-inference-service" / "offline_evaluation" / "generate_current_shadow_summary.py"
MAKEFILE = ROOT / "Makefile"
DOC = ROOT / "docs" / "architecture" / "shadow_performance_summary_generation_job.md"
WINDOWS_WRAPPER = ROOT / "scripts" / "shadow-performance-summary.cmd"
LOCAL_DEMO_INPUT_ROOT = ROOT / "deployment" / "local-demo-inputs" / "shadow-performance"
SYNTHETIC_DATASET = LOCAL_DEMO_INPUT_ROOT / "fdp102-feedback-dataset.synthetic.jsonl"
SYNTHETIC_METADATA = LOCAL_DEMO_INPUT_ROOT / "model-metadata.synthetic.json"


class CurrentShadowSummaryGenerationTest(unittest.TestCase):
    def test_successfulGenerationWritesValidCurrentSummary(self):
        with workspace() as paths:
            generate(paths)

            self.assertTrue(paths.output.is_file())
            self.assertEqual(validate_current_summary_file(paths.output), json.loads(paths.output.read_text()))

    def test_generatedSummaryIsShadowPerformanceSummaryV1(self):
        with workspace() as paths:
            summary = generate(paths)

            self.assertEqual(SUMMARY_TYPE, summary["summaryType"])
            self.assertEqual(SUMMARY_VERSION, summary["summaryVersion"])

    def test_generatedSummaryPassesValidator(self):
        with workspace() as paths:
            generate(paths)

            self.assertEqual(json.loads(paths.output.read_text()), validate_current_summary_file(paths.output))

    def test_generatedSummaryCanBeReadByFdp108Provider(self):
        with workspace() as paths:
            generate(paths)

            self.assertEqual("current-summary.json", paths.output.name)
            self.assertEqual("SHADOW_PERFORMANCE_SUMMARY_V1", validate_current_summary_file(paths.output)["summaryType"])

    def test_generatedSummaryUsesDiagnosticOnlyGovernance(self):
        with workspace() as paths:
            summary = generate(paths)

            self.assertEqual("DIAGNOSTIC_ONLY", summary["governance"]["governanceStatus"])
            self.assertTrue(summary["governance"]["diagnosticOnly"])
            self.assertTrue(summary["governance"]["notProductionApproval"])
            self.assertTrue(summary["governance"]["notPromotionApproval"])
            self.assertTrue(summary["governance"]["notThresholdRecommendation"])
            self.assertTrue(summary["governance"]["notPaymentAuthorization"])
            self.assertTrue(summary["governance"]["notAutomaticDecisioning"])

    def test_writesTempFileBeforeFinalPath(self):
        with workspace() as paths:
            payload = valid_payload(paths)
            seen_temp_before_final = []

            def validate(path):
                seen_temp_before_final.append(path.name == "current-summary.json.tmp" and not paths.output.exists())
                return validate_current_summary_file(path)

            with patch("offline_evaluation.generate_current_shadow_summary.validate_current_summary_file", validate):
                publish_current_summary(payload, paths.output, allowed_output_root=paths.output.parent)

            self.assertEqual([True], seen_temp_before_final)

    def test_validatesTempFileBeforePublish(self):
        with workspace() as paths:
            payload = valid_payload(paths)
            calls = []

            def validate(path):
                calls.append(path)
                return validate_current_summary_file(path)

            with patch("offline_evaluation.generate_current_shadow_summary.validate_current_summary_file", validate):
                publish_current_summary(payload, paths.output, allowed_output_root=paths.output.parent)

            self.assertEqual([paths.output.with_name("current-summary.json.tmp")], calls)

    def test_atomicMovePublishesOnlyAfterValidation(self):
        with workspace() as paths:
            payload = valid_payload(paths)
            state = []

            def validate(path):
                state.append((path.exists(), paths.output.exists()))
                return validate_current_summary_file(path)

            with patch("offline_evaluation.generate_current_shadow_summary.validate_current_summary_file", validate):
                publish_current_summary(payload, paths.output, allowed_output_root=paths.output.parent)

            self.assertEqual([(True, False)], state)
            self.assertTrue(paths.output.exists())
            self.assertFalse(paths.output.with_name("current-summary.json.tmp").exists())

    def test_failedGenerationDoesNotOverwriteExistingSummary(self):
        with workspace() as paths:
            existing = valid_payload(paths)
            paths.output.parent.mkdir(parents=True, exist_ok=True)
            paths.output.write_text(existing, encoding="utf-8")

            with self.assertRaises(Exception):
                publish_current_summary("{not-json}", paths.output, allowed_output_root=paths.output.parent)

            self.assertEqual(existing, paths.output.read_text(encoding="utf-8"))

    def test_partialTempFileIsNotTreatedAsCurrentSummary(self):
        with workspace() as paths:
            temp_path = paths.output.with_name("current-summary.json.tmp")
            temp_path.parent.mkdir(parents=True, exist_ok=True)
            temp_path.write_text("{partial", encoding="utf-8")

            self.assertFalse(paths.output.exists())
            with self.assertRaises(FileNotFoundError):
                validate_current_summary_file(paths.output)

    def test_missingInputDoesNotWriteSummary(self):
        with workspace() as paths:
            paths.dataset.unlink()

            with self.assertRaises(CurrentSummaryGenerationError):
                generate(paths)

            self.assertFalse(paths.output.exists())

    def test_invalidEvaluationDoesNotWriteSummary(self):
        with workspace() as paths:
            paths.dataset.write_text("{not-json}\n", encoding="utf-8")

            with self.assertRaises(Exception):
                generate(paths)

            self.assertFalse(paths.output.exists())

    def test_invalidModelCardDoesNotWriteSummary(self):
        with workspace() as paths:
            metadata = json.loads(paths.metadata.read_text())
            metadata["approvedFor"] = ["PRODUCTION_DECISIONING"]
            paths.metadata.write_text(json.dumps(metadata), encoding="utf-8")

            with self.assertRaises(Exception):
                generate(paths)

            self.assertFalse(paths.output.exists())

    def test_invalidShadowSummaryDoesNotWriteSummary(self):
        with workspace() as paths:
            with patch(
                    "offline_evaluation.generate_current_shadow_summary.build_shadow_performance_summary",
                    return_value={"summaryType": "BROKEN"},
            ):
                with self.assertRaises(Exception):
                    generate(paths)

            self.assertFalse(paths.output.exists())

    def test_validationFailureDoesNotPublishCurrentSummary(self):
        with workspace() as paths:
            payload = valid_payload(paths)
            with patch(
                    "offline_evaluation.generate_current_shadow_summary.validate_current_summary_file",
                    side_effect=CurrentSummaryGenerationError("validation failed"),
            ):
                with self.assertRaises(CurrentSummaryGenerationError):
                    publish_current_summary(payload, paths.output, allowed_output_root=paths.output.parent)

            self.assertFalse(paths.output.exists())

    def test_commandExitsNonZeroOnFailure(self):
        with workspace() as paths:
            code = main([
                "--dataset-jsonl", str(paths.root / "missing.jsonl"),
                "--model-metadata", str(paths.metadata),
                "--output", str(paths.output),
                "--allow-output-root", str(paths.output.parent),
            ])

            self.assertEqual(1, code)
            self.assertFalse(paths.output.exists())

    def test_doesNotFallbackToDemoSummary(self):
        with workspace() as paths:
            paths.dataset.unlink()

            with self.assertRaises(CurrentSummaryGenerationError):
                generate(paths)

            self.assertFalse(paths.output.exists())
            self.assertNotIn("current-summary.demo.json", generator_source())

    def test_doesNotWriteZeroMetricsOnFailure(self):
        with workspace() as paths:
            existing = valid_payload(paths)
            paths.output.parent.mkdir(parents=True, exist_ok=True)
            paths.output.write_text(existing, encoding="utf-8")
            paths.dataset.write_text("{not-json}\n", encoding="utf-8")

            with self.assertRaises(Exception):
                generate(paths)

            self.assertEqual(existing, paths.output.read_text(encoding="utf-8"))

    def test_doesNotUseDeploymentLocalFixturesAsGenerationInput(self):
        with workspace() as paths:
            fixture_input = paths.root / "deployment" / "local-fixtures" / "shadow-performance" / "input.jsonl"
            fixture_input.parent.mkdir(parents=True)
            fixture_input.write_text(paths.dataset.read_text(), encoding="utf-8")

            with self.assertRaises(CurrentSummaryGenerationError):
                generate_current_shadow_summary(
                    fixture_input,
                    paths.metadata,
                    paths.output,
                    generated_at=GENERATED_AT,
                    allowed_output_root=paths.output.parent,
                )

    def test_defaultLocalInputFixturePathIsSyntheticDemoBoundary(self):
        self.assertIn("local-demo-inputs", str(DEFAULT_DATASET_JSONL))
        self.assertIn("synthetic", DEFAULT_DATASET_JSONL.name)
        self.assertIn("local-demo-inputs", str(DEFAULT_MODEL_METADATA))
        self.assertIn("synthetic", DEFAULT_MODEL_METADATA.name)

    def test_committedSyntheticFixtureContentUsesExplicitSyntheticIds(self):
        self.assertTrue(SYNTHETIC_DATASET.is_file())
        self.assertTrue(SYNTHETIC_METADATA.is_file())

        fixture = SYNTHETIC_DATASET.read_text(encoding="utf-8")
        self.assertIn("synthetic-transaction-reference", fixture)
        self.assertIn("synthetic-evaluation-record", fixture)
        for forbidden in ("txnref-", "eval-", "aaaaaaaa", "bbbbbbbb", "cccccccc", "dddddddd", "eeeeeeee"):
            self.assertNotIn(forbidden, fixture)

    def test_rejectsOutputPathNotNamedCurrentSummaryJson(self):
        with workspace() as paths:
            with self.assertRaises(CurrentSummaryGenerationError):
                publish_current_summary(valid_payload(paths), paths.output.with_name("summary.json"), allowed_output_root=paths.output.parent)

    def test_rejectsOutputOutsideLocalGeneratedShadowPerformance(self):
        with workspace() as paths:
            outside = paths.root / "outside" / "current-summary.json"

            with self.assertRaises(CurrentSummaryGenerationError):
                publish_current_summary(valid_payload(paths), outside, allowed_output_root=paths.output.parent)

    def test_rejectsOutputUnderLocalFixtures(self):
        with workspace() as paths:
            fixture_output = paths.root / "deployment" / "local-fixtures" / "shadow-performance" / "current-summary.json"

            with self.assertRaises(CurrentSummaryGenerationError):
                publish_current_summary(valid_payload(paths), fixture_output, allowed_output_root=paths.output.parent)

    def test_rejectsOutputUnderLocalDemoInputs(self):
        with workspace() as paths:
            input_output = paths.root / "deployment" / "local-demo-inputs" / "shadow-performance" / "current-summary.json"

            with self.assertRaises(CurrentSummaryGenerationError):
                publish_current_summary(valid_payload(paths), input_output, allowed_output_root=paths.output.parent)

    def test_rejectsOutputPathTraversalOutsideAllowedRoot(self):
        with workspace() as paths:
            traversal = paths.output.parent / ".." / "outside" / "current-summary.json"

            with self.assertRaises(CurrentSummaryGenerationError):
                publish_current_summary(valid_payload(paths), traversal, allowed_output_root=paths.output.parent)

    def test_rejectsSymlinkFinalOutputPath(self):
        with workspace() as paths:
            target = paths.root / "target.json"
            target.write_text("{}", encoding="utf-8")
            try:
                paths.output.parent.mkdir(parents=True, exist_ok=True)
                paths.output.symlink_to(target)
            except OSError:
                self.skipTest("symlink creation is not supported in this environment")

            with self.assertRaises(CurrentSummaryGenerationError):
                publish_current_summary(valid_payload(paths), paths.output, allowed_output_root=paths.output.parent)

    def test_rejectsSymlinkTempOutputPath(self):
        with workspace() as paths:
            target = paths.root / "target.tmp"
            target.write_text("{}", encoding="utf-8")
            temp_path = paths.output.with_name("current-summary.json.tmp")
            try:
                paths.output.parent.mkdir(parents=True, exist_ok=True)
                temp_path.symlink_to(target)
            except OSError:
                self.skipTest("symlink creation is not supported in this environment")

            with self.assertRaises(CurrentSummaryGenerationError):
                publish_current_summary(valid_payload(paths), paths.output, allowed_output_root=paths.output.parent)

    def test_rejectsSymlinkParentOutputPath(self):
        with workspace() as paths:
            real_parent = paths.root / "real-output"
            symlink_parent = paths.root / "linked-output"
            try:
                real_parent.mkdir()
                symlink_parent.symlink_to(real_parent, target_is_directory=True)
            except OSError:
                self.skipTest("symlink creation is not supported in this environment")

            with self.assertRaises(CurrentSummaryGenerationError):
                publish_current_summary(
                    valid_payload(paths),
                    symlink_parent / "current-summary.json",
                    allowed_output_root=symlink_parent,
                )

    def test_allowsDefaultLocalGeneratedCurrentSummaryPath(self):
        self.assertEqual(DEFAULT_OUTPUT, _assert_allowed_output_path(DEFAULT_OUTPUT))
        self.assertEqual(DEFAULT_OUTPUT_ROOT, DEFAULT_OUTPUT.parent)

    def test_doesNotUseStaticSummaryProvider(self):
        self.assertNotIn("StaticShadowPerformanceSummaryProvider", generator_source())

    def test_doesNotUseStaleSummaryAsNewOutput(self):
        with workspace() as paths:
            stale = valid_payload(paths)
            paths.output.parent.mkdir(parents=True, exist_ok=True)
            paths.output.write_text(stale, encoding="utf-8")
            paths.metadata.write_text("[]", encoding="utf-8")

            with self.assertRaises(CurrentSummaryGenerationError):
                generate(paths)

            self.assertEqual(stale, paths.output.read_text(encoding="utf-8"))

    def test_finalSummaryDoesNotLeakRawInputs(self):
        with workspace() as paths:
            payload = json.dumps(generate(paths))

            for term in (
                    "rawFDP102Jsonl",
                    "rawEvaluationReport",
                    "rawModelCard",
                    "transactionReference",
                    "evaluationRecordId",
                    "synthetic-transaction-reference",
                    "synthetic-evaluation-record",
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
                    "token",
                    "secret",
                    "stacktrace",
                    "groundTruth",
                    "trainingLabel",
                    "finalDecision",
            ):
                self.assertNotIn(term, payload)

    def test_doesNotCreatePromotionReadiness(self):
        self.assertGeneratedPayloadDoesNotContain("promotion readiness score", "promotionReady", "promotion approved")

    def test_doesNotCreateThresholdRecommendation(self):
        self.assertGeneratedPayloadDoesNotContain("thresholdRecommendation", "recommendedThreshold")

    def test_doesNotCreateProductionApproval(self):
        self.assertGeneratedPayloadDoesNotContain("productionApproved", "production ready")

    def test_doesNotCreatePaymentAuthorization(self):
        self.assertGeneratedPayloadDoesNotContain("paymentAuthorization")

    def test_doesNotCreateAnalystRecommendation(self):
        self.assertGeneratedPayloadDoesNotContain("analystRecommendation")

    def test_doesNotMutateModelRegistry(self):
        self.assertGeneratorSourceDoesNotContain("ModelRegistry", "model_registry_write", "registry.write")

    def test_doesNotMutateModelArtifacts(self):
        self.assertGeneratorSourceDoesNotContain("model_artifact", "write_model_artifact", "save_model")

    def test_doesNotChangeOnlineScoring(self):
        self.assertGeneratorSourceDoesNotContain("FraudInferenceHandler", "score_transaction", "online_scoring")

    def test_doesNotEmitKafkaEvents(self):
        self.assertGeneratorSourceDoesNotContain("KafkaProducer", "KafkaTemplate", "send(")

    def test_doesNotAddSchedulerOrCron(self):
        self.assertGeneratorSourceDoesNotContain("schedule", "cron", "APScheduler")

    def test_doesNotAddUiChanges(self):
        offline_sources = "\n".join(path.read_text(encoding="utf-8") for path in (ROOT / "ml-inference-service" / "offline_evaluation").glob("*.py"))

        self.assertNotIn("analyst-console-ui", offline_sources)
        self.assertNotIn("ShadowPerformanceDashboard", offline_sources)

    def test_makeTargetExistsForManualGeneration(self):
        makefile = MAKEFILE.read_text(encoding="utf-8")

        self.assertIn("shadow-performance-summary:", makefile)
        self.assertIn("python -m offline_evaluation.generate_current_shadow_summary", makefile)

    def test_windowsWrapperExistsForManualGeneration(self):
        wrapper = WINDOWS_WRAPPER.read_text(encoding="utf-8")

        self.assertIn("shadow-performance-summary.ps1", wrapper)
        self.assertIn("powershell", wrapper)

    def test_docsDescribeManualOfflineBoundary(self):
        doc = DOC.read_text(encoding="utf-8")

        for text in (
                "FDP-108 reads current summary",
                "FDP-109 generates current summary",
                "FDP-106 exposes current summary",
                "FDP-107 displays current summary",
                "FDP-109 is manual/local/offline only",
                "scripts\\shadow-performance-summary.cmd",
                "FDP-109 is not production scheduler",
                "FDP-109 is not promotion readiness",
                "FDP-109 is not threshold recommendation",
                "FDP-109 is not production decisioning",
                "FDP-109 is not payment authorization",
                "FDP-109 is not analyst recommendation logic",
                "synthetic demo/local inputs only",
                "not production data",
                "not current runtime data",
                "not exported from real transactions",
                "generated summary must not contain raw transaction references or evaluation record IDs",
                "FDP-109 does not connect to Mongo/Kafka directly",
                "FDP-109 consumes explicit local/offline FDP-102-format input",
        ):
            self.assertIn(text, doc)

    def assertGeneratedPayloadDoesNotContain(self, *terms):
        with workspace() as paths:
            payload = paths.output.read_text(encoding="utf-8") if paths.output.exists() else valid_payload(paths)
            for term in terms:
                self.assertNotIn(term, payload)

    def assertGeneratorSourceDoesNotContain(self, *terms):
        source = generator_source()
        for term in terms:
            self.assertNotIn(term, source)


def generate(paths):
    generate_current_shadow_summary(
        paths.dataset,
        paths.metadata,
        paths.output,
        generated_at=GENERATED_AT,
        review_budget=2,
        top_k=2,
        allowed_output_root=paths.output.parent,
    )
    return json.loads(paths.output.read_text(encoding="utf-8"))


def valid_payload(_paths):
    with workspace() as source_paths:
        summary = generate(source_paths)
        return write_shadow_performance_summary(summary)


def generator_source():
    return GENERATOR_SOURCE.read_text(encoding="utf-8")


def valid_dataset_jsonl():
    return jsonl(
        record(
            evaluationRecordId="eval-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            transactionReference="txnref-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            evaluationLabel="ANALYST_CONFIRMED_FRAUD",
            mlRiskLevel="HIGH",
            rulesRiskLevel="LOW",
        ),
        record(
            evaluationRecordId="eval-bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
            transactionReference="txnref-bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
            evaluationLabel="ANALYST_MARKED_LEGITIMATE",
            mlRiskLevel="HIGH",
            rulesRiskLevel="LOW",
        ),
        record(
            evaluationRecordId="eval-cccccccccccccccccccccccccccccccc",
            transactionReference="txnref-cccccccccccccccccccccccccccccccc",
            evaluationLabel="ANALYST_CONFIRMED_FRAUD",
            mlRiskLevel=None,
            mlScoreBucket=None,
            mlEngineStatus="UNAVAILABLE",
            rulesRiskLevel="HIGH",
            projectionStatus="MISSING",
        ),
        record(
            evaluationRecordId="eval-dddddddddddddddddddddddddddddddd",
            transactionReference="txnref-dddddddddddddddddddddddddddddddd",
            evaluationLabel="NOT_EVALUATION_ELIGIBLE",
            mlRiskLevel="LOW",
            rulesRiskLevel=None,
            rulesScoreBucket=None,
            rulesEngineStatus="UNAVAILABLE",
        ),
        metadata_overrides={"rawRowsRead": 4, "recordsReturned": 4},
    )


def valid_metadata():
    return {
        "modelName": "python-logistic-fraud-model",
        "modelVersion": "2026-04-21.trained.v1",
        "modelFamily": "LOGISTIC_REGRESSION",
        "featureContractVersion": "2026-04-22.v1",
        "intendedUse": ["SHADOW_FRAUD_RISK_DIAGNOSTICS", "RULE_VS_ML_DIAGNOSTICS"],
        "notIntendedUse": ["OFFLINE_ONLY"],
        "approvedFor": ["COMPARE", "SHADOW"],
    }


class workspace:
    def __enter__(self):
        self._temp = tempfile.TemporaryDirectory()
        root = Path(self._temp.name)
        self.root = root
        self.dataset = root / "local-demo-inputs" / "shadow-performance" / "fdp102-feedback-dataset.synthetic.jsonl"
        self.metadata = root / "local-demo-inputs" / "shadow-performance" / "model-metadata.synthetic.json"
        self.output = root / "local-generated" / "shadow-performance" / "current-summary.json"
        self.dataset.parent.mkdir(parents=True)
        self.dataset.write_text(valid_dataset_jsonl(), encoding="utf-8")
        self.metadata.write_text(json.dumps(valid_metadata(), sort_keys=True), encoding="utf-8")
        return self

    def __exit__(self, exc_type, exc, tb):
        self._temp.cleanup()


if __name__ == "__main__":
    unittest.main()
