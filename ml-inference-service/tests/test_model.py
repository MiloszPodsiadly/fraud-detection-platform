import unittest
import json
import importlib.util
import os
from pathlib import Path
from unittest.mock import patch

from app.data.dataset import Dataset
from app.data.generator import generate_fraud_behavior, generate_normal_behavior
from app.data.splitting import split_dataset
from app.evaluation.evaluate import cli_summary, evaluate_scores
from app.feedback.feedback_dataset import FeedbackDatasetStore, dataset_from_feedback, feedback_from_decision_event
from app.features import feature_contract as feature_contract_module
from app.features.feature_contract import ALLOW_FALLBACK_ENV, FEATURE_CONTRACT, PRODUCTION_CONTRACT_ENV, FeatureContract
from app.features.feature_pipeline import (
    FeaturePipeline,
    MAX_RECENT_AMOUNT_SUM_PLN,
    MAX_RECENT_TRANSACTION_COUNT,
    MAX_TRANSACTION_VELOCITY_PER_MINUTE,
    RATE_CONSISTENCY_TOLERANCE,
    SUPPORTED_CURRENCIES,
)
from app.inference.model_runtime import FraudModelRuntime
from app.model import FraudModel
from app.models.model_loader import ModelConfigurationError, load_model_from_artifact
from app.models.logistic_model import LogisticFraudModel
from app.registry.model_registry import ModelRegistry
from app.models.xgboost_model import XGBoostFraudModel
from app.training.retraining import PromotionThresholds, _promotion_decision, compare_retrained_model
from app.training.train import (
    CANONICAL_MODEL_VERSION,
    _require_binary_evaluation_splits,
    train,
    train_model,
    train_model_with_evaluation,
    train_with_evaluation,
    write_artifact,
)


