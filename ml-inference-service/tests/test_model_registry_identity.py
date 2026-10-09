from __future__ import annotations

import inspect
import json
import tempfile
import unittest
from pathlib import Path

from app.models.model_loader import ModelConfigurationError, load_validated_model_artifact
from app.registry.model_registry import (
    ModelRegistry,
    ModelRegistryConflictError,
    ModelRegistryIntegrityError,
)


class ModelRegistryIdentityTest(unittest.TestCase):

    CANONICAL_ARTIFACT = Path(__file__).resolve().parents[1] / "app" / "model_artifact.json"

    def setUp(self) -> None:
        self._temporary_directory = tempfile.TemporaryDirectory()
        self.root = Path(self._temporary_directory.name)
        self.registry = ModelRegistry(self.root / "registry")

    def tearDown(self) -> None:
        self._temporary_directory.cleanup()

    def test_first_registration_uses_artifact_derived_identity_and_exact_sha(self):
        artifact_path = self._artifact("registry-identity-v1")

        entry = self.registry.register(artifact_path)
        validated = load_validated_model_artifact(artifact_path)

        self.assertEqual(validated.logical_identity, entry.logical_identity)
        self.assertEqual(validated.artifact_identity, entry.artifact_identity)
        self.assertEqual(validated.artifact_sha256, entry.artifact_sha256)
        self.assertEqual(validated.exact_bytes, Path(entry.artifact_path).read_bytes())

    def test_same_exact_artifact_registration_is_idempotent(self):
        artifact_path = self._artifact("registry-idempotent-v1")

        first = self.registry.register(artifact_path)
        second = self.registry.register(artifact_path)

        self.assertEqual(first, second)
        self.assertEqual(1, len(self.registry.entries()))

    def test_logical_identity_uses_model_name_and_version_together(self):
        first_path = self._artifact("shared-v1", file_name="first-model.json", modelName="first-model")
        second_path = self._artifact("shared-v1", file_name="second-model.json", modelName="second-model")

        first = self.registry.register(first_path)
        second = self.registry.register(second_path)

        self.assertEqual(first, self.registry.by_identity("first-model", "shared-v1"))
        self.assertEqual(second, self.registry.by_identity("second-model", "shared-v1"))
        self.assertCountEqual([first, second], self.registry.entries())

    def test_same_logical_identity_with_different_bytes_is_rejected(self):
        first_path = self._artifact("registry-conflict-v1")
        second_path = self.root / "registry-conflict-copy.json"
        second_path.write_bytes(first_path.read_bytes() + b"\n")
        self.registry.register(first_path)

        with self.assertRaises(ModelRegistryConflictError):
            self.registry.register(second_path)

    def test_same_logical_identity_with_changed_family_is_rejected(self):
        first_path = self._artifact("registry-family-v1")
        changed_path = self._artifact(
            "registry-family-v1",
            file_name="changed-family.json",
            modelFamily="LOGISTIC_REGRESSION_REBUILT",
        )
        self.registry.register(first_path)

        with self.assertRaises(ModelRegistryConflictError):
            self.registry.register(changed_path)

    def test_same_logical_identity_with_changed_type_is_rejected(self):
        first_path = self._artifact("registry-type-v1")
        changed_path = self._artifact(
            "registry-type-v1",
            file_name="changed-type.json",
            modelType="xgboost",
            modelFamily="XGBOOST",
        )
        self.registry.register(first_path)

        with self.assertRaises(ModelRegistryConflictError):
            self.registry.register(changed_path)

    def test_changed_feature_contract_is_rejected(self):
        first_path = self._artifact("registry-contract-v1")
        changed_path = self._artifact(
            "registry-contract-v1",
            file_name="changed-contract.json",
            featureContractVersion="unsupported-contract-v1",
        )
        self.registry.register(first_path)

        with self.assertRaises(ModelRegistryConflictError):
            self.registry.register(changed_path)

    def test_malformed_artifact_cannot_be_registered(self):
        malformed = self.root / "malformed.json"
        malformed.write_text("{not-json", encoding="utf-8")

        with self.assertRaisesRegex(ModelConfigurationError, "Invalid model artifact JSON"):
            self.registry.register(malformed)

        self.assertFalse(self.registry.index_path.exists())

    def test_registration_api_has_no_caller_supplied_identity_parameters(self):
        parameters = inspect.signature(ModelRegistry.register).parameters

        self.assertEqual(["self", "artifact_path"], list(parameters))

    def test_registry_api_has_no_lifecycle_or_ambiguous_selection_methods(self):
        for method_name in ("latest", "by_version", "champion", "challenger", "promote"):
            with self.subTest(method_name=method_name):
                self.assertFalse(hasattr(ModelRegistry, method_name))

    def test_old_registry_without_schema_version_is_rejected_explicitly(self):
        self.registry.root.mkdir(parents=True)
        self.registry.index_path.write_text(
            json.dumps({
                "models": [{
                    "model_version": "old-v1",
                    "model_type": "logistic",
                    "artifact_path": "old.json",
                    "metrics": {},
                    "training_metadata": {},
                    "created_at": "2026-10-08T00:00:00+00:00",
                    "role": "champion",
                }]
            }),
            encoding="utf-8",
        )

        with self.assertRaisesRegex(ModelRegistryIntegrityError, "schemaVersion is required"):
            self.registry.entries()

    def _artifact(self, model_version: str, file_name: str | None = None, **overrides: object) -> Path:
        payload = json.loads(self.CANONICAL_ARTIFACT.read_bytes())
        payload["modelVersion"] = model_version
        payload.update(overrides)
        artifact_path = self.root / (file_name or f"{model_version}.json")
        artifact_path.write_text(json.dumps(payload, indent=2, sort_keys=True), encoding="utf-8")
        return artifact_path


if __name__ == "__main__":
    unittest.main()
