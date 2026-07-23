import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from offline_evaluation.generate_current_shadow_summary import (
    CurrentSummaryGenerationError,
    DEFAULT_EVALUATION_CARD,
    DEFAULT_EVALUATION_CARD_MANIFEST,
    DEFAULT_OUTPUT,
    DEFAULT_OUTPUT_ROOT,
    _assert_allowed_output_path,
    generate_current_shadow_summary,
    main,
    publish_current_summary,
    validate_current_summary_file,
)
from offline_evaluation.shadow_performance_schema import REPORT_TYPE, SUMMARY_VERSION
from offline_evaluation.shadow_performance_writer import write_shadow_performance_summary
from offline_evaluation.fdp123.evaluation_card.writer import write_evaluation_card_artifacts
from fdp123.evaluation_card.test_schema import valid_evaluation_card


GENERATED_AT = "2026-06-13T02:00:00Z"
ROOT = Path(__file__).resolve().parents[3]
GENERATOR_SOURCE = ROOT / "ml-inference-service" / "offline_evaluation" / "generate_current_shadow_summary.py"


class CurrentShadowSummaryGenerationTest(unittest.TestCase):
    def test_successfulGenerationWritesValidCurrentSummaryV2(self):
        with workspace() as paths:
            summary = generate(paths)

            self.assertTrue(paths.output.is_file())
            self.assertEqual(REPORT_TYPE, summary["reportType"])
            self.assertEqual(SUMMARY_VERSION, summary["summaryVersion"])
            self.assertEqual(validate_current_summary_file(paths.output), json.loads(paths.output.read_text()))

    def test_generatedSummaryCanBeReadByProviderAsV2(self):
        with workspace() as paths:
            generate(paths)

            self.assertEqual("current-summary.json", paths.output.name)
            self.assertEqual(REPORT_TYPE, validate_current_summary_file(paths.output)["reportType"])

    def test_runnerDelegatesToAuthoritativeBuilder(self):
        source = generator_source()

        self.assertIn("build_shadow_performance_summary", source)
        self.assertNotIn("build_evaluation_report", source)
        self.assertNotIn("build_shadow_performance_summary_from_evaluation_report", source)
        self.assertNotIn("FDP-102 dataset JSONL", source)

    def test_invalidEvaluationCardDoesNotWriteSummary(self):
        with workspace() as paths:
            card = json.loads(paths.evaluation_card.read_text())
            card["cardType"] = "FDP103_LEGACY_MODEL_CARD"
            paths.evaluation_card.write_text(json.dumps(card), encoding="utf-8")

            with self.assertRaises(Exception):
                generate(paths)

            self.assertFalse(paths.output.exists())

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

    def test_failedGenerationDoesNotOverwriteExistingSummary(self):
        with workspace() as paths:
            existing = valid_payload(paths)
            paths.output.parent.mkdir(parents=True, exist_ok=True)
            paths.output.write_text(existing, encoding="utf-8")

            with self.assertRaises(Exception):
                publish_current_summary("{not-json}", paths.output, allowed_output_root=paths.output.parent)

            self.assertEqual(existing, paths.output.read_text(encoding="utf-8"))

    def test_missingInputDoesNotWriteSummary(self):
        with workspace() as paths:
            paths.evaluation_card.unlink()

            with self.assertRaises(CurrentSummaryGenerationError):
                generate(paths)

            self.assertFalse(paths.output.exists())

    def test_missingEvaluationCardManifestDoesNotWriteSummary(self):
        with workspace() as paths:
            paths.manifest.unlink()

            with self.assertRaises(CurrentSummaryGenerationError):
                generate(paths)

            self.assertFalse(paths.output.exists())

    def test_hashMismatchDoesNotWriteSummary(self):
        with workspace() as paths:
            card = json.loads(paths.evaluation_card.read_text())
            card["warnings"] = ["LOW_SAMPLE_SIZE", "NO_ACTUAL_POSITIVES"]
            paths.evaluation_card.write_text(json.dumps(card, sort_keys=True), encoding="utf-8")

            with self.assertRaises(CurrentSummaryGenerationError):
                generate(paths)

            self.assertFalse(paths.output.exists())

    def test_commandExitsNonZeroOnFailure(self):
        with workspace() as paths:
            code = main([
                "--evaluation-card", str(paths.root / "missing.json"),
                "--evaluation-card-manifest", str(paths.manifest),
                "--output", str(paths.output),
                "--allow-output-root", str(paths.output.parent),
            ])

            self.assertEqual(1, code)
            self.assertFalse(paths.output.exists())

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

    def test_allowsDefaultLocalGeneratedCurrentSummaryPath(self):
        self.assertEqual(DEFAULT_OUTPUT, _assert_allowed_output_path(DEFAULT_OUTPUT))
        self.assertEqual(DEFAULT_OUTPUT_ROOT, DEFAULT_OUTPUT.parent)
        self.assertIn("platform-recommendation-evaluation-card", str(DEFAULT_EVALUATION_CARD))
        self.assertEqual("manifest.json", DEFAULT_EVALUATION_CARD_MANIFEST.name)

    def test_finalSummaryDoesNotLeakRawInputs(self):
        with workspace() as paths:
            payload = json.dumps(generate(paths))

            for term in (
                    "rawEvaluationReport",
                    "rawModelCard",
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
                    "token",
                    "secret",
                    "stacktrace",
                    "groundTruth",
                    "trainingLabel",
                    "finalDecision",
            ):
                self.assertNotIn(term, payload)


def generate(paths):
    generate_current_shadow_summary(
        paths.evaluation_card,
        paths.manifest,
        paths.output,
        generated_at=GENERATED_AT,
        allowed_output_root=paths.output.parent,
    )
    return json.loads(paths.output.read_text(encoding="utf-8"))


def valid_payload(_paths):
    with workspace() as source_paths:
        summary = generate(source_paths)
        return write_shadow_performance_summary(summary)


def generator_source():
    return GENERATOR_SOURCE.read_text(encoding="utf-8")


class workspace:
    def __enter__(self):
        self._temp = tempfile.TemporaryDirectory()
        self.root = Path(self._temp.name)
        self.evaluation_card = (
            self.root
            / "local-generated"
            / "platform-recommendation-evaluation-card"
            / "platform_recommendation_evaluation_card.json"
        )
        self.manifest = self.evaluation_card.with_name("manifest.json")
        self.output = self.root / "local-generated" / "shadow-performance" / "current-summary.json"
        write_evaluation_card_artifacts(
            valid_evaluation_card(),
            self.evaluation_card.parent,
            allow_output_root=self.evaluation_card.parent,
        )
        return self

    def __exit__(self, exc_type, exc, tb):
        self._temp.cleanup()


if __name__ == "__main__":
    unittest.main()
