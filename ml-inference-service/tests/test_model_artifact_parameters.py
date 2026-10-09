from __future__ import annotations

import base64
import copy
import json
import math
import tempfile
import unittest
from pathlib import Path

from app.features.feature_pipeline import FeaturePipeline
from app.models.logistic_model import LogisticFraudModel
from app.models.model_loader import ModelConfigurationError, load_model_from_artifact


class ModelArtifactParameterValidationTest(unittest.TestCase):

    CANONICAL_ARTIFACT = Path(__file__).resolve().parents[1] / "app" / "model_artifact.json"

    def setUp(self) -> None:
        self._temporary_directory = tempfile.TemporaryDirectory()
        self.root = Path(self._temporary_directory.name)
        self.canonical = json.loads(self.CANONICAL_ARTIFACT.read_bytes())

    def tearDown(self) -> None:
        self._temporary_directory.cleanup()

    def test_logistic_weights_are_required_exact_and_finite(self):
        feature_name = self.canonical["featureSchema"][0]
        cases: dict[str, object] = {
            "missing_weights": None,
            "missing_feature": {k: v for k, v in self.canonical["weights"].items() if k != feature_name},
            "unknown_feature": {**self.canonical["weights"], "unknownFeature": 0.1},
            "boolean_weight": {**self.canonical["weights"], feature_name: True},
            "string_weight": {**self.canonical["weights"], feature_name: "0.25"},
            "nan_weight": {**self.canonical["weights"], feature_name: float("nan")},
            "positive_infinity_weight": {**self.canonical["weights"], feature_name: float("inf")},
            "negative_infinity_weight": {**self.canonical["weights"], feature_name: float("-inf")},
            "oversized_integer_weight": {**self.canonical["weights"], feature_name: 10 ** 400},
        }
        for case_name, weights in cases.items():
            with self.subTest(case_name=case_name):
                artifact = copy.deepcopy(self.canonical)
                if case_name == "missing_weights":
                    artifact.pop("weights")
                else:
                    artifact["weights"] = weights
                self._assert_rejected(artifact, "weights|weight")

    def test_logistic_bias_is_required_and_finite(self):
        cases = {
            "missing": None,
            "boolean": False,
            "string": "-4.5",
            "nan": float("nan"),
            "positive_infinity": float("inf"),
            "negative_infinity": float("-inf"),
        }
        for case_name, bias in cases.items():
            with self.subTest(case_name=case_name):
                artifact = copy.deepcopy(self.canonical)
                if case_name == "missing":
                    artifact.pop("bias")
                else:
                    artifact["bias"] = bias
                self._assert_rejected(artifact, "bias")

    def test_thresholds_are_required_exact_finite_bounded_and_ordered(self):
        cases: dict[str, object] = {
            "missing_medium": {"high": 0.75, "critical": 0.9},
            "unknown_threshold": {**self.canonical["thresholds"], "emergency": 0.99},
            "boolean": {**self.canonical["thresholds"], "medium": True},
            "string": {**self.canonical["thresholds"], "medium": "0.45"},
            "nan": {**self.canonical["thresholds"], "medium": float("nan")},
            "infinity": {**self.canonical["thresholds"], "critical": float("inf")},
            "below_zero": {**self.canonical["thresholds"], "medium": -0.01},
            "above_one": {**self.canonical["thresholds"], "critical": 1.01},
            "medium_above_high": {"medium": 0.8, "high": 0.7, "critical": 0.9},
            "high_above_critical": {"medium": 0.4, "high": 0.95, "critical": 0.9},
        }
        for case_name, thresholds in cases.items():
            with self.subTest(case_name=case_name):
                artifact = copy.deepcopy(self.canonical)
                artifact["thresholds"] = thresholds
                self._assert_rejected(artifact, "threshold")

    def test_model_type_and_family_must_be_compatible(self):
        artifact = copy.deepcopy(self.canonical)
        artifact["modelFamily"] = "XGBOOST"

        self._assert_rejected(artifact, "modelFamily is incompatible")

    def test_training_mode_cannot_fall_back_or_disagree_with_training_metadata(self):
        cases = {
            "missing": None,
            "null": None,
            "boolean": True,
            "nested_missing": "production",
            "nested_mismatch": "production",
        }
        for case_name, training_mode in cases.items():
            with self.subTest(case_name=case_name):
                artifact = copy.deepcopy(self.canonical)
                if case_name == "missing":
                    artifact.pop("trainingMode")
                else:
                    artifact["trainingMode"] = training_mode
                if case_name == "nested_missing":
                    artifact["training"].pop("trainingMode")
                elif case_name == "nested_mismatch":
                    artifact["training"]["trainingMode"] = "full"
                self._assert_rejected(artifact, "trainingMode")

    def test_xgboost_requires_a_nonempty_valid_runtime_payload(self):
        cases = {
            "missing": None,
            "empty": "",
            "invalid_base64": "not-base64!",
            "empty_payload": base64.b64encode(b"").decode("ascii"),
        }
        for case_name, payload in cases.items():
            with self.subTest(case_name=case_name):
                artifact = self._xgboost_artifact()
                if case_name == "missing":
                    artifact.pop("modelDataBase64", None)
                else:
                    artifact["modelDataBase64"] = payload
                self._assert_rejected(artifact, "modelDataBase64")

    def test_persisted_load_never_uses_training_defaults(self):
        with self.assertRaises(ModelConfigurationError):
            LogisticFraudModel.load(self.root / "missing.json")
        with self.assertRaises((KeyError, TypeError, ValueError)):
            LogisticFraudModel({})

        training_model = LogisticFraudModel(None)

        self.assertEqual(LogisticFraudModel.DEFAULT_WEIGHTS, training_model.weights)
        self.assertEqual(LogisticFraudModel.DEFAULT_BIAS, training_model.bias)
        self.assertEqual(LogisticFraudModel.DEFAULT_THRESHOLDS, training_model.thresholds)

    def test_canonical_prediction_mathematics_are_unchanged(self):
        model = load_model_from_artifact(self.CANONICAL_ARTIFACT)
        pipeline = FeaturePipeline()
        baseline = model.predict_proba(pipeline.transform_single(self._baseline_features(), mode=model.training_mode))
        high_risk = model.predict_proba(pipeline.transform_single(self._high_risk_features(), mode=model.training_mode))

        self.assertTrue(math.isclose(0.009737254902, baseline, rel_tol=0.0, abs_tol=5e-13))
        self.assertTrue(math.isclose(0.997997898017, high_risk, rel_tol=0.0, abs_tol=5e-13))

    def _assert_rejected(self, artifact: dict[str, object], message: str) -> None:
        artifact_path = self.root / "invalid-artifact.json"
        artifact_path.write_text(json.dumps(artifact), encoding="utf-8")
        with self.assertRaisesRegex(ModelConfigurationError, message):
            load_model_from_artifact(artifact_path)

    def _xgboost_artifact(self) -> dict[str, object]:
        artifact = copy.deepcopy(self.canonical)
        artifact["modelName"] = "python-xgboost-fraud-model"
        artifact["modelType"] = "xgboost"
        artifact["modelFamily"] = "XGBOOST"
        artifact.pop("bias", None)
        artifact.pop("weights", None)
        return artifact

    def _baseline_features(self) -> dict[str, object]:
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

    def _high_risk_features(self) -> dict[str, object]:
        return {
            "recentTransactionCount": 8,
            "recentAmountSumPln": 28_800.0,
            "currentTransactionAmountPln": 28_800.0,
            "currency": "USD",
            "recentTransactionCountWindow": "PT1M",
            "recentAmountSumWindow": "PT1M",
            "transactionVelocityPerMinute": 8.0,
            "merchantFrequency7d": 9,
            "deviceNovelty": True,
            "countryMismatch": True,
            "proxyOrVpnDetected": True,
        }


if __name__ == "__main__":
    unittest.main()
