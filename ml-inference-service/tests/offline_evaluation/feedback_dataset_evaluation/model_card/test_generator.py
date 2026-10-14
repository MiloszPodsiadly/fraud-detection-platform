import json
import os
import tempfile
import threading
import unittest
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from unittest.mock import patch

from app.models.model_loader import load_validated_model_artifact
from offline_evaluation.feedback_dataset_evaluation.dataset_reader import read_feedback_dataset_jsonl
from offline_evaluation.feedback_dataset_evaluation.model_card.artifact_reader import (
    read_validated_model_card_artifact_set,
)
from offline_evaluation.feedback_dataset_evaluation.model_card import writer as model_card_writer
from offline_evaluation.feedback_dataset_evaluation.model_card.generator import (
    build_model_card_from_artifacts,
    generate_model_card_artifact_set,
)
from offline_evaluation.feedback_dataset_evaluation.model_card.schema import (
    EVALUATION_AVAILABLE,
    EVALUATION_NOT_AVAILABLE,
    ModelCardValidationError,
)
from offline_evaluation.feedback_dataset_evaluation.model_card.run_model_card_generation import main
from offline_evaluation.feedback_dataset_evaluation.model_card.writer import (
    MODEL_CARD_FILENAME,
    MODEL_CARD_MANIFEST_FILENAME,
    MODEL_CARD_MARKDOWN_FILENAME,
    build_model_card_manifest,
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
from offline_evaluation.feedback_dataset_evaluation.report_writer import (
    build_artifact_manifest,
    report_json,
)

try:
    from feedback_dataset_evaluation.feedback_dataset_fixtures import GENERATED_AT, jsonl, record
except ModuleNotFoundError:
    from feedback_dataset_fixtures import GENERATED_AT, jsonl, record


CARD_GENERATED_AT = "2026-06-10T00:00:01Z"
CANONICAL_MODEL_ARTIFACT = Path(__file__).resolve().parents[4] / "app" / "model_artifact.json"
GOLDEN_ARTIFACT_DIR = (
    Path(__file__).resolve().parents[5]
    / "alert-service"
    / "src"
    / "test"
    / "resources"
    / "fixtures"
    / "model-card"
    / "python-generated-available-v1"
)


class ModelCardGeneratorTest(unittest.TestCase):
    def setUp(self):
        self._temporary_directory = tempfile.TemporaryDirectory()
        self.root = Path(self._temporary_directory.name)
        self.model_artifact = self.root / "model_artifact.json"
        self.model_artifact.write_bytes(CANONICAL_MODEL_ARTIFACT.read_bytes())
        loaded = load_validated_model_artifact(self.model_artifact)
        artifact_identity = loaded.artifact_identity
        self.identity = ModelEvaluationIdentity(
            artifact_identity.model_name,
            artifact_identity.model_version,
            artifact_identity.feature_contract_version,
            artifact_identity.artifact_sha256,
        )
        self.dataset_path = self.root / "feedback-dataset.jsonl"
        self.dataset_path.write_text(jsonl(record(
            mlModelName=self.identity.model_name,
            mlModelVersion=self.identity.model_version,
            mlFeatureContractVersion=self.identity.feature_contract_version,
            mlModelArtifactSha256=self.identity.model_artifact_sha256,
        )), encoding="utf-8", newline="\n")
        self.evaluation_dir = self.root / "model-evaluation"
        self._write_evaluation_artifact_set(self.identity)

    def tearDown(self):
        self._temporary_directory.cleanup()

    def test_builds_exact_artifact_card_and_preserves_verification_levels(self):
        claimed = build_model_card_from_artifacts(
            self.model_artifact,
            CARD_GENERATED_AT,
            self.evaluation_dir,
        )
        verified = build_model_card_from_artifacts(
            self.model_artifact,
            CARD_GENERATED_AT,
            self.evaluation_dir,
            self.dataset_path,
        )

        self.assertEqual(self.identity.as_subject(), claimed["subject"])
        self.assertEqual(EVALUATION_AVAILABLE, claimed["evaluationEvidence"]["status"])
        self.assertEqual(
            ModelEvaluationArtifactVerificationLevel.CLAIM_ONLY.value,
            claimed["evaluationEvidence"]["sourceDatasetVerificationLevel"],
        )
        self.assertEqual(
            ModelEvaluationArtifactVerificationLevel.VERIFIED_AGAINST_SOURCE_BYTES.value,
            verified["evaluationEvidence"]["sourceDatasetVerificationLevel"],
        )
        self.assertEqual(claimed["evaluationSummary"], verified["evaluationSummary"])

    def test_absent_evaluation_is_explicit_but_configured_corruption_fails_closed(self):
        card = build_model_card_from_artifacts(self.model_artifact, CARD_GENERATED_AT)
        self.assertEqual(EVALUATION_NOT_AVAILABLE, card["evaluationEvidence"]["status"])
        self.assertIsNone(card["evaluationSummary"])

        summary_path = self.evaluation_dir / "model_evaluation_summary.json"
        summary_path.write_text("{}", encoding="utf-8")
        with self.assertRaises(ValueError):
            build_model_card_from_artifacts(
                self.model_artifact,
                CARD_GENERATED_AT,
                self.evaluation_dir,
            )

    def test_rejects_identity_mismatch_including_same_logical_model_with_different_sha(self):
        mismatched = ModelEvaluationIdentity(
            self.identity.model_name,
            "different-version",
            self.identity.feature_contract_version,
            "b" * 64,
        )
        self._write_evaluation_artifact_set(mismatched)
        with self.assertRaisesRegex(ModelCardValidationError, "does not match"):
            build_model_card_from_artifacts(
                self.model_artifact,
                CARD_GENERATED_AT,
                self.evaluation_dir,
            )

        self._write_evaluation_artifact_set(self.identity)
        sha_variant = self.root / "same-logical-model-different-bytes.json"
        sha_variant.write_bytes(self.model_artifact.read_bytes() + b"\n")
        with self.assertRaisesRegex(ModelCardValidationError, "does not match"):
            build_model_card_from_artifacts(
                sha_variant,
                CARD_GENERATED_AT,
                self.evaluation_dir,
            )

    def test_rejects_platform_evaluation_artifact_identity(self):
        manifest_path = self.evaluation_dir / "manifest.json"
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        manifest["reportType"] = "FEEDBACK_DATASET_OFFLINE_EVALUATION_V1"
        manifest_path.write_text(json.dumps(manifest), encoding="utf-8")

        with self.assertRaisesRegex(ValueError, "reportType unsupported"):
            build_model_card_from_artifacts(
                self.model_artifact,
                CARD_GENERATED_AT,
                self.evaluation_dir,
            )

    def test_publishes_deterministic_canonical_artifact_sets(self):
        first = self._generate("first")
        second = self._generate("second")

        first_bytes = {path.name: path.read_bytes() for path in first.values()}
        second_bytes = {path.name: path.read_bytes() for path in second.values()}
        self.assertEqual(first_bytes, second_bytes)
        validated = read_validated_model_card_artifact_set(first["manifest"].parent)
        self.assertEqual(self.identity.as_subject(), dict(validated.card["subject"]))

    def test_python_generated_available_artifact_matches_cross_language_golden_bytes(self):
        generated = self._generate("cross-language-golden")

        self.assertEqual(
            {path.name for path in generated.values()},
            {path.name for path in GOLDEN_ARTIFACT_DIR.iterdir()},
        )
        for generated_path in generated.values():
            self.assertEqual(
                generated_path.read_bytes(),
                (GOLDEN_ARTIFACT_DIR / generated_path.name).read_bytes(),
                generated_path.name,
            )
        validated = read_validated_model_card_artifact_set(GOLDEN_ARTIFACT_DIR)
        self.assertEqual(EVALUATION_AVAILABLE, validated.card["evaluationEvidence"]["status"])
        self.assertGreater(validated.card["evaluationSummary"]["population"]["recordsEvaluated"], 0)

    def test_command_line_entrypoint_uses_explicit_artifact_paths(self):
        output = self.root / "command-line-output"

        result = main([
            "--model-artifact", str(self.model_artifact),
            "--model-evaluation-dir", str(self.evaluation_dir),
            "--source-dataset", str(self.dataset_path),
            "--output-dir", str(output),
            "--allow-output-root", str(self.root),
            "--generated-at", CARD_GENERATED_AT,
        ])

        self.assertEqual(0, result)
        read_validated_model_card_artifact_set(output)

    def test_reader_rejects_tampering_partial_sets_stale_manifests_and_duplicate_keys(self):
        cases = (
            "card",
            "markdown",
            "partial",
            "duplicate-manifest",
            "duplicate-card-key",
            "traversal",
            "extra-file",
            "noncanonical-card-with-recomputed-manifest",
            "markdown-with-recomputed-manifest",
            "manifest-order",
            "manifest-unsupported-field",
        )
        for case in cases:
            with self.subTest(case=case):
                paths = self._generate(f"tamper-{case}")
                output = paths["manifest"].parent
                if case == "card":
                    paths["modelCardJson"].write_text("{}", encoding="utf-8")
                elif case == "markdown":
                    paths["modelCardMarkdown"].write_text("tampered", encoding="utf-8")
                elif case == "partial":
                    paths["modelCardMarkdown"].unlink()
                elif case == "duplicate-manifest":
                    paths["manifest"].write_text(
                        '{"artifactSetVersion":"one","artifactSetVersion":"two"}',
                        encoding="utf-8",
                    )
                elif case == "duplicate-card-key":
                    paths["modelCardJson"].write_text(
                        '{"schemaVersion":"one","schemaVersion":"two"}',
                        encoding="utf-8",
                    )
                elif case == "extra-file":
                    (output / "unexpected.txt").write_text("unexpected", encoding="utf-8")
                elif case == "noncanonical-card-with-recomputed-manifest":
                    paths["modelCardJson"].write_text(
                        "  " + paths["modelCardJson"].read_text(encoding="utf-8"),
                        encoding="utf-8",
                    )
                    self._rewrite_model_card_manifest(paths)
                elif case == "markdown-with-recomputed-manifest":
                    paths["modelCardMarkdown"].write_text("# recomputed but not derived\n", encoding="utf-8")
                    self._rewrite_model_card_manifest(paths)
                elif case == "manifest-order":
                    manifest = json.loads(paths["manifest"].read_text(encoding="utf-8"))
                    manifest["files"].reverse()
                    paths["manifest"].write_text(
                        json.dumps(manifest, sort_keys=True, separators=(",", ":")) + "\n",
                        encoding="utf-8",
                    )
                elif case == "manifest-unsupported-field":
                    manifest = json.loads(paths["manifest"].read_text(encoding="utf-8"))
                    manifest["unsupported"] = True
                    paths["manifest"].write_text(
                        json.dumps(manifest, sort_keys=True, separators=(",", ":")) + "\n",
                        encoding="utf-8",
                    )
                else:
                    manifest = json.loads(paths["manifest"].read_text(encoding="utf-8"))
                    manifest["files"][0]["name"] = "../ml_model_card.json"
                    paths["manifest"].write_text(json.dumps(manifest), encoding="utf-8")
                with self.assertRaises(ValueError):
                    read_validated_model_card_artifact_set(output)

    def _rewrite_model_card_manifest(self, paths):
        paths["manifest"].write_text(
            build_model_card_manifest(
                {
                    paths["modelCardJson"]: paths["modelCardJson"].read_text(encoding="utf-8"),
                    paths["modelCardMarkdown"]: paths["modelCardMarkdown"].read_text(encoding="utf-8"),
                },
                CARD_GENERATED_AT,
            ),
            encoding="utf-8",
            newline="\n",
        )

    def test_invalid_timestamp_and_unpaired_source_dataset_are_rejected(self):
        with self.assertRaises(ValueError):
            build_model_card_from_artifacts(self.model_artifact, "not-a-timestamp")
        with self.assertRaisesRegex(ModelCardValidationError, "requires a configured"):
            build_model_card_from_artifacts(
                self.model_artifact,
                CARD_GENERATED_AT,
                source_dataset_path=self.dataset_path,
            )

    def test_publication_failure_leaves_no_partial_artifact_set(self):
        output = self.root / "failed-publication"
        with patch(
                "offline_evaluation.feedback_dataset_evaluation.model_card.writer.os.rename",
                side_effect=OSError("injected publication failure"),
        ):
            with self.assertRaisesRegex(ModelCardValidationError, "publication failed"):
                self._generate(output.name)

        self.assertFalse(output.exists())
        self.assertEqual([], list(self.root.glob(f".{output.name}.staging-*")))
        self.assertFalse((self.root / f".{output.name}.publish.lock").exists())

    def test_concurrent_reader_sees_no_partial_artifact_set_before_directory_publish(self):
        output = self.root / "concurrent-publication"
        real_replace = os.replace
        observed_before_publish = []

        def inspect_before_publish(source, destination):
            with self.assertRaises(ModelCardValidationError):
                read_validated_model_card_artifact_set(output)
            observed_before_publish.append(True)
            return real_replace(source, destination)

        with patch(
                "offline_evaluation.feedback_dataset_evaluation.model_card.writer.os.rename",
                side_effect=inspect_before_publish,
        ):
            self._generate(output.name)

        self.assertEqual([True], observed_before_publish)
        read_validated_model_card_artifact_set(output)

    def test_immutable_version_rejects_existing_empty_and_nonempty_destinations(self):
        for populated in (False, True):
            with self.subTest(populated=populated):
                output = self.root / f"existing-{populated}"
                output.mkdir()
                if populated:
                    (output / "owned.txt").write_text("keep", encoding="utf-8")

                with self.assertRaisesRegex(ModelCardValidationError, "already exists and is immutable"):
                    self._generate(output.name)

                self.assertEqual(["owned.txt"] if populated else [], [path.name for path in output.iterdir()])

    def test_concurrent_publishers_cannot_replace_the_same_version(self):
        output = self.root / "concurrent-version"
        publisher_started = threading.Event()
        allow_publication = threading.Event()
        real_publish = model_card_writer._publish_new_directory

        def block_first_publisher(staging_dir, destination):
            publisher_started.set()
            self.assertTrue(allow_publication.wait(timeout=5))
            real_publish(staging_dir, destination)

        with patch(
                "offline_evaluation.feedback_dataset_evaluation.model_card.writer._publish_new_directory",
                side_effect=block_first_publisher,
        ):
            with ThreadPoolExecutor(max_workers=1) as executor:
                first = executor.submit(self._generate, output.name)
                self.assertTrue(publisher_started.wait(timeout=5))
                with self.assertRaisesRegex(ModelCardValidationError, "already in progress"):
                    self._generate(output.name)
                allow_publication.set()
                first.result(timeout=5)

        read_validated_model_card_artifact_set(output)
        self.assertEqual([], list(self.root.glob(f".{output.name}.staging-*")))
        self.assertFalse((self.root / f".{output.name}.publish.lock").exists())

    def test_failures_before_manifest_and_after_complete_staging_publish_nothing(self):
        cases = (
            (
                "before-manifest",
                "offline_evaluation.feedback_dataset_evaluation.model_card.writer.build_model_card_manifest",
            ),
            (
                "after-complete-staging",
                "offline_evaluation.feedback_dataset_evaluation.model_card.writer._validate_staging_directory",
            ),
        )
        for name, target in cases:
            with self.subTest(name=name), patch(target, side_effect=OSError("injected failure")):
                output = self.root / name
                with self.assertRaises(OSError):
                    self._generate(name)
                self.assertFalse(output.exists())
                self.assertEqual([], list(self.root.glob(f".{name}.staging-*")))
                self.assertFalse((self.root / f".{name}.publish.lock").exists())

    def test_writer_rejects_parent_traversal_and_symlinked_destination_when_supported(self):
        with self.assertRaisesRegex(ModelCardValidationError, "parent traversal"):
            self._generate_to(self.root / "nested" / ".." / "traversal-version")

        target = self.root / "symlink-target"
        target.mkdir()
        link = self.root / "symlink-version"
        try:
            link.symlink_to(target, target_is_directory=True)
        except OSError:
            self.skipTest("directory symlinks are not available")
        with self.assertRaisesRegex(ModelCardValidationError, "symlink"):
            self._generate_to(link)

        real_parent = self.root / "real-parent"
        real_parent.mkdir()
        linked_parent = self.root / "linked-parent"
        try:
            linked_parent.symlink_to(real_parent, target_is_directory=True)
        except OSError:
            self.skipTest("directory symlinks are not available")
        with self.assertRaisesRegex(ModelCardValidationError, "symlink"):
            self._generate_to(linked_parent / "version")

    def test_outputs_exclude_raw_dataset_rows_and_model_payload(self):
        paths = self._generate("safe-output")
        combined = b"\n".join(path.read_bytes() for path in paths.values()).lower()
        for forbidden in (b"eval_", b"txnref_", b"modeldatabase64", b'"weights"'):
            self.assertNotIn(forbidden, combined)

    def test_markdown_presents_verified_metrics_ranking_disagreement_and_usage_without_recalculation(self):
        paths = self._generate("complete-markdown")
        markdown = paths["modelCardMarkdown"].read_text(encoding="utf-8")

        for expected in (
            "### Source dataset",
            "### Evaluation population",
            "### Confusion matrix",
            "- True positive: `1`",
            "- False positive: `0`",
            "- Precision: available=`true`, value=`1.0`, reason=`NONE`",
            "- False-positive rate: available=`false`, value=`UNAVAILABLE`, reason=`NO_ACTUAL_NEGATIVES`",
            "### Precision at K",
            "requestedK=`10`, actualK=`1`, available=`true`, value=`1.0`",
            "### Recall at K",
            "### Rules-vs-ML disagreement",
            "- BOTH_HIGH: `1`",
            "### Intended use",
            "- `EXACT_MODEL_OFFLINE_EVALUATION`",
            "### Not intended use",
            "- `PRIMARY_PRODUCTION_FRAUD_DECISIONING`",
            "### Use limitations",
            "- `DOES_NOT_ESTABLISH_PRODUCTION_READINESS`",
        ):
            self.assertIn(expected, markdown)
        self.assertNotIn("False-positive rate: available=`false`, value=`0", markdown)

    def test_reader_rejects_symlinked_artifact_directory_when_supported(self):
        paths = self._generate("symlink-source")
        link = self.root / "linked-model-card"
        try:
            link.symlink_to(paths["manifest"].parent, target_is_directory=True)
        except OSError:
            self.skipTest("directory symlinks are not available")
        with self.assertRaisesRegex(ModelCardValidationError, "symlink"):
            read_validated_model_card_artifact_set(link)

    def _generate(self, directory_name):
        return self._generate_to(self.root / directory_name)

    def _generate_to(self, output_dir):
        return generate_model_card_artifact_set(
            model_artifact_path=self.model_artifact,
            output_dir=output_dir,
            generated_at=CARD_GENERATED_AT,
            model_evaluation_dir=self.evaluation_dir,
            source_dataset_path=self.dataset_path,
            allow_output_root=self.root,
        )

    def _write_evaluation_artifact_set(self, identity):
        dataset = read_feedback_dataset_jsonl(self.dataset_path)
        summary = build_model_specific_evaluation_summary(dataset, identity, GENERATED_AT)
        self.evaluation_dir.mkdir(exist_ok=True)
        summary_path = self.evaluation_dir / "model_evaluation_summary.json"
        summary_payload = report_json(summary)
        summary_path.write_text(summary_payload, encoding="utf-8", newline="\n")
        manifest_payload = build_artifact_manifest(
            {summary_path: summary_payload},
            GENERATED_AT,
            artifact_set_version=MODEL_EVALUATION_ARTIFACT_SET_VERSION,
            report_type=MODEL_EVALUATION_REPORT_TYPE,
        )
        (self.evaluation_dir / "manifest.json").write_text(
            manifest_payload,
            encoding="utf-8",
            newline="\n",
        )


if __name__ == "__main__":
    unittest.main()
