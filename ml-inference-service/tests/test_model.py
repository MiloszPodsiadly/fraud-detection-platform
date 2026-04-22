import unittest
import json
from pathlib import Path

from app.data.dataset import Dataset
from app.data.generator import generate_fraud_behavior, generate_normal_behavior
from app.evaluation.evaluate import cli_summary, evaluate_scores
from app.feedback.feedback_dataset import FeedbackDatasetStore, dataset_from_feedback, feedback_from_decision_event
from app.features.feature_contract import FEATURE_CONTRACT
from app.features.feature_pipeline import FeaturePipeline
from app.model import FraudModel
from app.registry.model_registry import ModelRegistry
from app.models.xgboost_model import XGBoostFraudModel
from app.training.retraining import compare_retrained_model
from app.training.train import train, train_with_evaluation, write_artifact


class FraudModelTest(unittest.TestCase):
    def test_scores_high_risk_signal_as_high_or_critical(self):
        result = FraudModel().score(
            {
                "recentTransactionCount": 8,
                "recentAmountSum": {"amount": 7200.0, "currency": "USD"},
                "transactionVelocityPerMinute": 0.7,
                "merchantFrequency7d": 9,
                "deviceNovelty": True,
                "countryMismatch": True,
                "proxyOrVpnDetected": True,
                "featureFlags": ["DEVICE_NOVELTY", "COUNTRY_MISMATCH", "PROXY_OR_VPN", "HIGH_VELOCITY"],
            }
        )

        self.assertTrue(result["available"])
        self.assertIn(result["riskLevel"], {"HIGH", "CRITICAL"})
        self.assertGreaterEqual(result["fraudScore"], 0.75)
        self.assertIn("proxyOrVpnDetected", result["reasonCodes"])

    def test_scores_baseline_signal_as_low(self):
        result = FraudModel().score(
            {
                "recentTransactionCount": 1,
                "recentAmountSum": {"amount": 45.0, "currency": "USD"},
                "transactionVelocityPerMinute": 0.05,
                "merchantFrequency7d": 1,
                "deviceNovelty": False,
                "countryMismatch": False,
                "proxyOrVpnDetected": False,
                "featureFlags": [],
            }
        )

        self.assertEqual(result["riskLevel"], "LOW")
        self.assertLess(result["fraudScore"], 0.45)

    def test_scores_rapid_transfer_burst_as_high_or_critical(self):
        result = FraudModel().score(
            {
                "recentTransactionCount": 2,
                "recentAmountSum": {"amount": 20000.0, "currency": "PLN"},
                "recentAmountSumPln": 20000.0,
                "rapidTransferTotalPln": 20000.0,
                "rapidTransferFraudCaseCandidate": True,
                "transactionVelocityPerMinute": 2.0,
                "merchantFrequency7d": 1,
                "deviceNovelty": False,
                "countryMismatch": False,
                "proxyOrVpnDetected": False,
                "featureFlags": ["RAPID_PLN_20K_BURST"],
            }
        )

        self.assertIn(result["riskLevel"], {"HIGH", "CRITICAL"})
        self.assertIn("rapidTransferBurst", result["reasonCodes"])

    def test_keeps_rapid_transfer_seed_without_aggregate_signal_low(self):
        result = FraudModel().score(
            {
                "recentTransactionCount": 2,
                "recentAmountSum": {"amount": 10000.0, "currency": "PLN"},
                "recentAmountSumPln": 10000.0,
                "rapidTransferTotalPln": 10000.0,
                "rapidTransferFraudCaseCandidate": False,
                "transactionVelocityPerMinute": 2.0,
                "merchantFrequency7d": 1,
                "deviceNovelty": False,
                "countryMismatch": False,
                "proxyOrVpnDetected": False,
                "featureFlags": [],
            }
        )

        self.assertEqual(result["riskLevel"], "LOW")
        self.assertNotIn("rapidTransferBurst", result["reasonCodes"])

    def test_feature_pipeline_normalizes_single_event(self):
        normalized = FeaturePipeline().transform_single(
            {
                "recentTransactionCount": 20,
                "recentAmountSum": {"amount": 15000.0, "currency": "PLN"},
                "transactionVelocityPerMinute": 10,
                "merchantFrequency7d": 24,
                "deviceNovelty": True,
                "countryMismatch": False,
                "proxyOrVpnDetected": True,
                "featureFlags": ["A", "B", "C"],
                "rapidTransferTotalPln": 20_000.0,
            }
        )

        self.assertEqual(normalized["recentTransactionCount"], 1.0)
        self.assertEqual(normalized["recentAmountSum"], 1.0)
        self.assertEqual(normalized["transactionVelocityPerMinute"], 1.0)
        self.assertEqual(normalized["merchantFrequency7d"], 1.0)
        self.assertEqual(normalized["deviceNovelty"], 1.0)
        self.assertEqual(normalized["countryMismatch"], 0.0)
        self.assertEqual(normalized["proxyOrVpnDetected"], 1.0)
        self.assertEqual(normalized["highRiskFlagCount"], 0.5)
        self.assertEqual(normalized["rapidTransferBurst"], 1.0)

    def test_python_feature_pipeline_uses_shared_contract_schema(self):
        self.assertEqual(FeaturePipeline.FEATURE_NAMES, FEATURE_CONTRACT.ml_feature_names)
        self.assertIn("recentTransactionCount", FEATURE_CONTRACT.java_enriched_feature_names)
        self.assertIn("RAPID_PLN_20K_BURST", FEATURE_CONTRACT.feature_flags)

    def test_java_enriched_snapshot_normalizes_to_contract_features(self):
        normalized = FeaturePipeline().transform_single(
            {
                "recentTransactionCount": 5,
                "recentAmountSum": {"amount": 5000.0, "currency": "PLN"},
                "transactionVelocityPerMinute": 5.0,
                "merchantFrequency7d": 6,
                "deviceNovelty": True,
                "countryMismatch": False,
                "proxyOrVpnDetected": True,
                "featureFlags": ["DEVICE_NOVELTY", "PROXY_OR_VPN", "HIGH_VELOCITY"],
                "rapidTransferFraudCaseCandidate": False,
                "rapidTransferTotalPln": 10000.0,
            }
        )

        self.assertEqual(set(normalized), set(FEATURE_CONTRACT.ml_feature_names))
        self.assertEqual(normalized["recentTransactionCount"], 0.5)
        self.assertEqual(normalized["recentAmountSum"], 0.5)
        self.assertEqual(normalized["transactionVelocityPerMinute"], 1.0)
        self.assertEqual(normalized["merchantFrequency7d"], 0.5)
        self.assertEqual(normalized["deviceNovelty"], 1.0)
        self.assertEqual(normalized["countryMismatch"], 0.0)
        self.assertEqual(normalized["proxyOrVpnDetected"], 1.0)
        self.assertEqual(normalized["highRiskFlagCount"], 0.5)
        self.assertEqual(normalized["rapidTransferBurst"], 0.0)

    def test_feature_pipeline_transforms_raw_sequence_features(self):
        dataset = generate_fraud_behavior(count=200, seed=789, user_count=10, fraud_ratio=0.02)
        transformed = FeaturePipeline().fit(dataset).transform(dataset)

        self.assertEqual(len(transformed), dataset.size)
        self.assertTrue(all("transactionVelocityPerHour" in row for row in transformed))
        self.assertTrue(all("recentAmountAverage" in row for row in transformed))
        self.assertTrue(all("amountDeviationFromUserMean" in row for row in transformed))
        self.assertTrue(all("merchantEntropy" in row for row in transformed))
        self.assertTrue(all(0.0 <= value <= 1.0 for row in transformed for value in row.values()))

        fraud_rows = [
            features for source, features in zip(dataset.X, transformed)
            if source["metadata"]["scenario"] == "account_takeover"
        ]
        self.assertTrue(any(row["deviceNovelty"] == 1.0 or row["countryMismatch"] == 1.0 for row in fraud_rows))

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
        dataset = generate_fraud_behavior(count=100, seed=321, user_count=8, fraud_ratio=0.02)
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
        self.assertEqual(set(artifact["featureSchema"]), set(weights))

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
                    "recentAmountSum": {"amount": 7000.0},
                    "transactionVelocityPerMinute": 5.0,
                    "merchantFrequency7d": 2,
                    "deviceNovelty": True,
                    "countryMismatch": False,
                    "proxyOrVpnDetected": True,
                    "featureFlags": ["DEVICE_NOVELTY", "PROXY_OR_VPN"],
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
        dataset = generate_fraud_behavior(count=120, seed=654, user_count=8, fraud_ratio=0.02)
        current_evaluation = {"prAuc": 0.0}

        comparison = compare_retrained_model(dataset, current_evaluation, epochs=2, learning_rate=0.1)

        self.assertGreaterEqual(comparison.challenger_pr_auc, 0.0)
        self.assertTrue(comparison.promote_challenger)
        self.assertIn("prAuc", comparison.challenger_evaluation)

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
                    {
                        "modelName": "python-logistic-fraud-model",
                        "modelVersion": "registry-runtime-v1",
                        "modelType": "logistic",
                        "modelFamily": "LOGISTIC_REGRESSION",
                        "bias": -2.0,
                        "weights": {
                            name: 0.0 for name in FeaturePipeline.FEATURE_NAMES
                        },
                        "thresholds": {"medium": 0.45, "high": 0.75, "critical": 0.9},
                    }
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

    def test_optional_xgboost_model_fails_clearly_when_dependency_is_missing(self):
        with self.assertRaisesRegex(RuntimeError, "xgboost"):
            XGBoostFraudModel()


if __name__ == "__main__":
    unittest.main()
