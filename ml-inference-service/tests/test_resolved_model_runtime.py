from __future__ import annotations

import hashlib
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import app.model as model_module
from app.data.generator import generate_examples
from app.features.feature_pipeline import FeaturePipeline
from app.governance.lifecycle import current_model_lifecycle_metadata
from app.governance.profile import (
    InferenceProfile,
    governance_model_metadata,
    load_reference_profile,
)
from app.inference.model_runtime import resolve_model_runtime
from app.inference.model_selection import ModelSelectionMode, ModelSelectionPolicy
from app.model import FraudModel, resolve_configured_model_runtime
from app.models.model_loader import load_model_from_artifact
from app.registry.model_registry import ModelRegistry
from app.train_model import write_reference_profile


class ResolvedModelRuntimeTest(unittest.TestCase):

    CANONICAL_ARTIFACT = Path(__file__).resolve().parents[1] / "app" / "model_artifact.json"

    def test_resolved_runtime_owns_exact_artifact_identity_and_sha(self):
        resolved = resolve_model_runtime(
            self.CANONICAL_ARTIFACT,
            ModelSelectionPolicy.packaged_explicit(),
        )

        self.assertEqual(hashlib.sha256(self.CANONICAL_ARTIFACT.read_bytes()).hexdigest(), resolved.artifact_sha256)
        self.assertEqual(resolved.artifact_sha256, resolved.artifact_identity.artifact_sha256)
        self.assertEqual(resolved.logical_identity, resolved.artifact_identity.logical_identity)
        self.assertEqual(
            f"{resolved.logical_identity.model_name}/{resolved.logical_identity.model_version}"
            f"@sha256:{resolved.artifact_sha256}",
            resolved.canonical_artifact_path_or_id,
        )
        self.assertNotIn(str(self.CANONICAL_ARTIFACT.parent), resolved.canonical_artifact_path_or_id)
        self.assertIs(ModelSelectionMode.PACKAGED_EXPLICIT, resolved.selection_source)
        self.assertEqual(resolved.logical_identity.model_name, resolved.model.model_name)
        self.assertEqual(resolved.logical_identity.model_version, resolved.model.model_version)

    def test_scoring_facade_uses_the_same_resolved_object(self):
        resolved = resolve_model_runtime(
            self.CANONICAL_ARTIFACT,
            ModelSelectionPolicy.packaged_explicit(),
        )

        facade = FraudModel(resolved)

        self.assertIs(resolved, facade.resolved_runtime)
        self.assertIs(resolved, facade._runtime.resolved_model)
        self.assertIs(resolved.model, facade._runtime.model)
        self.assertEqual(resolved.artifact_sha256, facade.model_artifact_sha256)

    def test_configured_bootstrap_resolves_once_and_scoring_does_not_resolve_again(self):
        with patch("app.model.resolve_model_runtime", wraps=resolve_model_runtime) as resolver:
            resolved = resolve_configured_model_runtime({})
            facade = FraudModel(resolved)
            facade.score(self._production_features())
            facade.score(self._production_features())

        resolver.assert_called_once()

    def test_facade_has_no_hidden_default_model_resolution(self):
        with self.assertRaises(TypeError):
            FraudModel()  # type: ignore[call-arg]

        self.assertFalse(hasattr(model_module, "DEFAULT_MODEL"))
        self.assertFalse(hasattr(model_module, "MODEL_NAME"))
        self.assertFalse(hasattr(model_module, "MODEL_VERSION"))

    def test_valid_prediction_matches_directly_loaded_artifact(self):
        features = self._production_features()
        resolved = resolve_model_runtime(
            self.CANONICAL_ARTIFACT,
            ModelSelectionPolicy.packaged_explicit(),
        )
        result = FraudModel(resolved).score(features)
        direct_model = load_model_from_artifact(self.CANONICAL_ARTIFACT)
        normalized = FeaturePipeline().transform_single(features, mode=direct_model.training_mode)

        self.assertEqual(round(direct_model.predict_proba(normalized), 4), result["fraudScore"])
        self.assertEqual(direct_model.model_name, result["modelName"])
        self.assertEqual(direct_model.model_version, result["modelVersion"])

    def test_governance_and_lifecycle_use_the_resolved_identity(self):
        resolved = resolve_model_runtime(
            self.CANONICAL_ARTIFACT,
            ModelSelectionPolicy.packaged_explicit(),
        )

        governance = governance_model_metadata(resolved)
        lifecycle = current_model_lifecycle_metadata(resolved, {})

        self.assertEqual(resolved.logical_identity.model_name, governance["model_name"])
        self.assertEqual(resolved.logical_identity.model_version, governance["model_version"])
        self.assertEqual(resolved.artifact_sha256, governance["artifact"]["sha256"])
        self.assertEqual(resolved.logical_identity.model_name, lifecycle["model_name"])
        self.assertEqual(resolved.logical_identity.model_version, lifecycle["model_version"])
        self.assertEqual(f"sha256:{resolved.artifact_sha256}", lifecycle["artifact_checksum"])
        self.assertEqual(resolved.canonical_artifact_path_or_id, lifecycle["artifact_path_or_id"])

    def test_registry_selected_runtime_binds_all_diagnostics_to_exact_artifact(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            artifact_path = self._artifact_with_version(root, "registry-v17")
            registry = ModelRegistry(root / "registry")
            registry.register(artifact_path, role="archived")
            resolved = resolve_model_runtime(
                None,
                ModelSelectionPolicy.registry_exact("python-logistic-fraud-model", "registry-v17"),
                registry,
            )
            profile_path = self._reference_profile(root, resolved)

            reference = load_reference_profile(resolved, profile_path)
            inference = InferenceProfile(resolved, ["countryMismatch"]).snapshot()
            governance = governance_model_metadata(resolved)
            lifecycle = current_model_lifecycle_metadata(resolved, reference)

        self.assertEqual("MATCH", reference["identity_status"])
        self.assertEqual(resolved.logical_identity.model_name, inference["model_name"])
        self.assertEqual(resolved.logical_identity.model_version, inference["model_version"])
        self.assertEqual(resolved.artifact_sha256, inference["artifact_sha256"])
        self.assertEqual(resolved.artifact_sha256, governance["artifact"]["sha256"])
        self.assertEqual(f"sha256:{resolved.artifact_sha256}", lifecycle["artifact_checksum"])
        self.assertEqual(resolved.selection_source.value, inference["selection_source"])

    def test_reference_profile_for_another_artifact_is_explicitly_unavailable(self):
        resolved = resolve_model_runtime(
            self.CANONICAL_ARTIFACT,
            ModelSelectionPolicy.packaged_explicit(),
        )
        with tempfile.TemporaryDirectory() as directory:
            path = self._reference_profile(Path(directory), resolved, artifact_sha256="0" * 64)
            profile = load_reference_profile(resolved, path)

        self.assertFalse(profile["available"])
        self.assertEqual("IDENTITY_MISMATCH", profile["status"])
        self.assertEqual("MISMATCH", profile["identity_status"])
        self.assertEqual("REFERENCE_ARTIFACT_SHA256_MISMATCH", profile["unavailable_reason"])

    def test_reference_profile_without_artifact_digest_is_not_upgraded(self):
        resolved = resolve_model_runtime(
            self.CANONICAL_ARTIFACT,
            ModelSelectionPolicy.packaged_explicit(),
        )
        with tempfile.TemporaryDirectory() as directory:
            path = self._reference_profile(Path(directory), resolved, artifact_sha256=None)
            profile = load_reference_profile(resolved, path)

        self.assertFalse(profile["available"])
        self.assertEqual("UNKNOWN", profile["identity_status"])
        self.assertNotIn("artifact_sha256", profile)

    def test_reference_profile_generation_persists_exact_artifact_digest(self):
        resolved = resolve_model_runtime(
            self.CANONICAL_ARTIFACT,
            ModelSelectionPolicy.packaged_explicit(),
        )
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "reference.json"
            write_reference_profile(
                path,
                resolved.model,
                generate_examples(32, 7307),
                seed=7307,
                examples=32,
                artifact_sha256=resolved.artifact_sha256,
            )
            payload = json.loads(path.read_text(encoding="utf-8"))

        self.assertEqual(resolved.artifact_sha256, payload["artifact_sha256"])

    def _production_features(self) -> dict[str, object]:
        return {
            "recentTransactionCount": 1,
            "recentAmountSumPln": 100.0,
            "currentTransactionAmountPln": 100.0,
            "currency": "PLN",
            "recentTransactionCountWindow": "PT1M",
            "recentAmountSumWindow": "PT1M",
            "transactionVelocityPerMinute": 1.0,
            "merchantFrequency7d": 1,
            "deviceNovelty": False,
            "countryMismatch": False,
            "proxyOrVpnDetected": False,
        }

    def _artifact_with_version(self, root: Path, model_version: str) -> Path:
        payload = json.loads(self.CANONICAL_ARTIFACT.read_text(encoding="utf-8"))
        payload["modelVersion"] = model_version
        path = root / "model.json"
        path.write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8")
        return path

    def _reference_profile(
            self,
            root: Path,
            resolved,
            artifact_sha256: str | None = "resolved",
    ) -> Path:
        payload = {
            "profileType": "test_reference",
            "profileVersion": "test-v1",
            "model_name": resolved.logical_identity.model_name,
            "model_version": resolved.logical_identity.model_version,
            "numeric_feature_stats": {"countryMismatch": {"count": 1}},
            "score_distribution": {"count": 1},
            "source": "evaluation",
        }
        if artifact_sha256 is not None:
            payload["artifact_sha256"] = (
                resolved.artifact_sha256 if artifact_sha256 == "resolved" else artifact_sha256
            )
        path = root / "reference.json"
        path.write_text(json.dumps(payload), encoding="utf-8")
        return path


if __name__ == "__main__":
    unittest.main()