class FraudModelTest(unittest.TestCase):
    def _artifact_payload(
            self,
            model_version: str = "test-model-v1",
            model_type: str = "logistic",
            training_mode: str = "production",
            feature_schema: list[str] | None = None,
            weights: dict[str, float] | None = None,
    ) -> dict[str, object]:
        schema = feature_schema or (
            list(FeaturePipeline.PRODUCTION_FEATURE_NAMES)
            if training_mode == "production"
            else list(FeaturePipeline.FEATURE_NAMES)
        )
        payload: dict[str, object] = {
            "modelName": "python-logistic-fraud-model",
            "modelVersion": model_version,
            "modelType": model_type,
            "modelFamily": "LOGISTIC_REGRESSION" if model_type == "logistic" else "XGBOOST",
            "trainingMode": training_mode,
            "featureSchema": schema,
            "featureSetUsed": schema,
            "featureContractVersion": FEATURE_CONTRACT.version,
            "featureSchemaVersion": FEATURE_CONTRACT.version,
            "featureSetVersion": FEATURE_CONTRACT.version,
            "thresholds": {"medium": 0.45, "high": 0.75, "critical": 0.9},
            "thresholdPolicy": {
                "policyVersion": "fixed-business-risk-thresholds-v1",
                "ownership": "fixed_business_risk_thresholds",
                "runtimeSemantics": "riskLevel bands are business-owned; alertRecommended is true for HIGH or CRITICAL",
                "deployedAlertThresholdName": "high",
                "deployedAlertThreshold": 0.75,
                "thresholds": {"medium": 0.45, "high": 0.75, "critical": 0.9},
            },
            "modelRuntimeReadiness": self._ready_model_runtime_readiness(),
            "evaluation": {
                "modelRuntimeReadiness": self._ready_model_runtime_readiness(),
            },
            "training": {
                "trainingMode": training_mode,
                "featureSetUsed": schema,
                "featureContractVersion": FEATURE_CONTRACT.version,
                "featureSetVersion": FEATURE_CONTRACT.version,
            },
        }
        if model_type == "logistic":
            payload["bias"] = -2.0
            payload["weights"] = weights or {name: 0.0 for name in schema}
        return payload

    def _ready_model_runtime_readiness(self) -> dict[str, object]:
        return {
            "status": "READY",
            "reasons": [],
            "policyVersion": "fixed-business-risk-thresholds-v1",
            "deployedAlertThresholdName": "high",
            "deployedAlertThreshold": 0.75,
            "temporalFraudCaptureRate": 0.5,
            "outOfTimeFraudCaptureRate": 0.5,
            "rankingMetricsAreNotSufficient": True,
        }

    def _production_payload(self, **overrides: object) -> dict[str, object]:
        payload: dict[str, object] = {
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
        payload.update(overrides)
        return payload

    def _ordered_production_dataset(self, labels: list[int]) -> Dataset:
        rows = [
            self._production_payload(
                recentTransactionCount=2 if label else 1,
                recentAmountSumPln=20_000.0 if label else 100.0,
                currentTransactionAmountPln=10_000.0 if label else 100.0,
                transactionVelocityPerMinute=2.0 if label else 1.0,
                merchantFrequency7d=6 if label else 1,
                deviceNovelty=bool(label),
                countryMismatch=bool(label),
                proxyOrVpnDetected=bool(label),
            )
            for label in labels
        ]
        return Dataset(X=rows, y=labels, metadata={"source": "ordered-production-unit"})

    def _timestamped_production_dataset(
            self,
            labels: list[int],
            label_dependent_features: bool = True,
    ) -> Dataset:
        rows = []
        for index, label in enumerate(labels):
            payload = self._production_payload(
                timestamp=f"2026-01-{index + 1:02d}T00:00:00",
            )
            if label_dependent_features:
                payload.update(
                    recentTransactionCount=2 if label else 1,
                    recentAmountSumPln=20_000.0 if label else 100.0,
                    currentTransactionAmountPln=10_000.0 if label else 100.0,
                    transactionVelocityPerMinute=2.0 if label else 1.0,
                    merchantFrequency7d=6 if label else 1,
                    deviceNovelty=bool(label),
                    countryMismatch=bool(label),
                    proxyOrVpnDetected=bool(label),
                )
            rows.append(payload)
        return Dataset(X=rows, y=labels, metadata={"source": "timestamped-production-unit"})

    def _runtime_with_weights(self, weights: dict[str, float]) -> FraudModelRuntime:
        schema_weights = {name: 0.0 for name in FeaturePipeline.PRODUCTION_FEATURE_NAMES}
        schema_weights.update(weights)
        artifact = self._artifact_payload(weights=schema_weights)
        return FraudModelRuntime(
            Path.cwd() / "missing-runtime-artifact.json",
            model=LogisticFraudModel(artifact),
        )

    def test_scores_high_risk_signal_as_high_or_critical(self):
        result = FraudModel().score(
            {
                "recentTransactionCount": 8,
                "currentTransactionAmountPln": 28_800.0,
                "currency": "USD",
                "transactionVelocityPerMinute": 8.0,
                "merchantFrequency7d": 9,
                "deviceNovelty": True,
                "countryMismatch": True,
                "proxyOrVpnDetected": True,
                "recentTransactionCountWindow": "PT1M",
                "recentAmountSumWindow": "PT1M",
                "recentAmountSumPln": 28_800.0,
            }
        )

        self.assertTrue(result["available"])
        self.assertIn(result["riskLevel"], {"HIGH", "CRITICAL"})
        self.assertGreaterEqual(result["fraudScore"], 0.75)
        self.assertIn("PROXY_OR_VPN", result["reasonCodes"])
        self.assertEqual(result["featureContractVersion"], FEATURE_CONTRACT.version)
        self.assertEqual(result["explanationMetadata"]["featureContractVersion"], FEATURE_CONTRACT.version)

    def test_scores_baseline_signal_as_low(self):
        result = FraudModel().score(
            {
                "recentTransactionCount": 1,
                "currentTransactionAmountPln": 180.0,
                "currency": "USD",
                "transactionVelocityPerMinute": 1.0,
                "merchantFrequency7d": 1,
                "deviceNovelty": False,
                "countryMismatch": False,
                "proxyOrVpnDetected": False,
                "recentTransactionCountWindow": "PT1M",
                "recentAmountSumWindow": "PT1M",
                "recentAmountSumPln": 180.0,
            }
        )

        self.assertEqual(result["riskLevel"], "LOW")
        self.assertLess(result["fraudScore"], 0.45)

    def test_scores_rapid_transfer_burst_as_high_or_critical(self):
        result = FraudModel().score(
            {
                "recentTransactionCount": 2,
                "recentAmountSumPln": 20000.0,
                "currentTransactionAmountPln": 10000.0,
                "currency": "PLN",
                "recentTransactionCountWindow": "PT1M",
                "recentAmountSumWindow": "PT1M",
                "transactionVelocityPerMinute": 2.0,
                "merchantFrequency7d": 1,
                "deviceNovelty": False,
                "countryMismatch": False,
                "proxyOrVpnDetected": False,
            }
        )

        self.assertIn(result["riskLevel"], {"HIGH", "CRITICAL"})
        self.assertIn("RAPID_PLN_20K_BURST", result["reasonCodes"])

    def test_keeps_rapid_transfer_seed_without_aggregate_signal_low(self):
        result = FraudModel().score(
            {
                "recentTransactionCount": 1,
                "recentAmountSumPln": 1000.0,
                "currentTransactionAmountPln": 500.0,
                "currency": "PLN",
                "recentTransactionCountWindow": "PT1M",
                "recentAmountSumWindow": "PT1M",
                "transactionVelocityPerMinute": 1.0,
                "merchantFrequency7d": 1,
                "deviceNovelty": False,
                "countryMismatch": False,
                "proxyOrVpnDetected": False,
            }
        )

        self.assertEqual(result["riskLevel"], "LOW")
        self.assertNotIn("RAPID_PLN_20K_BURST", result["reasonCodes"])

    def test_negative_model_contribution_does_not_become_fraud_reason_code(self):
        runtime = self._runtime_with_weights({"suspiciousFactRatio": -3.0})

        result = runtime.score(
            self._production_payload(
                recentTransactionCount=2,
                recentAmountSumPln=6_000.0,
                currentTransactionAmountPln=3_000.0,
                transactionVelocityPerMinute=2.0,
                merchantFrequency7d=6,
                deviceNovelty=True,
                countryMismatch=True,
                proxyOrVpnDetected=True,
            )
        )

        self.assertTrue(result["available"])
        self.assertNotIn("MODEL_HIGH_RISK", result["reasonCodes"])
        self.assertLess(result["scoreDetails"]["featureContributions"]["suspiciousFactRatio"], 0.0)

    def test_positive_model_contribution_can_become_canonical_fraud_reason_code(self):
        runtime = self._runtime_with_weights({"suspiciousFactRatio": 3.0})

        result = runtime.score(
            self._production_payload(
                recentTransactionCount=2,
                recentAmountSumPln=6_000.0,
                currentTransactionAmountPln=3_000.0,
                transactionVelocityPerMinute=2.0,
                merchantFrequency7d=6,
                deviceNovelty=True,
                countryMismatch=True,
                proxyOrVpnDetected=True,
            )
        )

        self.assertTrue(result["available"])
        self.assertIn("MODEL_HIGH_RISK", result["reasonCodes"])
        self.assertGreater(result["scoreDetails"]["featureContributions"]["suspiciousFactRatio"], 0.0)

    def test_low_risk_baseline_does_not_emit_negative_signals_as_fraud_reasons(self):
        runtime = self._runtime_with_weights(
            {
                "recentTransactionCount": -2.0,
                "recentAmountSumPln": -2.0,
                "transactionVelocityPerMinute": -2.0,
            }
        )

        result = runtime.score(self._production_payload())

        self.assertEqual(result["riskLevel"], "LOW")
        self.assertEqual(result["reasonCodes"], [])
        self.assertLess(result["scoreDetails"]["featureContributions"]["recentTransactionCount"], 0.0)
        self.assertLess(result["scoreDetails"]["featureContributions"]["recentAmountSumPln"], 0.0)
        self.assertLess(result["scoreDetails"]["featureContributions"]["transactionVelocityPerMinute"], 0.0)

    def test_negative_contribution_still_affects_logit_and_fraud_score(self):
        neutral = self._runtime_with_weights({})
        negative = self._runtime_with_weights({"recentTransactionCount": -4.0})
        payload = self._production_payload(recentTransactionCount=5, transactionVelocityPerMinute=5.0)

        neutral_result = neutral.score(payload)
        negative_result = negative.score(payload)

        self.assertLess(
            negative_result["scoreDetails"]["logit"],
            neutral_result["scoreDetails"]["logit"],
        )
        self.assertLess(negative_result["fraudScore"], neutral_result["fraudScore"])
        self.assertLess(negative_result["scoreDetails"]["featureContributions"]["recentTransactionCount"], 0.0)
        self.assertNotIn("RECENT_TRANSACTION_SPIKE", negative_result["reasonCodes"])

    def test_reason_code_ordering_uses_positive_contribution_magnitude(self):
        runtime = self._runtime_with_weights(
            {
                "deviceNovelty": 0.2,
                "countryMismatch": 0.9,
                "proxyOrVpnDetected": 0.5,
            }
        )

        result = runtime.score(
            self._production_payload(
                deviceNovelty=True,
                countryMismatch=True,
                proxyOrVpnDetected=True,
            )
        )

        self.assertEqual(
            result["reasonCodes"][:3],
            ["COUNTRY_MISMATCH", "PROXY_OR_VPN", "DEVICE_NOVELTY"],
        )

    def test_feature_pipeline_normalizes_single_event(self):
        normalized = FeaturePipeline().transform_single(
            {
                "recentTransactionCount": 20,
                "transactionVelocityPerMinute": 20,
                "merchantFrequency7d": 24,
                "deviceNovelty": True,
                "countryMismatch": False,
                "proxyOrVpnDetected": True,
                "recentTransactionCountWindow": "PT1M",
                "recentAmountSumWindow": "PT1M",
                "recentAmountSumPln": 20_000.0,
            }
        )

        self.assertEqual(normalized["recentTransactionCount"], 1.0)
        self.assertEqual(normalized["recentAmountSumPln"], 1.0)
        self.assertEqual(normalized["transactionVelocityPerMinute"], 1.0)
        self.assertEqual(normalized["merchantFrequency7d"], 1.0)
        self.assertEqual(normalized["deviceNovelty"], 1.0)
        self.assertEqual(normalized["countryMismatch"], 0.0)
        self.assertEqual(normalized["proxyOrVpnDetected"], 1.0)
        self.assertEqual(normalized["suspiciousFactRatio"], 5.0 / 6.0)
        self.assertEqual(normalized["rapidTransferBurst"], 1.0)

    def test_python_feature_pipeline_uses_shared_contract_schema(self):
        self.assertEqual(FeaturePipeline.FEATURE_NAMES, FEATURE_CONTRACT.ml_feature_names)
        self.assertIn("recentTransactionCount", FEATURE_CONTRACT.java_enriched_feature_names)
        self.assertIn("rapidTransferTransactionIds", FEATURE_CONTRACT.java_enriched_feature_names)
        self.assertIn("recentTransactionCountWindow", FEATURE_CONTRACT.java_enriched_feature_names)
        self.assertIn("recentAmountSumWindow", FEATURE_CONTRACT.java_enriched_feature_names)
        self.assertIn("recentAmountSumPln", FEATURE_CONTRACT.java_enriched_feature_names)
        self.assertNotEqual(FEATURE_CONTRACT.version, "fallback")

    def test_production_contract_can_load_from_explicit_packaged_path(self):
        contract_path = Path.cwd() / "explicit-fraud-feature-contract.json"
        canonical_path = (
            Path(__file__).resolve().parents[2]
            / "common-events"
            / "src"
            / "main"
            / "resources"
            / "feature-contract"
            / "fraud-feature-contract.json"
        )
        try:
            contract_path.write_text(canonical_path.read_text(encoding="utf-8"), encoding="utf-8")
            with patch.dict(os.environ, {PRODUCTION_CONTRACT_ENV: str(contract_path)}, clear=False):
                loaded = FeatureContract.load()
        finally:
            if contract_path.exists():
                contract_path.unlink()

        self.assertEqual(loaded.version, FEATURE_CONTRACT.version)
        self.assertEqual(loaded.production_inference_features, FEATURE_CONTRACT.production_inference_features)

    def test_missing_production_contract_fails_startup(self):
        missing_path = Path.cwd() / "missing-fraud-feature-contract.json"
        with patch.dict(os.environ, {PRODUCTION_CONTRACT_ENV: str(missing_path)}, clear=False):
            with self.assertRaisesRegex(RuntimeError, "Configured feature contract does not exist"):
                FeatureContract.load()

    def test_invalid_production_contract_fails_startup(self):
        contract_path = Path.cwd() / "invalid-fraud-feature-contract.json"
        try:
            contract_path.write_text("{not-json", encoding="utf-8")
            with patch.dict(os.environ, {PRODUCTION_CONTRACT_ENV: str(contract_path)}, clear=False):
                with self.assertRaisesRegex(RuntimeError, "Feature contract JSON is invalid"):
                    FeatureContract.load()
        finally:
            if contract_path.exists():
                contract_path.unlink()

    def test_production_contract_fallback_requires_explicit_test_opt_in(self):
        with patch.object(feature_contract_module, "_contract_path", return_value=None):
            with patch.dict(os.environ, {ALLOW_FALLBACK_ENV: "true"}, clear=False):
                self.assertEqual(FeatureContract.load().version, "fallback")
            with patch.dict(os.environ, {ALLOW_FALLBACK_ENV: ""}, clear=False):
                with self.assertRaisesRegex(RuntimeError, "Canonical fraud feature contract is required"):
                    FeatureContract.load()

    def test_production_cannot_use_fallback_contract(self):
        with self.assertRaisesRegex(RuntimeError, "must not be fallback"):
            FeatureContract(feature_contract_module._fallback_contract())

    def test_feature_contract_required_lists_fail_closed(self):
        cases = {
            "missing_ml": ("mlFeatureNames", None, "mlFeatureNames must be a list"),
            "wrong_type_ml": ("mlFeatureNames", "recentTransactionCount", "mlFeatureNames must be a list"),
            "duplicate_ml": (
                "mlFeatureNames",
                [*FEATURE_CONTRACT.ml_feature_names, FEATURE_CONTRACT.ml_feature_names[0]],
                "mlFeatureNames contains duplicates",
            ),
            "missing_production": ("productionInferenceFeatures", None, "productionInferenceFeatures must be a list"),
            "wrong_type_production": (
                "productionInferenceFeatures",
                "recentTransactionCount",
                "productionInferenceFeatures must be a list",
            ),
            "duplicate_production": (
                "productionInferenceFeatures",
                [*FEATURE_CONTRACT.production_inference_features, FEATURE_CONTRACT.production_inference_features[0]],
                "productionInferenceFeatures contains duplicates",
            ),
        }
        for case_name, (field, value, message) in cases.items():
            with self.subTest(case_name=case_name):
                payload = self._canonical_feature_contract_payload()
                if value is None:
                    payload.pop(field)
                else:
                    payload[field] = value

                with self.assertRaisesRegex(RuntimeError, message):
                    FeatureContract(payload)

    def test_feature_contract_rejects_unknown_reference_keys(self):
        cases = {
            "unknown_availability": ("featureAvailability", "ghostFeature", "providedByJava", "featureAvailability contains unknown"),
            "unknown_normalization": ("normalization", "ghostFeature", {"source": "ghost"}, "normalization contains unknown"),
        }
        for case_name, (field, key, value, message) in cases.items():
            with self.subTest(case_name=case_name):
                payload = self._canonical_feature_contract_payload()
                payload[field] = {**payload[field], key: value}

                with self.assertRaisesRegex(RuntimeError, message):
                    FeatureContract(payload)

    def _canonical_feature_contract_payload(self) -> dict[str, object]:
        path = Path(__file__).resolve().parents[2] / "common-events" / "src" / "main" / "resources" / "feature-contract" / "fraud-feature-contract.json"
        return json.loads(path.read_text(encoding="utf-8"))

    def test_ml_production_image_packages_canonical_feature_contract(self):
        dockerfile = (Path(__file__).resolve().parents[2] / "deployment" / "Dockerfile.ml-inference").read_text(
            encoding="utf-8"
        )

        self.assertIn('ENV FRAUD_FEATURE_CONTRACT_PATH="/app/contracts/fraud-feature-contract.json"', dockerfile)
        self.assertIn(
            "COPY --chown=10001:10001 common-events/src/main/resources/feature-contract/fraud-feature-contract.json "
            "/app/contracts/fraud-feature-contract.json",
            dockerfile,
        )

    def test_shared_contract_defines_java_python_semantics_not_just_names(self):
        semantics = FEATURE_CONTRACT.production_feature_semantics
        strict_typing = FEATURE_CONTRACT.strict_typing_expectations

        self.assertEqual(FeaturePipeline.PRODUCTION_FEATURE_NAMES, FEATURE_CONTRACT.production_inference_features)
        self.assertEqual(set(semantics), set(FEATURE_CONTRACT.production_inference_features))
        self.assertEqual(set(FEATURE_CONTRACT.supported_currencies), SUPPORTED_CURRENCIES)
        self.assertNotIn("recentAmountSum", FEATURE_CONTRACT.production_inference_features)

        expected = {
            "recentTransactionCount": ("integer", "count", "PT1M"),
            "recentAmountSumPln": ("decimal", "PLN", "PT1M"),
            "transactionVelocityPerMinute": ("double", "transactions_per_minute", "PT1M"),
            "merchantFrequency7d": ("integer", "count", "P7D"),
            "deviceNovelty": ("boolean", "flag", "current_transaction"),
            "countryMismatch": ("boolean", "flag", "current_transaction"),
            "proxyOrVpnDetected": ("boolean", "flag", "current_transaction"),
            "suspiciousFactRatio": ("double", "ratio", "derived_from_current_production_facts"),
            "rapidTransferBurst": ("boolean_numeric", "flag", "PT1M"),
        }
        for name, (feature_type, unit, window) in expected.items():
            with self.subTest(feature=name):
                self.assertEqual(semantics[name]["type"], feature_type)
                self.assertEqual(semantics[name]["unit"], unit)
                self.assertEqual(semantics[name]["window"], window)
                self.assertIn("source", semantics[name])

        self.assertEqual(semantics["recentTransactionCount"]["bounds"]["max"], MAX_RECENT_TRANSACTION_COUNT)
        self.assertEqual(semantics["merchantFrequency7d"]["bounds"]["max"], MAX_RECENT_TRANSACTION_COUNT)
        self.assertEqual(semantics["transactionVelocityPerMinute"]["bounds"]["max"], MAX_TRANSACTION_VELOCITY_PER_MINUTE)
        self.assertAlmostEqual(semantics["recentAmountSumPln"]["bounds"]["max"], MAX_RECENT_AMOUNT_SUM_PLN)
        self.assertEqual(semantics["recentAmountSumPln"]["currencyBasis"], "PLN")
        self.assertEqual(semantics["rapidTransferBurst"]["currencyBasis"], "PLN")
        self.assertEqual(semantics["rapidTransferBurst"]["threshold"]["count"], 2)
        self.assertEqual(semantics["rapidTransferBurst"]["threshold"]["amountPln"], 20000)
        self.assertIn(str(RATE_CONSISTENCY_TOLERANCE), semantics["transactionVelocityPerMinute"]["consistency"])

        self.assertEqual(
            strict_typing["integerRejects"],
            ["string", "fractional", "boolean", "negative", "object", "array"],
        )
        self.assertEqual(
            strict_typing["decimalRejects"],
            ["string", "boolean", "negative", "nan", "infinity", "object", "array"],
        )
        self.assertEqual(
            strict_typing["booleanRejects"],
            ["string", "number", "object", "array", "null"],
        )
        self.assertEqual(strict_typing["windows"]["recentTransactionCountWindow"], "PT1M")
        self.assertEqual(strict_typing["windows"]["recentAmountSumWindow"], "PT1M")
        self.assertEqual(strict_typing["rateConsistency"], "transactionVelocityPerMinute == recentTransactionCount / PT1M")
        self.assertEqual(strict_typing["currentTransactionAmountPln"]["currencyBasis"], "PLN")
        self.assertAlmostEqual(strict_typing["currentTransactionAmountPln"]["bounds"]["max"], MAX_RECENT_AMOUNT_SUM_PLN)

    def test_java_enriched_snapshot_normalizes_to_contract_features(self):
        payload = {
            "recentTransactionCount": 5,
            "currentTransactionAmountPln": 5000.0,
            "currency": "PLN",
            "transactionVelocityPerMinute": 5.0,
            "merchantFrequency7d": 6,
            "deviceNovelty": True,
            "countryMismatch": False,
            "proxyOrVpnDetected": True,
            "recentTransactionCountWindow": "PT1M",
            "recentAmountSumWindow": "PT1M",
            "recentAmountSumPln": 10000.0,
        }
        pipeline = FeaturePipeline()
        normalized = pipeline.transform_single(payload)
        compatibility = pipeline.validate_production_snapshot(payload)

        self.assertEqual(set(normalized), set(FEATURE_CONTRACT.ml_feature_names))
        self.assertTrue(compatibility["compatible"])
        self.assertIn("transactionVelocityPerHour", compatibility["trainingOnlyFeatures"])
        self.assertIn("suspiciousFactRatio", compatibility["derivedInPython"])
        self.assertEqual(normalized["recentTransactionCount"], 0.5)
        self.assertEqual(normalized["recentAmountSumPln"], 1.0)
        self.assertEqual(normalized["transactionVelocityPerMinute"], 1.0)
        self.assertEqual(normalized["merchantFrequency7d"], 0.5)
        self.assertEqual(normalized["deviceNovelty"], 1.0)
        self.assertEqual(normalized["countryMismatch"], 0.0)
        self.assertEqual(normalized["proxyOrVpnDetected"], 1.0)
        self.assertEqual(normalized["suspiciousFactRatio"], 4.0 / 6.0)
        self.assertEqual(normalized["rapidTransferBurst"], 0.0)

    def test_production_feature_vector_is_derived_from_current_canonical_fields(self):
        canonical = {
            "recentTransactionCount": 2,
            "currentTransactionAmountPln": 10000.0,
            "currency": "PLN",
            "transactionVelocityPerMinute": 2.0,
            "merchantFrequency7d": 1,
            "deviceNovelty": False,
            "countryMismatch": False,
            "proxyOrVpnDetected": False,
            "recentTransactionCountWindow": "PT1M",
            "recentAmountSumWindow": "PT1M",
            "recentAmountSumPln": 20000.0,
        }
        additive_transport_overlay = {
            **canonical,
            "unsupportedPolicyMarker": "ignored",
        }

        pipeline = FeaturePipeline()

        self.assertEqual(
            pipeline.transform_single(canonical, mode="production"),
            pipeline.transform_single(additive_transport_overlay, mode="production"),
        )

    def test_legacy_recent_amount_sum_additive_field_is_ignored(self):
        canonical = {
            "recentTransactionCount": 2,
            "recentAmountSumPln": 1000.0,
            "currentTransactionAmountPln": 500.0,
            "currency": "GBP",
            "transactionVelocityPerMinute": 2.0,
            "merchantFrequency7d": 1,
            "deviceNovelty": False,
            "countryMismatch": False,
            "proxyOrVpnDetected": False,
            "recentTransactionCountWindow": "PT1M",
            "recentAmountSumWindow": "PT1M",
        }
        legacy_overlay = {
            **canonical,
            "recentAmountSum": {"amount": 200.0, "currency": "GBP"},
        }

        pipeline = FeaturePipeline()

        self.assertEqual(
            pipeline.transform_single(canonical, mode="production"),
            pipeline.transform_single(legacy_overlay, mode="production"),
        )
        self.assertEqual(
            pipeline.validate_production_snapshot(canonical),
            pipeline.validate_production_snapshot(legacy_overlay),
        )

    def test_production_monetary_feature_uses_normalized_pln_basis_for_supported_currencies(self):
        payload = {
            "recentTransactionCount": 2,
            "recentAmountSumPln": 1000.0,
            "currentTransactionAmountPln": 500.0,
            "currency": "GBP",
            "transactionVelocityPerMinute": 2.0,
            "merchantFrequency7d": 1,
            "deviceNovelty": False,
            "countryMismatch": False,
            "proxyOrVpnDetected": False,
            "recentTransactionCountWindow": "PT1M",
            "recentAmountSumWindow": "PT1M",
        }

        pipeline = FeaturePipeline()
        compatibility = pipeline.validate_production_snapshot(payload)
        normalized = pipeline.transform_single(payload, mode="production")

        self.assertTrue(compatibility["compatible"])
        self.assertEqual(normalized["recentAmountSumPln"], 0.1)
        self.assertNotIn("recentAmountSum", normalized)

    def test_production_training_features_match_java_inference_schema(self):
        dataset = generate_fraud_behavior(count=1000, seed=301, user_count=8, fraud_ratio=0.03)
        _, weights, evaluation = train_with_evaluation(dataset, epochs=2, learning_rate=0.1)
        payload = {
            "recentTransactionCount": 5,
            "currentTransactionAmountPln": 5000.0,
            "currency": "PLN",
            "transactionVelocityPerMinute": 5.0,
            "merchantFrequency7d": 6,
            "deviceNovelty": True,
            "countryMismatch": False,
            "proxyOrVpnDetected": True,
            "recentTransactionCountWindow": "PT1M",
            "recentAmountSumWindow": "PT1M",
            "recentAmountSumPln": 10000.0,
        }
        inference_features = FeaturePipeline().transform_single(payload, mode="production")

        self.assertEqual(list(weights), evaluation["featureSetUsed"])
        self.assertEqual(list(inference_features), evaluation["featureSetUsed"])
        self.assertEqual(set(inference_features), set(FEATURE_CONTRACT.production_inference_features))
        self.assertEqual(
            [name for name in evaluation["featureSetUsed"] if name not in inference_features],
            [],
            "missing production inference features",
        )

    def test_feature_pipeline_reports_missing_required_production_features(self):
        compatibility = FeaturePipeline().validate_production_snapshot(
            {
                "recentTransactionCount": 5,
            }
        )

        self.assertFalse(compatibility["compatible"])
        self.assertIn("recentTransactionCountWindow", compatibility["missingRequiredFeatures"])
        self.assertIn("recentAmountSumWindow", compatibility["missingRequiredFeatures"])
        self.assertIn("recentAmountSumPln", compatibility["missingRequiredFeatures"])
        self.assertIn("transactionVelocityPerMinute", compatibility["missingRequiredFeatures"])
        self.assertIn("merchantFrequency7d", compatibility["missingRequiredFeatures"])

    def test_runtime_fails_closed_when_canonical_windows_are_missing(self):
        result = FraudModel().score(
            {
                "recentTransactionCount": 2,
                "recentAmountSumPln": 20000.0,
                "currentTransactionAmountPln": 10000.0,
                "currency": "PLN",
                "transactionVelocityPerMinute": 2.0,
                "merchantFrequency7d": 1,
                "deviceNovelty": False,
                "countryMismatch": False,
                "proxyOrVpnDetected": False,
            }
        )

        self.assertFalse(result["available"])
        self.assertEqual(result["fallbackReason"], "INCOMPATIBLE_FEATURE_SNAPSHOT")
        self.assertEqual(result["scoreDetails"]["normalizedFeatures"], {})
        self.assertIn(
            "recentTransactionCountWindow",
            result["scoreDetails"]["featureCompatibility"]["missingRequiredFeatures"],
        )

    def test_runtime_rejects_raw_sequence_payload_in_production_inference(self):
        result = FraudModel().score(
            {
                "raw_transaction": {
                    "amount": 10000.0,
                    "currency": "PLN",
                    "deviceId": "raw-device",
                    "country": "PL",
                    "merchantId": "raw-merchant",
                    "proxyOrVpnDetected": True,
                },
                "timestamp": "2026-09-15T10:00:00",
                "user_id": "raw-user",
            }
        )

        self.assertFalse(result["available"])
        self.assertIsNone(result["fraudScore"])
        self.assertIsNone(result["riskLevel"])
        self.assertNotIn("alertRecommended", result)
        self.assertEqual(result["fallbackReason"], "INCOMPATIBLE_FEATURE_SNAPSHOT")
        self.assertEqual(result["scoreDetails"]["normalizedFeatures"], {})
        self.assertEqual(
            result["scoreDetails"]["featureCompatibility"]["invalidFeatures"]["raw_transaction"],
            "raw_sequence_not_allowed_for_production_inference",
        )

    def test_runtime_fails_closed_for_present_invalid_canonical_features(self):
        valid = {
            "recentTransactionCount": 2,
            "recentAmountSumPln": 20000.0,
            "currentTransactionAmountPln": 10000.0,
            "currency": "PLN",
            "transactionVelocityPerMinute": 2.0,
            "merchantFrequency7d": 1,
            "deviceNovelty": False,
            "countryMismatch": False,
            "proxyOrVpnDetected": False,
            "recentTransactionCountWindow": "PT1M",
            "recentAmountSumWindow": "PT1M",
        }
        invalid_cases = {
            "string_count": {"recentTransactionCount": "2"},
            "fractional_count": {"recentTransactionCount": 2.5},
            "bool_count": {"recentTransactionCount": True},
            "negative_count": {"recentTransactionCount": -1},
            "nan_amount": {"recentAmountSumPln": float("nan")},
            "infinite_amount": {"recentAmountSumPln": float("inf")},
            "negative_amount": {"recentAmountSumPln": -1.0},
            "nested_amount": {"recentAmountSumPln": {"amount": 20000.0}},
            "list_amount": {"recentAmountSumPln": [20000.0]},
            "invalid_window": {"recentTransactionCountWindow": "PT5M"},
            "inconsistent_rate": {"transactionVelocityPerMinute": 3.0},
            "unsupported_currency": {"currency": "CHF"},
            "null_currency": {"currency": None},
            "wrong_boolean": {"deviceNovelty": "true"},
        }

        for case_name, mutation in invalid_cases.items():
            with self.subTest(case_name=case_name):
                result = FraudModel().score({**valid, **mutation})

                self.assertFalse(result["available"])
                self.assertEqual(result["fallbackReason"], "INCOMPATIBLE_FEATURE_SNAPSHOT")
                self.assertIsNone(result["fraudScore"])
                self.assertIsNone(result["riskLevel"])
                self.assertNotIn("alertRecommended", result)
                self.assertEqual(result["scoreDetails"]["normalizedFeatures"], {})
                self.assertTrue(result["scoreDetails"]["featureCompatibility"]["invalidFeatures"])

    def test_feature_pipeline_transforms_raw_sequence_features(self):
        dataset = generate_fraud_behavior(count=200, seed=789, user_count=10, fraud_ratio=0.02)
        transformed = FeaturePipeline().fit(dataset).transform(dataset)

        self.assertEqual(len(transformed), dataset.size)
        self.assertTrue(all("recentAmountSumPln" in row for row in transformed))
        self.assertTrue(all("transactionVelocityPerHour" not in row for row in transformed))
        self.assertTrue(all("recentAmountAverage" not in row for row in transformed))
        self.assertTrue(all("merchantEntropy" not in row for row in transformed))
        self.assertTrue(all(0.0 <= value <= 1.0 for row in transformed for value in row.values()))

        fraud_rows = [
            features for source, features in zip(dataset.X, transformed)
            if source["metadata"]["scenario"] == "account_takeover"
        ]
        self.assertTrue(any(row["deviceNovelty"] == 1.0 or row["countryMismatch"] == 1.0 for row in fraud_rows))
        rapid_rows = [
            features for source, features in zip(dataset.X, transformed)
            if source["metadata"]["scenario"] == "rapid_transfer_burst"
        ]
        self.assertTrue(any(row["rapidTransferBurst"] == 1.0 for row in rapid_rows))

    def test_dataset_rejects_mismatched_features_and_labels(self):
        with self.assertRaises(ValueError):
            Dataset(X=[{"recentTransactionCount": 1.0}], y=[])

    def test_normal_behavior_generator_creates_reproducible_user_sequences(self):
        first = generate_normal_behavior(count=20, seed=123, user_count=4)
        second = generate_normal_behavior(count=20, seed=123, user_count=4)

        self.assertEqual(first.X, second.X)
        self.assertEqual(first.y, second.y)
        self.assertEqual(first.size, 20)
        self.assertEqual(set(first.y), {0})
        self.assertEqual(first.metadata["source"], "normal-user-behavior-sequences")

        timestamps = [row["timestamp"] for row in first.X]
        self.assertEqual(timestamps, sorted(timestamps))
        self.assertTrue(all(row["label"] is None for row in first.X))
        self.assertTrue(all("raw_transaction" in row for row in first.X))
        self.assertGreater(len({row["user_id"] for row in first.X}), 1)

    def test_fraud_behavior_generator_labels_realistic_scenarios(self):
        dataset = generate_fraud_behavior(count=500, seed=456, user_count=30, fraud_ratio=0.02)

        self.assertEqual(dataset.size, 500)
        fraud_count = sum(dataset.y)
        self.assertGreaterEqual(fraud_count / dataset.size, 0.01)
        self.assertLessEqual(fraud_count / dataset.size, 0.03)
        self.assertEqual(dataset.metadata["source"], "fraud-injected-user-behavior-sequences")
        self.assertTrue(dataset.metadata["fraud_injected"])

        scenarios = {row["metadata"]["scenario"] for row in dataset.X}
        self.assertIn("account_takeover", scenarios)
        self.assertIn("card_testing", scenarios)
        self.assertIn("rapid_transfer_burst", scenarios)
        self.assertIn("low_and_slow", scenarios)
        self.assertIn("legitimate_anomaly", scenarios)
        self.assertTrue(all(row["label"] == bool(label) for row, label in zip(dataset.X, dataset.y)))
        self.assertTrue(all("scenarioDebug" in row["metadata"] for row in dataset.X if row["metadata"]["scenario"] != "normal_behavior"))

    def test_training_artifact_contains_model_metadata_and_feature_schema(self):
        dataset = generate_fraud_behavior(count=1000, seed=301, user_count=8, fraud_ratio=0.03)
        bias, weights, evaluation = train_with_evaluation(dataset, epochs=2, learning_rate=0.1)

        artifact_path = Path.cwd() / "test-model-artifact.json"
        try:
            write_artifact(artifact_path, bias, weights, dataset.size, model_type="logistic", evaluation=evaluation)
            artifact = json.loads(artifact_path.read_text(encoding="utf-8"))
        finally:
            if artifact_path.exists():
                artifact_path.unlink()

        self.assertEqual(artifact["modelType"], "logistic")
        self.assertIn("featureSchema", artifact)
        self.assertIn("featureImportance", artifact)
        self.assertIn("evaluation", artifact)
        self.assertIn("prAuc", artifact["evaluation"])
        self.assertIn("splitMetadata", artifact["evaluation"])
        self.assertIn("outOfTimeEvaluation", artifact["evaluation"])
        self.assertIn("evaluationComparison", artifact["evaluation"])
        self.assertEqual(artifact["evaluation"]["selectedThresholdSource"], "fixed_business_risk_thresholds")
        self.assertEqual(artifact["thresholdPolicy"], artifact["evaluation"]["thresholdPolicy"])
        self.assertEqual(artifact["modelRuntimeReadiness"], artifact["evaluation"]["modelRuntimeReadiness"])
        self.assertIn("deployedAlertThresholdMetrics", artifact["evaluation"])
        self.assertIn("deployedAlertThresholdMetrics", artifact["evaluation"]["outOfTimeEvaluation"])
        self.assertEqual(artifact["evaluation"]["modelVersion"], CANONICAL_MODEL_VERSION)
        self.assertEqual(artifact["evaluation"]["featureContractVersion"], FEATURE_CONTRACT.version)
        self.assertEqual(artifact["evaluation"]["featureSchemaVersion"], FEATURE_CONTRACT.version)
        self.assertEqual(artifact["evaluation"]["featureSetVersion"], FEATURE_CONTRACT.version)
        self.assertEqual(artifact["modelVersion"], CANONICAL_MODEL_VERSION)
        self.assertEqual(artifact["trainingMode"], "production")
        self.assertEqual(artifact["featureSetUsed"], list(weights))
        self.assertEqual(set(artifact["featureSchema"]), set(weights))
        self.assertEqual(artifact["featureContractVersion"], FEATURE_CONTRACT.version)
        self.assertEqual(artifact["featureSchemaVersion"], FEATURE_CONTRACT.version)
        self.assertEqual(artifact["featureSetVersion"], FEATURE_CONTRACT.version)
        self.assertEqual(artifact["training"]["featureContractVersion"], FEATURE_CONTRACT.version)
        self.assertEqual(artifact["training"]["featureSetVersion"], FEATURE_CONTRACT.version)

    def test_artifact_threshold_policy_matches_evaluation_policy(self):
        dataset = generate_fraud_behavior(count=1000, seed=301, user_count=8, fraud_ratio=0.03)
        bias, weights, evaluation = train_with_evaluation(dataset, epochs=2, learning_rate=0.1)
        artifact_path = Path.cwd() / "threshold-policy-artifact.json"
        try:
            write_artifact(artifact_path, bias, weights, dataset.size, model_type="logistic", evaluation=evaluation)
            artifact = json.loads(artifact_path.read_text(encoding="utf-8"))
        finally:
            if artifact_path.exists():
                artifact_path.unlink()

        self.assertEqual(artifact["thresholdPolicy"], artifact["evaluation"]["thresholdPolicy"])
        self.assertEqual(artifact["thresholds"], artifact["thresholdPolicy"]["thresholds"])
        self.assertEqual(artifact["thresholdPolicy"]["deployedAlertThresholdName"], "high")
        self.assertEqual(artifact["thresholdPolicy"]["deployedAlertThreshold"], artifact["thresholds"]["high"])

    def test_deployed_alert_threshold_has_recorded_temporal_metrics(self):
        dataset = generate_fraud_behavior(count=300, seed=324, user_count=8, fraud_ratio=0.03)
        _, _, evaluation = train_with_evaluation(dataset, epochs=2, learning_rate=0.1)

        deployed = evaluation["deployedAlertThresholdMetrics"]

        self.assertEqual(deployed["threshold"], evaluation["thresholdPolicy"]["deployedAlertThreshold"])
        self.assertIn("precision", deployed)
        self.assertIn("fraudCaptureRate", deployed)
        self.assertIn("falsePositiveRate", deployed)
        self.assertIn("alertRate", deployed)

    def test_deployed_alert_threshold_has_recorded_out_of_time_metrics(self):
        dataset = generate_fraud_behavior(count=300, seed=325, user_count=8, fraud_ratio=0.03)
        _, _, evaluation = train_with_evaluation(dataset, epochs=2, learning_rate=0.1)

        deployed = evaluation["outOfTimeEvaluation"]["deployedAlertThresholdMetrics"]

        self.assertEqual(deployed["threshold"], evaluation["thresholdPolicy"]["deployedAlertThreshold"])
        self.assertIn("precision", deployed)
        self.assertIn("fraudCaptureRate", deployed)
        self.assertIn("falsePositiveRate", deployed)
        self.assertIn("alertRate", deployed)

    def test_selected_threshold_source_matches_runtime_semantics(self):
        dataset = generate_fraud_behavior(count=300, seed=326, user_count=8, fraud_ratio=0.03)
        _, _, evaluation = train_with_evaluation(dataset, epochs=2, learning_rate=0.1)

        self.assertEqual(evaluation["selectedThresholdSource"], "fixed_business_risk_thresholds")
        self.assertEqual(evaluation["thresholdPolicy"]["ownership"], "fixed_business_risk_thresholds")
        self.assertNotEqual(
            evaluation["deployedAlertThresholdMetrics"]["threshold"],
            evaluation["validationEvaluation"]["optimalThreshold"]["threshold"],
        )

    def test_runtime_artifact_cannot_hide_zero_capture_behind_ranking_metrics(self):
        evaluation = {
            "prAuc": 0.85,
            "rocAuc": 0.9,
            "selectedThresholdSource": "fixed_business_risk_thresholds",
            "thresholdPolicy": {
                "policyVersion": "fixed-business-risk-thresholds-v1",
                "deployedAlertThresholdName": "high",
                "deployedAlertThreshold": 0.75,
                "thresholds": {"medium": 0.45, "high": 0.75, "critical": 0.9},
            },
            "deployedAlertThresholdMetrics": {
                "threshold": 0.75,
                "fraudCaptureRate": 0.5,
                "precision": 1.0,
                "falsePositiveRate": 0.0,
                "alertRate": 0.01,
            },
            "outOfTimeEvaluation": {
                "prAuc": 0.85,
                "rocAuc": 0.9,
                "deployedAlertThresholdMetrics": {
                    "threshold": 0.75,
                    "fraudCaptureRate": 0.0,
                    "precision": 0.0,
                    "falsePositiveRate": 0.0,
                    "alertRate": 0.0,
                },
            },
            "modelRuntimeReadiness": {
                "status": "NOT_READY",
                "reasons": ["OUT_OF_TIME_DEPLOYED_ALERT_THRESHOLD_ZERO_FRAUD_CAPTURE"],
                "policyVersion": "fixed-business-risk-thresholds-v1",
                "deployedAlertThresholdName": "high",
                "deployedAlertThreshold": 0.75,
                "temporalFraudCaptureRate": 0.5,
                "outOfTimeFraudCaptureRate": 0.0,
                "rankingMetricsAreNotSufficient": True,
            },
        }
        schema = list(FeaturePipeline.PRODUCTION_FEATURE_NAMES)
        artifact_path = Path.cwd() / "not-ready-artifact.json"
        try:
            write_artifact(
                artifact_path,
                bias=-2.0,
                weights={name: 0.0 for name in schema},
                examples=100,
                model_type="logistic",
                evaluation=evaluation,
            )
            artifact = json.loads(artifact_path.read_text(encoding="utf-8"))
            with self.assertRaisesRegex(ModelConfigurationError, "model runtime readiness failed"):
                load_model_from_artifact(artifact_path)
        finally:
            if artifact_path.exists():
                artifact_path.unlink()

        self.assertEqual(artifact["modelRuntimeReadiness"]["status"], "NOT_READY")
        self.assertIn(
            "OUT_OF_TIME_DEPLOYED_ALERT_THRESHOLD_ZERO_FRAUD_CAPTURE",
            artifact["modelRuntimeReadiness"]["reasons"],
        )

    def test_model_lifecycle_report_schema_is_consistent_for_logistic(self):
        dataset = generate_fraud_behavior(count=300, seed=322, user_count=8, fraud_ratio=0.03)
        model, evaluation = train_model_with_evaluation(dataset, "logistic", epochs=2, learning_rate=0.1)
        expected_keys = {
            "rows",
            "positiveLabels",
            "negativeLabels",
            "prAuc",
            "rocAuc",
            "precisionAtK",
            "recallAtK",
            "thresholds",
            "optimalThreshold",
            "costEvaluation",
            "budgetEvaluation",
            "splitMetadata",
            "validationEvaluation",
            "outOfTimeEvaluation",
            "evaluationComparison",
            "stabilityAssessment",
            "modelRuntimeReadiness",
            "trainingMode",
            "featureSetUsed",
            "segmentEvaluation",
        }

        self.assertEqual(model.model_family, "LOGISTIC_REGRESSION")
        self.assertTrue(expected_keys.issubset(evaluation))
        self.assertEqual(evaluation["trainingMode"], "production")

    def test_budget_and_segment_evaluation_are_reported(self):
        report = evaluate_scores(
            y_true=[0, 1, 0, 1, 0, 0],
            y_score=[0.05, 0.95, 0.40, 0.80, 0.10, 0.02],
            thresholds=[0.50],
            alert_budgets=[0.5],
            segment_rows=[
                {"country": "PL", "metadata": {"scenario": "normal_behavior"}},
                {"country": "PL", "metadata": {"scenario": "account_takeover"}},
                {"country": "DE", "metadata": {"scenario": "normal_behavior"}},
                {"country": "DE", "metadata": {"scenario": "card_testing"}},
                {"country": "PL", "metadata": {"scenario": "normal_behavior"}},
                {"country": "DE", "metadata": {"scenario": "normal_behavior"}},
            ],
        )

        self.assertIn("budgetEvaluation", report)
        self.assertEqual(report["budgetEvaluation"]["recommended"]["alertBudget"], 0.5)
        self.assertIn("segmentEvaluation", report)
        self.assertIn("country", report["segmentEvaluation"])

    def test_dataset_split_prefers_temporal_order_and_separate_row_sets(self):
        dataset = generate_fraud_behavior(count=300, seed=222, user_count=4, fraud_ratio=0.03)
        splits = split_dataset(dataset)

        self.assertEqual(splits.metadata["strategy"], "stratified_temporal")
        self.assertTrue(set(splits.metadata["trainIndices"]).isdisjoint(splits.metadata["validationIndices"]))
        self.assertTrue(set(splits.metadata["trainIndices"]).isdisjoint(splits.metadata["testIndices"]))
        self.assertTrue(set(splits.metadata["validationIndices"]).isdisjoint(splits.metadata["testIndices"]))
        self.assertGreater(splits.train.size, 0)
        self.assertGreater(splits.validation.size, 0)
        self.assertGreater(splits.test.size, 0)
        self.assertGreater(splits.metadata["classDistribution"]["train"]["fraud"], 0)
        self.assertGreater(splits.metadata["classDistribution"]["validation"]["fraud"], 0)
        self.assertGreater(splits.metadata["classDistribution"]["test"]["fraud"], 0)
        rates = list(splits.metadata["fraudRate"].values())
        self.assertLessEqual(max(rates) - min(rates), 0.05)

    def test_out_of_time_split_uses_later_test_window(self):
        dataset = generate_fraud_behavior(count=300, seed=223, user_count=5, fraud_ratio=0.03)
        splits = split_dataset(dataset, mode="out_of_time", cutoff_ratio=0.6)

        train_timestamps = [row["timestamp"] for row in splits.train.X]
        test_timestamps = [row["timestamp"] for row in splits.test.X]
        self.assertEqual(splits.metadata["strategy"], "out_of_time")
        self.assertLess(max(train_timestamps), min(test_timestamps))

    def test_explicit_cutoff_timestamp_is_never_moved_for_class_balance(self):
        dataset = self._timestamped_production_dataset([0, 0, 0, 0, 1, 0, 1, 0, 1, 0])
        cutoff = "2026-01-06T00:00:00"

        splits = split_dataset(dataset, mode="out_of_time", cutoff_timestamp=cutoff)

        self.assertEqual(splits.metadata["requestedCutoffTimestamp"], cutoff)
        self.assertEqual(splits.metadata["effectiveCutoffTimestamp"], cutoff)
        self.assertEqual(splits.metadata["validationIndices"], [5])
        self.assertEqual(splits.metadata["testIndices"], [6, 7, 8, 9])
        with self.assertRaisesRegex(ValueError, "OUT_OF_TIME_VALIDATION_SPLIT_MUST_CONTAIN_BOTH_CLASSES"):
            _require_binary_evaluation_splits(splits, "out_of_time")

    def test_out_of_time_boundary_does_not_depend_on_labels(self):
        labels = [0, 0, 0, 1, 0, 1, 0, 0, 1, 0]
        changed_labels = [1, 1, 1, 0, 1, 0, 1, 1, 0, 1]
        first = self._timestamped_production_dataset(labels, label_dependent_features=False)
        second = self._timestamped_production_dataset(changed_labels, label_dependent_features=False)

        first_splits = split_dataset(first, mode="out_of_time", cutoff_ratio=0.5)
        second_splits = split_dataset(second, mode="out_of_time", cutoff_ratio=0.5)

        for key in (
                "trainIndices",
                "validationIndices",
                "testIndices",
                "trainEndTimestamp",
                "validationEndTimestamp",
                "testStartTimestamp",
                "effectiveCutoffTimestamp",
        ):
            self.assertEqual(first_splits.metadata[key], second_splits.metadata[key])

    def test_single_class_out_of_time_partition_fails_instead_of_moving_cutoff(self):
        dataset = self._timestamped_production_dataset([0, 0, 0, 1, 0, 1, 0, 0, 1, 0, 1, 0])

        splits = split_dataset(dataset, mode="out_of_time", cutoff_ratio=0.5)

        self.assertEqual(splits.metadata["trainIndices"], [0, 1, 2, 3, 4, 5])
        self.assertEqual(splits.metadata["validationIndices"], [6, 7])
        self.assertEqual(splits.metadata["testIndices"], [8, 9, 10, 11])
        with self.assertRaisesRegex(ValueError, "OUT_OF_TIME_VALIDATION_SPLIT_MUST_CONTAIN_BOTH_CLASSES"):
            _require_binary_evaluation_splits(splits, "out_of_time")

    def test_explicit_cutoff_timestamp_is_reflected_in_metadata(self):
        dataset = self._timestamped_production_dataset([0, 1, 0, 1, 0, 1, 0, 1, 0, 1])
        cutoff = "2026-01-06T00:00:00"

        splits = split_dataset(dataset, mode="out_of_time", cutoff_timestamp=cutoff)

        self.assertIsNone(splits.metadata["requestedCutoffRatio"])
        self.assertEqual(splits.metadata["requestedCutoffTimestamp"], cutoff)
        self.assertEqual(splits.metadata["effectiveCutoffTimestamp"], cutoff)
        self.assertEqual(splits.metadata["trainEndTimestamp"], "2026-01-05T00:00:00")
        self.assertEqual(splits.metadata["validationEndTimestamp"], cutoff)
        self.assertEqual(splits.metadata["testStartTimestamp"], "2026-01-07T00:00:00")

    def test_training_lifecycle_rejects_single_class_train_split_before_fit(self):
        dataset = self._ordered_production_dataset([0, 0, 0, 0, 0, 0, 0, 1, 0, 1])

        with patch.object(LogisticFraudModel, "fit", side_effect=AssertionError("fit must not run")):
            with self.assertRaisesRegex(ValueError, "TEMPORAL_TRAIN_SPLIT_MUST_CONTAIN_BOTH_CLASSES"):
                train_model_with_evaluation(dataset, "logistic", epochs=2, learning_rate=0.1)

    def test_training_lifecycle_rejects_single_class_validation_split_before_fit(self):
        dataset = self._ordered_production_dataset([0, 0, 0, 0, 0, 1, 0, 0, 0, 1])

        with patch.object(LogisticFraudModel, "fit", side_effect=AssertionError("fit must not run")):
            with self.assertRaisesRegex(ValueError, "TEMPORAL_VALIDATION_SPLIT_MUST_CONTAIN_BOTH_CLASSES"):
                train_model_with_evaluation(dataset, "logistic", epochs=2, learning_rate=0.1)

    def test_training_lifecycle_rejects_single_class_test_split_before_fit(self):
        dataset = self._ordered_production_dataset([0, 0, 0, 0, 0, 1, 0, 1, 0, 0])

        with patch.object(LogisticFraudModel, "fit", side_effect=AssertionError("fit must not run")):
            with self.assertRaisesRegex(ValueError, "TEMPORAL_TEST_SPLIT_MUST_CONTAIN_BOTH_CLASSES"):
                train_model_with_evaluation(dataset, "logistic", epochs=2, learning_rate=0.1)

    def test_training_lifecycle_accepts_binary_train_validation_and_test_splits(self):
        dataset = self._ordered_production_dataset([0, 0, 0, 0, 0, 1, 0, 1, 0, 1])

        _, evaluation = train_model_with_evaluation(dataset, "logistic", epochs=2, learning_rate=0.1)

        distribution = evaluation["splitMetadata"]["classDistribution"]
        self.assertGreater(distribution["train"]["fraud"], 0)
        self.assertGreater(distribution["validation"]["fraud"], 0)
        self.assertGreater(distribution["test"]["fraud"], 0)

    def test_evaluation_metrics_include_ranking_business_and_thresholds(self):
        report = evaluate_scores(
            y_true=[0, 1, 0, 1, 0],
            y_score=[0.05, 0.95, 0.40, 0.80, 0.10],
            thresholds=[0.50, 0.90],
            top_k=[1, 2],
        )

        self.assertEqual(report["rows"], 5)
        self.assertEqual(report["positiveLabels"], 2)
        self.assertEqual(report["prAuc"], 1.0)
        self.assertEqual(report["rocAuc"], 1.0)
        self.assertEqual(report["precisionAtK"]["2"], 1.0)
        self.assertEqual(report["recallAtK"]["2"], 1.0)
        self.assertEqual(len(report["thresholds"]), 2)
        self.assertIn("optimalThreshold", report)
        self.assertIn("costEvaluation", report)
        self.assertIn("optimalCostThreshold", report["costEvaluation"])
        self.assertIn("alertRate", report["optimalThreshold"])
        self.assertIn("prAuc=1.0", cli_summary(report))

    def test_evaluation_rejects_invalid_inputs(self):
        with self.assertRaises(ValueError):
            evaluate_scores(y_true=[], y_score=[])
        with self.assertRaises(ValueError):
            evaluate_scores(y_true=[0, 1], y_score=[0.1])

    def test_feedback_events_build_privacy_safe_training_dataset(self):
        event = {
            "decisionId": "decision-1",
            "transactionId": "txn-sensitive",
            "decision": "CONFIRMED_FRAUD",
            "decisionMetadata": {
                "modelScore": 0.87,
                "featureSnapshot": {
                    "recentTransactionCount": 5,
                    "recentAmountSumPln": 7000.0,
                    "transactionVelocityPerMinute": 5.0,
                    "merchantFrequency7d": 2,
                    "deviceNovelty": True,
                    "countryMismatch": False,
                    "proxyOrVpnDetected": True,
                },
            },
            "decidedAt": "2026-04-22T18:00:00Z",
        }

        feedback = feedback_from_decision_event(event)
        dataset = dataset_from_feedback([feedback])

        self.assertNotEqual(feedback.transaction_ref, "txn-sensitive")
        self.assertEqual(feedback.model_score, 0.87)
        self.assertEqual(feedback.label, 1)
        self.assertEqual(dataset.size, 1)
        self.assertEqual(dataset.y, [1])
        self.assertEqual(dataset.metadata["privacy"], "hashed identifiers only")

    def test_unresolved_feedback_is_excluded_from_training_dataset(self):
        feedback = feedback_from_decision_event(
            {
                "decisionId": "decision-more-evidence",
                "transactionId": "txn-more-evidence",
                "decision": "REQUIRE_MORE_EVIDENCE",
                "decisionMetadata": {"featureSnapshot": {"recentTransactionCount": 1}},
            }
        )

        dataset = dataset_from_feedback([feedback])

        self.assertIsNone(feedback.label)
        self.assertEqual(dataset.size, 0)
        self.assertEqual(dataset.y, [])

    def test_feedback_store_versions_and_delayed_label_updates(self):
        store = FeedbackDatasetStore(Path.cwd())
        initial = feedback_from_decision_event(
            {
                "decisionId": "decision-2",
                "transactionId": "txn-delayed",
                "decision": "REQUIRE_MORE_EVIDENCE",
                "decisionMetadata": {"featureSnapshot": {"recentTransactionCount": 1}},
                "decidedAt": "2026-04-22T18:00:00Z",
            }
        )
        first_path = None
        second_path = None
        try:
            first_path = store.save_version([initial], version="unit-feedback")
            second_path = store.update_label(first_path, initial.feedback_id, "MARKED_LEGITIMATE")
            updated = store.load_version(second_path)
        finally:
            for path in (first_path, second_path):
                if path and path.exists():
                    path.unlink()

        self.assertIsNone(initial.label)
        self.assertEqual(updated[0].label, 0)
        self.assertEqual(updated[0].analyst_decision, "MARKED_LEGITIMATE")

    def test_retraining_comparison_reports_challenger_metrics(self):
        dataset = generate_fraud_behavior(count=1000, seed=301, user_count=8, fraud_ratio=0.03)
        current_evaluation = {
            "prAuc": 0.0,
            "optimalThreshold": {"falsePositiveRate": 0.0, "alertRate": 0.05},
            "costEvaluation": {"optimalCostThreshold": {"totalCost": 10000.0}},
        }

        comparison = compare_retrained_model(
            dataset,
            current_evaluation,
            epochs=2,
            learning_rate=0.1,
            thresholds=PromotionThresholds(max_alert_rate=1.0),
        )

        self.assertGreaterEqual(comparison.challenger_pr_auc, 0.0)
        self.assertIn("criteria", comparison.decision)
        self.assertIn(comparison.decision["decision"], {"promote", "shadow_only", "reject"})
        self.assertIn("recommended_rollout_mode", comparison.decision)
        self.assertIn("prAuc", comparison.challenger_evaluation)
        self.assertIn("splitMetadata", comparison.challenger_evaluation)

    def test_rollout_decision_promote_shadow_and_reject_outcomes(self):
        current = self._evaluation_for_decision(pr_auc=0.6, fpr=0.05, alert_rate=0.05, cost=1000.0)
        promote = self._evaluation_for_decision(pr_auc=0.8, fpr=0.05, alert_rate=0.05, cost=900.0)
        shadow = self._evaluation_for_decision(pr_auc=0.8, fpr=0.05, alert_rate=0.05, cost=900.0)
        shadow["stabilityAssessment"] = {"prAucDelta": 0.4, "expectedCostDelta": 10.0}
        reject = self._evaluation_for_decision(pr_auc=0.4, fpr=0.30, alert_rate=0.80, cost=2000.0)

        thresholds = PromotionThresholds(alert_budget=0.01, max_alert_rate=0.5)

        self.assertEqual(_promotion_decision(current, promote, thresholds)["decision"], "promote")
        self.assertEqual(_promotion_decision(current, shadow, thresholds)["decision"], "shadow_only")
        self.assertEqual(_promotion_decision(current, reject, thresholds)["decision"], "reject")

    def test_model_registry_tracks_latest_champion_challenger_and_versions(self):
        artifact_path = Path.cwd() / "registry-test-artifact.json"
        registry_path = Path.cwd() / "registry-test"
        try:
            artifact_path.write_text(
                json.dumps(
                    {
                        "modelName": "python-logistic-fraud-model",
                        "modelVersion": "registry-v1",
                        "modelType": "logistic",
                        "modelFamily": "LOGISTIC_REGRESSION",
                        "bias": -2.0,
                        "weights": {},
                        "thresholds": {"medium": 0.45, "high": 0.75, "critical": 0.9},
                    }
                ),
                encoding="utf-8",
            )
            registry = ModelRegistry(registry_path)
            first = registry.register(
                artifact_path=artifact_path,
                model_version="registry-v1",
                model_type="logistic",
                metrics={"prAuc": 0.5},
                training_metadata={"examples": 10},
                role="champion",
            )
            second = registry.register(
                artifact_path=artifact_path,
                model_version="registry-v2",
                model_type="logistic",
                metrics={"prAuc": 0.6},
                training_metadata={"examples": 20},
                role="challenger",
            )

            self.assertEqual(registry.by_version("registry-v1"), first)
            self.assertEqual(registry.champion().model_version, "registry-v1")
            self.assertEqual(registry.challenger().model_version, "registry-v2")
            self.assertEqual(registry.latest().model_version, second.model_version)

            promoted = registry.promote("registry-v2")
            self.assertEqual(promoted.role, "champion")
            self.assertEqual(registry.champion().model_version, "registry-v2")
            self.assertEqual(registry.by_version("registry-v1").role, "archived")
        finally:
            if artifact_path.exists():
                artifact_path.unlink()
            if registry_path.exists():
                for child in sorted(registry_path.rglob("*"), reverse=True):
                    if child.is_file():
                        child.unlink()
                    elif child.is_dir():
                        child.rmdir()
                registry_path.rmdir()

    def test_runtime_loads_champion_model_from_registry(self):
        artifact_path = Path.cwd() / "registry-runtime-artifact.json"
        registry_path = Path.cwd() / "registry-runtime"
        try:
            artifact_path.write_text(
                json.dumps(
                    self._artifact_payload("registry-runtime-v1")
                ),
                encoding="utf-8",
            )
            registry = ModelRegistry(registry_path)
            registry.register(artifact_path, "registry-runtime-v1", "logistic", role="champion")

            model = FraudModel(
                artifact_path=Path.cwd() / "missing-artifact.json",
                model_version="registry-runtime-v1",
                registry=registry,
            )

            self.assertEqual(model.model_version, "registry-runtime-v1")
        finally:
            if artifact_path.exists():
                artifact_path.unlink()
            if registry_path.exists():
                for child in sorted(registry_path.rglob("*"), reverse=True):
                    if child.is_file():
                        child.unlink()
                    elif child.is_dir():
                        child.rmdir()
                registry_path.rmdir()

    def test_runtime_compares_champion_and_challenger_models(self):
        champion_artifact = Path.cwd() / "registry-compare-champion.json"
        challenger_artifact = Path.cwd() / "registry-compare-challenger.json"
        registry_path = Path.cwd() / "registry-compare"
        feature_schema = list(FeaturePipeline.PRODUCTION_FEATURE_NAMES)
        try:
            champion_artifact.write_text(
                json.dumps(
                    self._artifact_payload("champion-v1", feature_schema=feature_schema)
                ),
                encoding="utf-8",
            )
            challenger_artifact.write_text(
                json.dumps(
                    self._artifact_payload(
                        "challenger-v2",
                        feature_schema=feature_schema,
                        weights={name: 0.1 for name in feature_schema},
                    )
                ),
                encoding="utf-8",
            )
            registry = ModelRegistry(registry_path)
            registry.register(champion_artifact, "champion-v1", "logistic", role="champion")
            registry.register(challenger_artifact, "challenger-v2", "logistic", role="challenger")
            model = FraudModel(artifact_path=Path.cwd() / "missing-artifact.json", registry=registry)

            comparison = model.compare_with(
                {
                    "recentTransactionCount": 5,
                    "recentAmountSumPln": 5000.0,
                    "currentTransactionAmountPln": 5000.0,
                    "currency": "PLN",
                    "recentTransactionCountWindow": "PT1M",
                    "recentAmountSumWindow": "PT1M",
                    "transactionVelocityPerMinute": 5.0,
                    "merchantFrequency7d": 4,
                    "deviceNovelty": True,
                    "countryMismatch": False,
                    "proxyOrVpnDetected": True,
                },
                artifact_path=Path.cwd() / "missing-artifact.json",
                registry=registry,
            )
            invalid_comparison = model.compare_with(
                {
                    "recentTransactionCount": "5",
                    "recentAmountSumPln": 5000.0,
                    "currentTransactionAmountPln": 5000.0,
                    "currency": "PLN",
                    "recentTransactionCountWindow": "PT1M",
                    "recentAmountSumWindow": "PT1M",
                    "transactionVelocityPerMinute": 5.0,
                    "merchantFrequency7d": 4,
                    "deviceNovelty": True,
                    "countryMismatch": False,
                    "proxyOrVpnDetected": True,
                },
                artifact_path=Path.cwd() / "missing-artifact.json",
                registry=registry,
            )
        finally:
            for artifact_path in (champion_artifact, challenger_artifact):
                if artifact_path.exists():
                    artifact_path.unlink()
            if registry_path.exists():
                for child in sorted(registry_path.rglob("*"), reverse=True):
                    if child.is_file():
                        child.unlink()
                    elif child.is_dir():
                        child.rmdir()
                registry_path.rmdir()

        self.assertEqual(comparison["mode"], "ML_COMPARE")
        self.assertEqual(comparison["modelA"]["modelVersion"], "champion-v1")
        self.assertEqual(comparison["modelB"]["modelVersion"], "challenger-v2")
        self.assertIn("thresholdDifferences", comparison)
        self.assertIn("comparisonMetricsByVersion", comparison)
        self.assertIsNone(invalid_comparison["scoreDelta"])
        self.assertIsNone(invalid_comparison["absoluteScoreDelta"])
        self.assertIsNone(invalid_comparison["riskLevelMismatch"])
        self.assertIsNone(invalid_comparison["decisionDisagreement"])
        self.assertFalse(invalid_comparison["modelA"]["available"])
        self.assertFalse(invalid_comparison["modelB"]["available"])
        self.assertIsNone(invalid_comparison["modelA"]["fraudScore"])
        self.assertIsNone(invalid_comparison["modelB"]["fraudScore"])
        self.assertEqual(invalid_comparison["modelA"]["fallbackReason"], "INCOMPATIBLE_FEATURE_SNAPSHOT")
        self.assertEqual(invalid_comparison["modelB"]["fallbackReason"], "INCOMPATIBLE_FEATURE_SNAPSHOT")

    def test_model_loader_uses_logistic_artifact_type(self):
        artifact_path = Path.cwd() / "loader-logistic-artifact.json"
        try:
            artifact_path.write_text(
                json.dumps(
                    self._artifact_payload("loader-logistic-v1")
                ),
                encoding="utf-8",
            )
            model = load_model_from_artifact(artifact_path)
        finally:
            if artifact_path.exists():
                artifact_path.unlink()

        self.assertEqual(model.model_version, "loader-logistic-v1")

    def test_committed_canonical_model_artifact_loads_successfully(self):
        artifact_path = Path(__file__).resolve().parents[1] / "app" / "model_artifact.json"

        model = load_model_from_artifact(artifact_path)

        self.assertEqual(model.model_version, CANONICAL_MODEL_VERSION)
        self.assertEqual(model.runtime_feature_names(), list(FeaturePipeline.PRODUCTION_FEATURE_NAMES))

    def test_model_loader_rejects_unknown_artifact_type(self):
        artifact_path = Path.cwd() / "loader-unknown-artifact.json"
        try:
            artifact_path.write_text(json.dumps({"modelType": "svm"}), encoding="utf-8")
            with self.assertRaisesRegex(ModelConfigurationError, "Unsupported modelType"):
                load_model_from_artifact(artifact_path)
        finally:
            if artifact_path.exists():
                artifact_path.unlink()

    def test_model_loader_does_not_fallback_from_xgboost_to_logistic(self):
        artifact_path = Path.cwd() / "loader-xgboost-artifact.json"
        try:
            artifact_path.write_text(json.dumps(self._artifact_payload("xgboost-v1", model_type="xgboost")), encoding="utf-8")
            with self.assertRaisesRegex(RuntimeError, "xgboost"):
                load_model_from_artifact(artifact_path)
        finally:
            if artifact_path.exists():
                artifact_path.unlink()

    def test_artifact_feature_contract_version_must_match_runtime_contract(self):
        artifact_path = Path.cwd() / "loader-version-mismatch-artifact.json"
        artifact = self._artifact_payload("loader-version-mismatch-v1")
        artifact["featureContractVersion"] = "old-contract"
        try:
            artifact_path.write_text(json.dumps(artifact), encoding="utf-8")
            with self.assertRaisesRegex(ModelConfigurationError, "featureContractVersion mismatch"):
                load_model_from_artifact(artifact_path)
        finally:
            if artifact_path.exists():
                artifact_path.unlink()

    def test_artifact_feature_schema_must_match_production_schema(self):
        artifact_path = Path.cwd() / "loader-schema-mismatch-artifact.json"
        artifact = self._artifact_payload("loader-schema-mismatch-v1")
        artifact["featureSchema"] = list(reversed(FeaturePipeline.PRODUCTION_FEATURE_NAMES))
        try:
            artifact_path.write_text(json.dumps(artifact), encoding="utf-8")
            with self.assertRaisesRegex(ModelConfigurationError, "featureSchema mismatch"):
                load_model_from_artifact(artifact_path)
        finally:
            if artifact_path.exists():
                artifact_path.unlink()

    def test_missing_model_runtime_readiness_is_rejected(self):
        artifact_path = Path.cwd() / "loader-missing-readiness-artifact.json"
        artifact = self._artifact_payload("loader-missing-readiness-v1")
        artifact.pop("modelRuntimeReadiness")
        try:
            artifact_path.write_text(json.dumps(artifact), encoding="utf-8")
            with self.assertRaisesRegex(ModelConfigurationError, "missing required fields"):
                load_model_from_artifact(artifact_path)
        finally:
            if artifact_path.exists():
                artifact_path.unlink()

    def test_old_production_readiness_field_alone_is_rejected(self):
        artifact_path = Path.cwd() / "loader-old-readiness-artifact.json"
        artifact = self._artifact_payload("loader-old-readiness-v1")
        readiness = artifact.pop("modelRuntimeReadiness")
        artifact["productionReadiness"] = readiness
        try:
            artifact_path.write_text(json.dumps(artifact), encoding="utf-8")
            with self.assertRaisesRegex(ModelConfigurationError, "missing required fields"):
                load_model_from_artifact(artifact_path)
        finally:
            if artifact_path.exists():
                artifact_path.unlink()

    def test_malformed_model_runtime_readiness_is_rejected(self):
        cases = {
            "null": None,
            "string": "READY",
            "unknown_status": {**self._ready_model_runtime_readiness(), "status": "UNKNOWN"},
            "not_ready_status": {
                **self._ready_model_runtime_readiness(),
                "status": "NOT_READY",
                "reasons": ["UNIT_TEST_NOT_READY"],
            },
            "malformed_reasons": {**self._ready_model_runtime_readiness(), "reasons": "none"},
        }
        for case_name, readiness in cases.items():
            with self.subTest(case_name=case_name):
                artifact_path = Path.cwd() / f"loader-{case_name}-readiness-artifact.json"
                artifact = self._artifact_payload(f"loader-{case_name}-readiness-v1")
                artifact["modelRuntimeReadiness"] = readiness
                try:
                    artifact_path.write_text(json.dumps(artifact), encoding="utf-8")
                    with self.assertRaisesRegex(ModelConfigurationError, "modelRuntimeReadiness|model runtime readiness failed"):
                        load_model_from_artifact(artifact_path)
                finally:
                    if artifact_path.exists():
                        artifact_path.unlink()

    def test_xgboost_artifact_without_readiness_is_rejected_before_model_load(self):
        artifact_path = Path.cwd() / "loader-xgboost-missing-readiness-artifact.json"
        artifact = self._artifact_payload("xgboost-missing-readiness-v1", model_type="xgboost")
        artifact.pop("modelRuntimeReadiness")
        try:
            artifact_path.write_text(json.dumps(artifact), encoding="utf-8")
            with self.assertRaisesRegex(ModelConfigurationError, "missing required fields"):
                load_model_from_artifact(artifact_path)
        finally:
            if artifact_path.exists():
                artifact_path.unlink()

    def test_registry_artifact_cannot_bypass_contract_validation(self):
        artifact_path = Path.cwd() / "registry-invalid-artifact.json"
        registry_path = Path.cwd() / "registry-invalid"
        artifact = self._artifact_payload("registry-invalid-v1")
        artifact["featureSetVersion"] = "old-contract"
        try:
            artifact_path.write_text(json.dumps(artifact), encoding="utf-8")
            registry = ModelRegistry(registry_path)
            registry.register(artifact_path, "registry-invalid-v1", "logistic", role="champion")

            with self.assertRaisesRegex(ModelConfigurationError, "featureSetVersion mismatch"):
                FraudModel(artifact_path=Path.cwd() / "missing-artifact.json", registry=registry)
        finally:
            if artifact_path.exists():
                artifact_path.unlink()
            if registry_path.exists():
                for child in sorted(registry_path.rglob("*"), reverse=True):
                    if child.is_file():
                        child.unlink()
                    elif child.is_dir():
                        child.rmdir()
                registry_path.rmdir()

    def test_registry_selected_not_ready_artifact_is_rejected(self):
        artifact_path = Path.cwd() / "registry-not-ready-artifact.json"
        registry_path = Path.cwd() / "registry-not-ready"
        artifact = self._artifact_payload("registry-not-ready-v1")
        artifact["modelRuntimeReadiness"] = {
            **self._ready_model_runtime_readiness(),
            "status": "NOT_READY",
            "reasons": ["UNIT_TEST_NOT_READY"],
        }
        try:
            artifact_path.write_text(json.dumps(artifact), encoding="utf-8")
            registry = ModelRegistry(registry_path)
            registry.register(artifact_path, "registry-not-ready-v1", "logistic", role="champion")

            with self.assertRaisesRegex(ModelConfigurationError, "model runtime readiness failed"):
                FraudModel(artifact_path=Path.cwd() / "missing-artifact.json", registry=registry)
        finally:
            if artifact_path.exists():
                artifact_path.unlink()
            if registry_path.exists():
                for child in sorted(registry_path.rglob("*"), reverse=True):
                    if child.is_file():
                        child.unlink()
                    elif child.is_dir():
                        child.rmdir()
                registry_path.rmdir()

    def test_optional_xgboost_model_fails_clearly_when_dependency_is_missing(self):
        if importlib.util.find_spec("xgboost") is None:
            with self.assertRaisesRegex(RuntimeError, "xgboost"):
                XGBoostFraudModel()

    def test_logistic_save_writes_strict_readiness_envelope(self):
        schema = list(FeaturePipeline.PRODUCTION_FEATURE_NAMES)
        artifact = self._artifact_payload(feature_schema=schema, weights={name: 0.01 for name in schema})
        model = LogisticFraudModel(artifact)
        artifact_path = Path.cwd() / "logistic-save-artifact.json"
        try:
            model.save(
                artifact_path,
                metadata={
                    "examples": 100,
                    "evaluation": {"modelRuntimeReadiness": self._ready_model_runtime_readiness()},
                },
            )
            saved = json.loads(artifact_path.read_text(encoding="utf-8"))
            loaded = load_model_from_artifact(artifact_path)
        finally:
            if artifact_path.exists():
                artifact_path.unlink()

        self.assertEqual(saved["thresholdPolicy"]["deployedAlertThresholdName"], "high")
        self.assertEqual(saved["modelRuntimeReadiness"]["status"], "READY")
        self.assertEqual(saved["training"]["examples"], 100)
        self.assertIsInstance(loaded, LogisticFraudModel)

    def test_logistic_save_without_readiness_is_not_loadable(self):
        schema = list(FeaturePipeline.PRODUCTION_FEATURE_NAMES)
        artifact = self._artifact_payload(feature_schema=schema, weights={name: 0.01 for name in schema})
        model = LogisticFraudModel(artifact)
        artifact_path = Path.cwd() / "logistic-save-not-ready-artifact.json"
        try:
            model.save(artifact_path, metadata={"examples": 100})
            saved = json.loads(artifact_path.read_text(encoding="utf-8"))
            with self.assertRaisesRegex(ModelConfigurationError, "model runtime readiness failed"):
                load_model_from_artifact(artifact_path)
        finally:
            if artifact_path.exists():
                artifact_path.unlink()

        self.assertEqual(saved["modelRuntimeReadiness"]["status"], "UNKNOWN")
        self.assertEqual(saved["modelRuntimeReadiness"]["reasons"], ["MODEL_RUNTIME_READINESS_NOT_EVALUATED"])

    @unittest.skipUnless(importlib.util.find_spec("xgboost") is not None, "optional xgboost package is not installed")
    def test_xgboost_training_inference_and_artifact_loading_when_dependency_exists(self):
        dataset = generate_fraud_behavior(count=300, seed=987, user_count=6, fraud_ratio=0.03)
        model = train_model(dataset, "xgboost", epochs=2, learning_rate=0.1)
        sample = FeaturePipeline().fit(dataset).transform(dataset, mode="production")[0]
        score = model.predict_proba(sample)
        artifact_path = Path.cwd() / "xgboost-artifact.json"
        try:
            model.save(
                artifact_path,
                metadata={
                    "examples": dataset.size,
                    "evaluation": {"modelRuntimeReadiness": self._ready_model_runtime_readiness()},
                },
            )
            saved = json.loads(artifact_path.read_text(encoding="utf-8"))
            loaded = load_model_from_artifact(artifact_path)
            loaded_score = loaded.predict_proba(sample)
        finally:
            if artifact_path.exists():
                artifact_path.unlink()

        self.assertEqual(saved["modelRuntimeReadiness"]["status"], "READY")
        self.assertGreaterEqual(score, 0.0)
        self.assertLessEqual(score, 1.0)
        self.assertGreaterEqual(loaded_score, 0.0)
        self.assertLessEqual(loaded_score, 1.0)

    def _evaluation_for_decision(self, pr_auc: float, fpr: float, alert_rate: float, cost: float) -> dict[str, object]:
        return {
            "prAuc": pr_auc,
            "optimalThreshold": {
                "falsePositiveRate": fpr,
                "alertRate": alert_rate,
                "fraudCaptureRate": 0.8,
            },
            "costEvaluation": {"optimalCostThreshold": {"totalCost": cost}},
            "budgetEvaluation": {
                "budgets": [
                    {
                        "alertBudget": 0.01,
                        "expectedCost": cost,
                        "fraudCaptureRate": 0.8,
                    }
                ]
            },
            "stabilityAssessment": {"prAucDelta": 0.02, "expectedCostDelta": 10.0},
            "splitMetadata": {"testRows": 10},
        }


if __name__ == "__main__":
    unittest.main()
