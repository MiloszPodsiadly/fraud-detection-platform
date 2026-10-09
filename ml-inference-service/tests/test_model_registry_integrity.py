from __future__ import annotations

import hashlib
import json
import os
import tempfile
import unittest
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from threading import Barrier
from unittest.mock import patch

from app.models.model_loader import ModelConfigurationError, load_validated_model_artifact
from app.registry.model_registry import (
    ModelRegistry,
    ModelRegistryIntegrityError,
    ModelRegistryMutationError,
)


class ModelRegistryIntegrityTest(unittest.TestCase):

    CANONICAL_ARTIFACT = Path(__file__).resolve().parents[1] / "app" / "model_artifact.json"

    def setUp(self) -> None:
        self._temporary_directory = tempfile.TemporaryDirectory()
        self.root = Path(self._temporary_directory.name)
        self.registry = ModelRegistry(self.root / "registry")

    def tearDown(self) -> None:
        self._temporary_directory.cleanup()

    def test_registry_entry_path_escape_is_rejected(self):
        entry = self.registry.register(self._artifact("contained-v1"))
        outside = self._artifact("outside-v1", file_name="outside.json")
        index = self._index()
        index["models"][0]["artifact_path"] = str(outside.resolve())
        self._write_index(index)

        with self.assertRaisesRegex(ModelRegistryIntegrityError, "escapes the configured registry root"):
            self.registry.entries()

        self.assertNotEqual(Path(entry.artifact_path), outside)

    def test_registry_artifact_symlink_is_rejected(self):
        entry = self.registry.register(self._artifact("symlink-v1"))
        registered_path = Path(entry.artifact_path)
        outside = self._artifact("symlink-v1", file_name="outside-symlink-target.json")
        registered_path.unlink()
        try:
            registered_path.symlink_to(outside)
        except OSError:
            with patch.object(
                    type(registered_path),
                    "is_symlink",
                    autospec=True,
                    side_effect=lambda path: path == registered_path,
            ):
                with self.assertRaisesRegex(ModelRegistryIntegrityError, "symbolic link"):
                    self.registry.resolve(entry)
            return

        with self.assertRaisesRegex(ModelRegistryIntegrityError, "symbolic link"):
            self.registry.resolve(entry)

    def test_oversized_artifact_is_rejected(self):
        registry = ModelRegistry(self.root / "bounded-artifact-registry", max_artifact_bytes=64)
        oversized = self.root / "oversized.json"
        oversized.write_bytes(b"{" + b" " * 64 + b"}")

        with self.assertRaisesRegex(ModelConfigurationError, "exceeds maximum size"):
            registry.register(oversized)

    def test_oversized_registry_index_is_rejected(self):
        registry = ModelRegistry(self.root / "bounded-index-registry", max_index_bytes=32)
        registry.root.mkdir(parents=True)
        registry.index_path.write_bytes(b"{" + b" " * 32 + b"}")

        with self.assertRaisesRegex(ModelRegistryIntegrityError, "exceeds maximum size"):
            registry.entries()

    def test_registry_entry_limit_is_enforced_without_losing_existing_entry(self):
        registry = ModelRegistry(self.root / "bounded-entry-registry", max_entries=1)
        first = registry.register(self._artifact("entry-limit-v1"))

        with self.assertRaisesRegex(ModelRegistryIntegrityError, "entry limit"):
            registry.register(self._artifact("entry-limit-v2"))

        self.assertEqual([first], registry.entries())

    def test_corrupt_registry_index_is_not_treated_as_empty(self):
        registered = self.registry.register(self._artifact("corrupt-index-authoritative-v1"))
        registered_path = Path(registered.artifact_path)
        registered_bytes = registered_path.read_bytes()
        self.registry.index_path.write_text("{truncated", encoding="utf-8")

        with self.assertRaisesRegex(ModelRegistryIntegrityError, "not valid JSON"):
            self.registry.register(self._artifact("corrupt-index-candidate-v2"))

        self.assertEqual(registered_bytes, registered_path.read_bytes())
        self.assertEqual([registered_path], self._managed_artifacts())

    def test_missing_index_with_existing_artifact_fails_without_deleting_or_publishing(self):
        first = self.registry.register(self._artifact("missing-index-authoritative-v1"))
        first_path = Path(first.artifact_path)
        first_bytes = first_path.read_bytes()
        self.registry.index_path.unlink()

        with self.assertRaisesRegex(ModelRegistryIntegrityError, "without an authoritative registry index"):
            self.registry.register(self._artifact("missing-index-candidate-v2"))

        self.assertEqual(first_bytes, first_path.read_bytes())
        self.assertFalse(self.registry.index_path.exists())
        self.assertEqual([first_path], self._managed_artifacts())

    def test_missing_index_with_empty_artifacts_directory_allows_initial_registration(self):
        self.registry.artifacts_root.mkdir(parents=True)

        registered = self.registry.register(self._artifact("initial-empty-registry-v1"))

        self.assertEqual([registered], self.registry.entries())

    def test_missing_index_with_unknown_registry_state_fails_without_publication(self):
        self.registry.root.mkdir(parents=True)
        unknown = self.registry.root / "restored-fragment"
        unknown.write_text("unknown", encoding="utf-8")

        with self.assertRaisesRegex(ModelRegistryIntegrityError, "unsupported state"):
            self.registry.register(self._artifact("unknown-state-v1"))

        self.assertEqual("unknown", unknown.read_text(encoding="utf-8"))
        self.assertFalse(self.registry.index_path.exists())

    def test_missing_index_with_artifacts_symlink_fails_without_publication(self):
        outside = self.root / "outside-artifacts"
        outside.mkdir()
        self.registry.root.mkdir(parents=True)
        try:
            self.registry.artifacts_root.symlink_to(outside, target_is_directory=True)
        except OSError:
            self.registry.artifacts_root.mkdir()
            with patch.object(
                    type(self.registry.artifacts_root),
                    "is_symlink",
                    autospec=True,
                    side_effect=lambda path: path == self.registry.artifacts_root,
            ):
                with self.assertRaisesRegex(ModelRegistryIntegrityError, "non-symlink directory"):
                    self.registry.register(self._artifact("symlinked-state-v1"))
            self.assertFalse(self.registry.index_path.exists())
            return

        with self.assertRaisesRegex(ModelRegistryIntegrityError, "non-symlink directory"):
            self.registry.register(self._artifact("symlinked-state-v1"))

        self.assertFalse(self.registry.index_path.exists())

    def test_registry_index_uses_explicit_schema_version_two(self):
        self.registry.register(self._artifact("schema-v2"))

        index = self._index()

        self.assertEqual(2, index["schemaVersion"])
        self.assertEqual({"schemaVersion", "models"}, set(index))

    def test_unsupported_old_and_future_schema_versions_are_rejected(self):
        registered = self.registry.register(self._artifact("schema-version-v1"))
        registered_path = Path(registered.artifact_path)
        registered_bytes = registered_path.read_bytes()
        for schema_version in (1, 3):
            with self.subTest(schema_version=schema_version):
                index = self._index()
                index["schemaVersion"] = schema_version
                self._write_index(index)

                with self.assertRaisesRegex(ModelRegistryIntegrityError, "schemaVersion .* unsupported"):
                    self.registry.register(self._artifact(f"schema-version-candidate-{schema_version}"))

                self.assertEqual(registered_bytes, registered_path.read_bytes())
                self.assertEqual([registered_path], self._managed_artifacts())

                index["schemaVersion"] = 2
                self._write_index(index)

    def test_unknown_index_and_obsolete_entry_fields_are_rejected(self):
        self.registry.register(self._artifact("strict-fields-v1"))
        index = self._index()
        index["unexpected"] = True
        self._write_index(index)
        with self.assertRaisesRegex(ModelRegistryIntegrityError, "index fields"):
            self.registry.entries()

        del index["unexpected"]
        index["models"][0]["role"] = "champion"
        self._write_index(index)
        with self.assertRaisesRegex(ModelRegistryIntegrityError, "entry fields"):
            self.registry.entries()

    def test_duplicate_contradictory_logical_identity_is_rejected(self):
        self.registry.register(self._artifact("duplicate-v1"))
        index = self._index()
        duplicate = dict(index["models"][0])
        duplicate["artifact_sha256"] = "0" * 64
        index["models"].append(duplicate)
        self._write_index(index)

        with self.assertRaisesRegex(ModelRegistryIntegrityError, "duplicate logical model identity"):
            self.registry.entries()

    def test_tampered_registered_artifact_is_rejected_before_model_construction(self):
        entry = self.registry.register(self._artifact("tamper-v1"))
        registered_path = Path(entry.artifact_path)
        registered_path.write_bytes(registered_path.read_bytes() + b"\n")

        with self.assertRaisesRegex(ModelRegistryIntegrityError, "digest mismatch"):
            self.registry.resolve(entry)

    def test_duplicate_registration_fails_when_authoritative_stored_artifact_is_corrupt(self):
        source = self._artifact("duplicate-corrupt-v1")
        entry = self.registry.register(source)
        Path(entry.artifact_path).write_text("{corrupt", encoding="utf-8")

        with self.assertRaisesRegex(ModelRegistryIntegrityError, "Registered artifact is invalid"):
            self.registry.register(source)

        self.assertEqual("{corrupt", Path(entry.artifact_path).read_text(encoding="utf-8"))

    def test_registry_metadata_artifact_metadata_mismatch_is_rejected(self):
        self.registry.register(self._artifact("family-drift-v1"))
        index = self._index()
        index["models"][0]["model_family"] = "LOGISTIC_REGRESSION_RELABELED"
        self._write_index(index)
        mismatched_entry = self.registry.entries()[0]

        with self.assertRaisesRegex(ModelRegistryIntegrityError, "metadata mismatch"):
            self.registry.resolve(mismatched_entry)

    def test_concurrent_registrations_do_not_lose_an_update(self):
        first_path = self._artifact("concurrent-v1")
        second_path = self._artifact("concurrent-v2")
        first_registry = ModelRegistry(self.registry.root)
        second_registry = ModelRegistry(self.registry.root)
        start = Barrier(2)

        def register(registry: ModelRegistry, artifact_path: Path):
            start.wait()
            return registry.register(artifact_path)

        with ThreadPoolExecutor(max_workers=2) as executor:
            first_future = executor.submit(register, first_registry, first_path)
            second_future = executor.submit(register, second_registry, second_path)
            identities = {first_future.result().logical_identity, second_future.result().logical_identity}

        self.assertEqual(identities, {entry.logical_identity for entry in self.registry.entries()})
        self.assertEqual(2, len(self.registry.entries()))

    def test_failed_index_replace_keeps_previous_complete_index(self):
        first = self.registry.register(self._artifact("atomic-v1"))
        second_path = self._artifact("atomic-v2")
        real_replace = os.replace

        def fail_index_replace(source: str | Path, target: str | Path) -> None:
            if Path(target) == self.registry.index_path:
                raise OSError("injected index replace failure")
            real_replace(source, target)

        with patch("app.registry.model_registry.os.replace", side_effect=fail_index_replace):
            with self.assertRaisesRegex(ModelRegistryMutationError, "publish registry index atomically"):
                self.registry.register(second_path)

        self.assertEqual([first], self.registry.entries())
        self.assertEqual([Path(first.artifact_path)], self._managed_artifacts())
        self.assertEqual([], list(self.registry.root.glob(".registry.json.*.tmp")))

    def test_index_serialization_failure_rolls_back_new_artifact(self):
        artifact = self._artifact("serialization-failure-v1")

        with patch("app.registry.model_registry.json.dumps", side_effect=TypeError("injected serialization failure")):
            with self.assertRaisesRegex(ModelRegistryIntegrityError, "not JSON serializable"):
                self.registry.register(artifact)

        self.assertEqual([], self.registry.entries())
        self.assertEqual([], self._managed_artifacts())

    def test_repeated_index_publication_failures_do_not_accumulate_orphans(self):
        first = self.registry.register(self._artifact("repeated-failure-authoritative-v1"))
        real_replace = os.replace

        def fail_index_replace(source: str | Path, target: str | Path) -> None:
            if Path(target) == self.registry.index_path:
                raise OSError("injected repeated index failure")
            real_replace(source, target)

        with patch("app.registry.model_registry.os.replace", side_effect=fail_index_replace):
            for version in ("repeated-failure-v2", "repeated-failure-v3"):
                with self.assertRaises(ModelRegistryMutationError):
                    self.registry.register(self._artifact(version))

        self.assertEqual([first], self.registry.entries())
        self.assertEqual([Path(first.artifact_path)], self._managed_artifacts())

    def test_index_replace_followed_by_fsync_failure_preserves_committed_artifact(self):
        first = self.registry.register(self._artifact("fsync-authoritative-v1"))
        second_path = self._artifact("fsync-ambiguous-v2")
        real_fsync_directory = self.registry._fsync_directory

        def fail_index_directory_fsync(directory: Path) -> None:
            if directory == self.registry.root:
                raise OSError("injected index directory fsync failure")
            real_fsync_directory(directory)

        with patch.object(self.registry, "_fsync_directory", side_effect=fail_index_directory_fsync):
            with self.assertRaisesRegex(ModelRegistryMutationError, "publish registry index atomically"):
                self.registry.register(second_path)

        entries = self.registry.entries()
        self.assertEqual(2, len(entries))
        self.assertIn(first, entries)
        self.assertEqual(2, len(self._managed_artifacts()))

    def test_stale_valid_index_cannot_delete_a_previously_committed_artifact(self):
        first = self.registry.register(self._artifact("stale-index-authoritative-v1"))
        stale_index_bytes = self.registry.index_path.read_bytes()
        second = self.registry.register(self._artifact("stale-index-committed-v2"))
        second_path = Path(second.artifact_path)
        second_bytes = second_path.read_bytes()
        second_sha_before = hashlib.sha256(second_bytes).hexdigest()

        self.assertCountEqual([first, second], self.registry.entries())
        self.assertEqual(2, len(self._managed_artifacts()))

        self.registry.index_path.write_bytes(stale_index_bytes)
        registration_error = None
        try:
            self.registry.register(self._artifact("stale-index-candidate-v3"))
        except ModelRegistryIntegrityError as exception:
            registration_error = exception

        self.assertTrue(
            second_path.exists(),
            f"committed artifact SHA-256 {second_sha_before} was deleted",
        )
        self.assertEqual(second_bytes, second_path.read_bytes())
        self.assertEqual(second_sha_before, hashlib.sha256(second_path.read_bytes()).hexdigest())
        self.assertIsInstance(registration_error, ModelRegistryIntegrityError)
        self.assertIn("controlled recovery is required", str(registration_error))

    def test_unindexed_artifact_requires_controlled_recovery_without_mutation(self):
        authoritative = self.registry.register(self._artifact("interruption-authoritative-v1"))
        orphan_source = self._artifact("interrupted-orphan-v1")
        orphan_target = self.registry._artifact_target(
            load_validated_model_artifact(orphan_source).logical_identity
        )
        orphan_target.parent.mkdir(parents=True, exist_ok=True)
        orphan_bytes = orphan_source.read_bytes()
        orphan_target.write_bytes(orphan_bytes)
        candidate_source = self._artifact("post-interruption-v1")
        candidate_target = self.registry._artifact_target(
            load_validated_model_artifact(candidate_source).logical_identity
        )

        with self.assertRaisesRegex(ModelRegistryIntegrityError, "controlled recovery is required"):
            self.registry.register(candidate_source)

        self.assertEqual(orphan_bytes, orphan_target.read_bytes())
        self.assertFalse(candidate_target.exists())
        self.assertEqual([authoritative], self.registry.entries())
        self.assertEqual(sorted([Path(authoritative.artifact_path), orphan_target]), self._managed_artifacts())

    def test_unlock_failure_does_not_leave_process_lock_held(self):
        with patch("app.registry.model_registry._unlock_file", side_effect=OSError("injected unlock failure")):
            with self.assertRaisesRegex(ModelRegistryMutationError, "lock cleanup failed"):
                self.registry.register(self._artifact("unlock-failure-v1"))

        second = self.registry.register(self._artifact("unlock-failure-v2"))

        self.assertEqual("unlock-failure-v2", second.model_version)
        self.assertEqual(2, len(self.registry.entries()))

    def _artifact(self, model_version: str, file_name: str | None = None) -> Path:
        payload = json.loads(self.CANONICAL_ARTIFACT.read_bytes())
        payload["modelVersion"] = model_version
        artifact_path = self.root / (file_name or f"{model_version}.json")
        artifact_path.write_text(json.dumps(payload, indent=2, sort_keys=True), encoding="utf-8")
        return artifact_path

    def _index(self) -> dict[str, object]:
        return json.loads(self.registry.index_path.read_bytes())

    def _write_index(self, payload: dict[str, object]) -> None:
        self.registry.index_path.write_text(json.dumps(payload, indent=2, sort_keys=True), encoding="utf-8")

    def _managed_artifacts(self) -> list[Path]:
        if not self.registry.artifacts_root.exists():
            return []
        return sorted(path.resolve() for path in self.registry.artifacts_root.iterdir())


if __name__ == "__main__":
    unittest.main()
