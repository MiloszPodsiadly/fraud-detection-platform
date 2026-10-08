from __future__ import annotations

import hashlib
import json
import tempfile
import unittest
from dataclasses import FrozenInstanceError
from pathlib import Path
from unittest.mock import patch

from app.models.model_loader import (
    ModelConfigurationError,
    load_model_from_artifact,
    load_validated_model_artifact,
)


class ModelArtifactIdentityTest(unittest.TestCase):

    CANONICAL_ARTIFACT = Path(__file__).resolve().parents[1] / "app" / "model_artifact.json"

    def test_identity_and_sha_are_derived_from_the_exact_bytes_parsed(self):
        payload = json.loads(self.CANONICAL_ARTIFACT.read_bytes())
        payload["modelName"] = "identity-proof-model"
        payload["modelVersion"] = "identity-proof-v1"
        exact_bytes = json.dumps(payload, indent=2, sort_keys=True).encode("utf-8")

        with tempfile.TemporaryDirectory() as directory:
            artifact_path = Path(directory) / "artifact.json"
            artifact_path.write_bytes(exact_bytes)

            validated = load_validated_model_artifact(artifact_path)

        self.assertEqual("identity-proof-model", validated.logical_identity.model_name)
        self.assertEqual("identity-proof-v1", validated.logical_identity.model_version)
        self.assertEqual(validated.logical_identity, validated.artifact_identity.logical_identity)
        self.assertEqual(hashlib.sha256(exact_bytes).hexdigest(), validated.artifact_sha256)
        self.assertEqual(exact_bytes, validated.exact_bytes)
        self.assertEqual(json.loads(exact_bytes), validated.artifact)

    def test_identical_bytes_produce_identical_artifact_identity(self):
        exact_bytes = self.CANONICAL_ARTIFACT.read_bytes()

        with tempfile.TemporaryDirectory() as directory:
            first_path = Path(directory) / "first.json"
            second_path = Path(directory) / "second.json"
            first_path.write_bytes(exact_bytes)
            second_path.write_bytes(exact_bytes)

            first = load_validated_model_artifact(first_path)
            second = load_validated_model_artifact(second_path)

        self.assertEqual(first.artifact_identity, second.artifact_identity)

    def test_changed_bytes_change_sha_and_conflict_under_same_logical_identity(self):
        exact_bytes = self.CANONICAL_ARTIFACT.read_bytes()

        with tempfile.TemporaryDirectory() as directory:
            first_path = Path(directory) / "first.json"
            second_path = Path(directory) / "second.json"
            first_path.write_bytes(exact_bytes)
            second_path.write_bytes(exact_bytes + b"\n")

            first = load_validated_model_artifact(first_path)
            second = load_validated_model_artifact(second_path)

        self.assertEqual(first.logical_identity, second.logical_identity)
        self.assertNotEqual(first.artifact_sha256, second.artifact_sha256)
        self.assertTrue(first.artifact_identity.conflicts_with(second.artifact_identity))

    def test_changed_valid_parameters_conflict_under_same_logical_identity(self):
        original_payload = json.loads(self.CANONICAL_ARTIFACT.read_bytes())
        changed_payload = dict(original_payload)
        changed_payload["bias"] = float(original_payload["bias"]) + 0.001

        with tempfile.TemporaryDirectory() as directory:
            original_path = Path(directory) / "original.json"
            changed_path = Path(directory) / "changed.json"
            original_path.write_text(json.dumps(original_payload), encoding="utf-8")
            changed_path.write_text(json.dumps(changed_payload), encoding="utf-8")

            original = load_validated_model_artifact(original_path)
            changed = load_validated_model_artifact(changed_path)

        self.assertEqual(original.logical_identity, changed.logical_identity)
        self.assertTrue(original.artifact_identity.conflicts_with(changed.artifact_identity))

    def test_artifact_identity_is_immutable(self):
        validated = load_validated_model_artifact(self.CANONICAL_ARTIFACT)

        with self.assertRaises(FrozenInstanceError):
            validated.artifact_identity.model_version = "mutated"

    def test_oversized_artifact_is_rejected_before_parsing(self):
        with tempfile.TemporaryDirectory() as directory:
            artifact_path = Path(directory) / "oversized.json"
            artifact_path.write_bytes(b"{" + b" " * 32 + b"}")

            with patch("app.models.model_loader.MAX_MODEL_ARTIFACT_BYTES", 32):
                with self.assertRaisesRegex(ModelConfigurationError, "exceeds maximum size"):
                    load_validated_model_artifact(artifact_path)

    def test_committed_artifact_identity_matches_constructed_model(self):
        validated = load_validated_model_artifact(self.CANONICAL_ARTIFACT)
        model = load_model_from_artifact(self.CANONICAL_ARTIFACT)

        self.assertEqual(validated.logical_identity.model_name, model.model_name)
        self.assertEqual(validated.logical_identity.model_version, model.model_version)
        self.assertEqual(validated.artifact_identity.model_family, model.model_family)
        self.assertEqual(validated.artifact_identity.feature_contract_version, model.feature_contract_version)


if __name__ == "__main__":
    unittest.main()
