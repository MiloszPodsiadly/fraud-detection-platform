from __future__ import annotations

import json
import os
import tempfile
import unittest
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from unittest.mock import patch

from offline_evaluation.feedback_dataset_evaluation.exact_model_shadow_performance.artifact_set import (
    MANIFEST_FILENAME,
    SUMMARY_FILENAME,
    read_exact_model_shadow_artifact_set,
    read_selected_exact_model_shadow_artifact_set,
)
from offline_evaluation.feedback_dataset_evaluation.exact_model_shadow_performance.generator import (
    build_exact_model_shadow_performance,
)
from offline_evaluation.feedback_dataset_evaluation.exact_model_shadow_performance.publisher import (
    ExactModelShadowPublicationDurabilityUnconfirmed,
    publish_exact_model_shadow_artifact_set,
)
from offline_evaluation.feedback_dataset_evaluation.exact_model_shadow_performance.schema import (
    ExactModelShadowPerformanceValidationError,
)

from feedback_dataset_evaluation.exact_model_shadow_performance.fixtures import (
    IDENTITY,
    OTHER_BYTES_IDENTITY,
    SHADOW_GENERATED_AT,
    representative_evidence,
    thaw,
)


class ExactModelShadowArtifactSetTest(unittest.TestCase):
    def setUp(self):
        self._windows_sync_patch = None
        if os.name == "nt":
            self._windows_sync_patch = patch(
                "offline_evaluation.feedback_dataset_evaluation.exact_model_shadow_performance.publisher._sync_directory"
            )
            self._windows_sync_patch.start()

    def tearDown(self):
        if self._windows_sync_patch is not None:
            self._windows_sync_patch.stop()

    def test_publish_read_select_and_idempotent_retry(self):
        summary = self._summary()
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)

            first = publish_exact_model_shadow_artifact_set(summary, root)
            second = publish_exact_model_shadow_artifact_set(summary, root)
            evidence = read_selected_exact_model_shadow_artifact_set(root, self._subject(summary), self._window(summary))

            self.assertEqual(first, second)
            self.assertEqual(summary, thaw(evidence.summary))
            self.assertEqual(first["directory"].name, evidence.selection_key)

    def test_same_logical_identity_different_sha_publishes_independently(self):
        first = self._summary(IDENTITY)
        second = self._summary(OTHER_BYTES_IDENTITY)
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            first_paths = publish_exact_model_shadow_artifact_set(first, root)
            second_paths = publish_exact_model_shadow_artifact_set(second, root)

            self.assertNotEqual(first_paths["directory"], second_paths["directory"])
            self.assertEqual(first, thaw(read_selected_exact_model_shadow_artifact_set(root, self._subject(first), self._window(first)).summary))
            self.assertEqual(second, thaw(read_selected_exact_model_shadow_artifact_set(root, self._subject(second), self._window(second)).summary))

    def test_corrupt_selection_never_falls_back_to_valid_neighbor(self):
        valid = self._summary(IDENTITY)
        corrupt = self._summary(OTHER_BYTES_IDENTITY)
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            publish_exact_model_shadow_artifact_set(valid, root)
            corrupt_paths = publish_exact_model_shadow_artifact_set(corrupt, root)
            manifest = json.loads(corrupt_paths["manifest"].read_text(encoding="utf-8"))
            manifest["files"][0]["sha256"] = "0" * 64
            corrupt_paths["manifest"].write_text(json.dumps(manifest), encoding="utf-8")

            with self.assertRaises(ExactModelShadowPerformanceValidationError):
                read_selected_exact_model_shadow_artifact_set(root, self._subject(corrupt), self._window(corrupt))
            self.assertEqual(valid, thaw(read_selected_exact_model_shadow_artifact_set(root, self._subject(valid), self._window(valid)).summary))

    def test_missing_selection_returns_none(self):
        summary = self._summary()
        with tempfile.TemporaryDirectory() as directory:
            self.assertIsNone(read_selected_exact_model_shadow_artifact_set(
                directory, self._subject(summary), self._window(summary)
            ))

    def test_different_payload_cannot_replace_immutable_selection(self):
        summary = self._summary()
        changed = json.loads(json.dumps(summary))
        changed["generatedAt"] = "2026-06-10T00:00:02Z"
        with tempfile.TemporaryDirectory() as directory:
            publish_exact_model_shadow_artifact_set(summary, directory)
            with self.assertRaisesRegex(ExactModelShadowPerformanceValidationError, "different evidence"):
                publish_exact_model_shadow_artifact_set(changed, directory)

    def test_concurrent_equivalent_publication_converges_on_one_artifact(self):
        summary = self._summary()
        with tempfile.TemporaryDirectory() as directory:
            with ThreadPoolExecutor(max_workers=2) as executor:
                results = list(executor.map(
                    lambda _: publish_exact_model_shadow_artifact_set(summary, directory),
                    range(2),
                ))
            self.assertEqual(results[0], results[1])
            self.assertEqual(1, len([path for path in Path(directory).iterdir() if path.is_dir()]))

    def test_retry_reconfirms_parent_durability_before_reporting_success(self):
        summary = self._summary()
        sync_target = (
            "offline_evaluation.feedback_dataset_evaluation."
            "exact_model_shadow_performance.publisher._sync_directory"
        )
        with tempfile.TemporaryDirectory() as directory, patch(
                sync_target,
                side_effect=[None, OSError("initial parent sync failed"),
                             OSError("retry parent sync failed"), None],
        ) as sync_directory:
            with self.assertRaises(ExactModelShadowPublicationDurabilityUnconfirmed):
                publish_exact_model_shadow_artifact_set(summary, directory)
            self.assertEqual(1, len([path for path in Path(directory).iterdir() if path.is_dir()]))

            with self.assertRaises(ExactModelShadowPublicationDurabilityUnconfirmed):
                publish_exact_model_shadow_artifact_set(summary, directory)

            paths = publish_exact_model_shadow_artifact_set(summary, directory)

            self.assertTrue(paths["summary"].is_file())
            self.assertEqual(4, sync_directory.call_count)

    def test_rejects_tampering_unexpected_files_and_noncanonical_json(self):
        for mutation in ("hash", "unexpected", "noncanonical"):
            with self.subTest(mutation=mutation), tempfile.TemporaryDirectory() as directory:
                paths = publish_exact_model_shadow_artifact_set(self._summary(), directory)
                if mutation == "hash":
                    paths["summary"].write_text(paths["summary"].read_text(encoding="utf-8") + " ", encoding="utf-8")
                elif mutation == "unexpected":
                    (paths["directory"] / "extra.json").write_text("{}", encoding="utf-8")
                else:
                    payload = json.loads(paths["summary"].read_text(encoding="utf-8"))
                    paths["summary"].write_text(json.dumps(payload, indent=2), encoding="utf-8")
                with self.assertRaises(ExactModelShadowPerformanceValidationError):
                    read_exact_model_shadow_artifact_set(paths["directory"])

    def test_rejects_symlink_when_supported(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            paths = publish_exact_model_shadow_artifact_set(self._summary(), root)
            target = root / "linked"
            try:
                target.symlink_to(paths["directory"], target_is_directory=True)
            except OSError:
                self.skipTest("symlink creation is unavailable")
            with self.assertRaises(ExactModelShadowPerformanceValidationError):
                read_exact_model_shadow_artifact_set(target)

    @unittest.skipUnless(os.name == "nt", "Windows-specific fail-closed durability contract")
    def test_windows_publication_fails_when_directory_durability_cannot_be_confirmed(self):
        self._windows_sync_patch.stop()
        self._windows_sync_patch = None
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(ExactModelShadowPublicationDurabilityUnconfirmed):
                publish_exact_model_shadow_artifact_set(self._summary(), directory)
            self.assertEqual(0, len([path for path in Path(directory).iterdir() if path.is_dir()]))

    def _summary(self, identity=IDENTITY):
        return build_exact_model_shadow_performance(representative_evidence(identity), SHADOW_GENERATED_AT)

    @staticmethod
    def _subject(summary):
        return summary["evaluationSummary"]["evaluationSubject"]

    @staticmethod
    def _window(summary):
        return summary["evaluationSummary"]["evaluationWindow"]


if __name__ == "__main__":
    unittest.main()

