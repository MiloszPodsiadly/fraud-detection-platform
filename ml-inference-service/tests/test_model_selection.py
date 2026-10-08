from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from app.inference.model_selection import (
    ModelSelectionError,
    ModelSelectionPolicy,
)
from app.model import (
    MODEL_NAME_ENV,
    MODEL_REGISTRY_PATH_ENV,
    MODEL_SELECTION_MODE_ENV,
    MODEL_VERSION_ENV,
    FraudModel,
    model_selection_policy_from_environment,
    resolve_configured_model_runtime,
)
from app.models.model_loader import ModelConfigurationError
from app.registry.model_registry import ModelRegistry


class ModelSelectionTest(unittest.TestCase):

    CANONICAL_ARTIFACT = Path(__file__).resolve().parents[1] / "app" / "model_artifact.json"
    MODEL_NAME = "python-logistic-fraud-model"

    def setUp(self) -> None:
        self._temporary_directory = tempfile.TemporaryDirectory()
        self.root = Path(self._temporary_directory.name)
        self.registry = ModelRegistry(self.root / "registry")

    def tearDown(self) -> None:
        self._temporary_directory.cleanup()

    def test_exact_requested_model_loads_and_scores(self):
        self.registry.register(self._artifact("requested-v17"), role="archived")

        model = self._registry_model("requested-v17")
        result = model.score(self._production_features())

        self.assertEqual("requested-v17", model.model_version)
        self.assertTrue(result["available"])

    def test_absent_exact_model_fails(self):
        with self.assertRaisesRegex(ModelSelectionError, "requested-v17"):
            self._registry_model("requested-v17")

    def test_absent_exact_model_does_not_select_champion(self):
        self.registry.register(self._artifact("champion-v12"), role="champion")

        with self.assertRaisesRegex(ModelSelectionError, "requested-v17"):
            self._registry_model("requested-v17")

    def test_absent_exact_model_does_not_select_challenger(self):
        self.registry.register(self._artifact("challenger-v16"), role="challenger")

        with self.assertRaisesRegex(ModelSelectionError, "requested-v17"):
            self._registry_model("requested-v17")

    def test_absent_exact_model_does_not_select_latest(self):
        self.registry.register(self._artifact("latest-v18"), role="archived")

        with self.assertRaisesRegex(ModelSelectionError, "requested-v17"):
            self._registry_model("requested-v17")

    def test_invalid_selection_policy_fails(self):
        with self.assertRaisesRegex(ModelSelectionError, "Unsupported model selection mode"):
            ModelSelectionPolicy("AUTOMATIC")

    def test_registry_exact_requires_complete_identity(self):
        for model_name, model_version in ((None, "v1"), (self.MODEL_NAME, None)):
            with self.subTest(model_name=model_name, model_version=model_version):
                with self.assertRaisesRegex(ModelSelectionError, "requires a valid modelName and modelVersion"):
                    ModelSelectionPolicy("REGISTRY_EXACT", model_name, model_version)

    def test_packaged_artifact_loads_only_under_explicit_packaged_policy(self):
        with patch.object(self.registry, "by_identity", side_effect=AssertionError("registry lookup forbidden")):
            model = FraudModel.from_selection(
                self.CANONICAL_ARTIFACT,
                ModelSelectionPolicy.packaged_explicit(),
                self.registry,
            )

        self.assertEqual(self.MODEL_NAME, model.model_name)

    def test_missing_or_corrupt_packaged_artifact_fails(self):
        corrupt = self.root / "corrupt.json"
        corrupt.write_text("{invalid", encoding="utf-8")
        for artifact_path in (self.root / "missing.json", corrupt):
            with self.subTest(artifact_path=artifact_path.name):
                with self.assertRaises(ModelConfigurationError):
                    FraudModel.from_selection(
                        artifact_path,
                        ModelSelectionPolicy.packaged_explicit(),
                    )

        with self.assertRaisesRegex(ModelSelectionError, "requires an artifact path"):
            FraudModel.from_selection(None, ModelSelectionPolicy.packaged_explicit())

    def test_exact_lookup_never_calls_role_or_recency_selectors(self):
        with (
            patch.object(self.registry, "latest", side_effect=AssertionError("latest fallback forbidden")) as latest,
            patch.object(self.registry, "champion", side_effect=AssertionError("role fallback forbidden")) as champion,
            patch.object(self.registry, "challenger", side_effect=AssertionError("role fallback forbidden")) as challenger,
        ):
            with self.assertRaises(ModelSelectionError):
                self._registry_model("requested-v17")

        latest.assert_not_called()
        champion.assert_not_called()
        challenger.assert_not_called()

    def test_environment_configuration_is_explicit_and_fail_closed(self):
        packaged = model_selection_policy_from_environment({})
        exact = model_selection_policy_from_environment({
            MODEL_SELECTION_MODE_ENV: "REGISTRY_EXACT",
            MODEL_NAME_ENV: self.MODEL_NAME,
            MODEL_VERSION_ENV: "configured-v1",
        })

        self.assertEqual("PACKAGED_EXPLICIT", packaged.mode.value)
        self.assertEqual("REGISTRY_EXACT", exact.mode.value)
        self.assertEqual("configured-v1", exact.model_version)
        with self.assertRaises(ModelSelectionError):
            model_selection_policy_from_environment({MODEL_SELECTION_MODE_ENV: "REGISTRY_EXACT"})

    def test_registry_path_configuration_cannot_create_an_implicit_selection(self):
        with self.assertRaisesRegex(ModelSelectionError, "only valid with REGISTRY_EXACT"):
            resolve_configured_model_runtime({MODEL_REGISTRY_PATH_ENV: str(self.root / "registry")})

        with self.assertRaisesRegex(ModelSelectionError, "must not be blank"):
            resolve_configured_model_runtime({
                MODEL_SELECTION_MODE_ENV: "REGISTRY_EXACT",
                MODEL_NAME_ENV: self.MODEL_NAME,
                MODEL_VERSION_ENV: "configured-v1",
                MODEL_REGISTRY_PATH_ENV: " ",
            })

    def test_configured_exact_model_fails_startup_when_identity_is_absent(self):
        with self.assertRaisesRegex(ModelSelectionError, "configured-v17"):
            resolve_configured_model_runtime({
                MODEL_SELECTION_MODE_ENV: "REGISTRY_EXACT",
                MODEL_NAME_ENV: self.MODEL_NAME,
                MODEL_VERSION_ENV: "configured-v17",
                MODEL_REGISTRY_PATH_ENV: str(self.registry.root),
            })

    def _registry_model(self, model_version: str) -> FraudModel:
        return FraudModel.from_registry_exact(self.MODEL_NAME, model_version, self.registry)

    def _artifact(self, model_version: str) -> Path:
        payload = json.loads(self.CANONICAL_ARTIFACT.read_bytes())
        payload["modelVersion"] = model_version
        artifact_path = self.root / f"{model_version}.json"
        artifact_path.write_text(json.dumps(payload, indent=2, sort_keys=True), encoding="utf-8")
        return artifact_path

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


if __name__ == "__main__":
    unittest.main()
