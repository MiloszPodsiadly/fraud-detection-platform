import json
import threading
import unittest
from http.client import HTTPConnection
from http.server import ThreadingHTTPServer
from pathlib import Path

from app import server
from app.governance.drift import CONFIDENCE_ORDER, MIN_OBSERVATIONS, evaluate_drift
from app.governance.profile import InferenceProfile, load_reference_profile


SENSITIVE_FIELDS = (
    "transactionId",
    "customerId",
    "accountId",
    "cardId",
    "userId",
    "correlationId",
    "txn-sensitive",
)


class MlGovernanceUnitTest(unittest.TestCase):
    def test_reference_profile_loads_successfully(self):
        profile = load_reference_profile()

        self.assertTrue(profile["available"])
        self.assertEqual(profile["status"], "loaded")
        self.assertEqual(profile["profileType"], "synthetic_local_reference")
        self.assertEqual(profile["source"], "synthetic")
        self.assertEqual(profile["reference_quality"], "SYNTHETIC")
        self.assertEqual(profile["sample_size"], 1000)
        self.assertIn("data_window", profile)
        self.assertIn("generated_by", profile)
        self.assertIn("numeric_feature_stats", profile)
        self.assertIn("score_distribution", profile)

    def test_missing_reference_profile_returns_unknown_safe_status(self):
        profile = load_reference_profile(Path("missing-reference-profile.json"))
        drift = evaluate_drift(profile, {"observation_count": MIN_OBSERVATIONS})

        self.assertFalse(profile["available"])
        self.assertEqual(profile["status"], "missing")
        self.assertEqual(drift["status"], "UNKNOWN")
        self.assertEqual(drift["reason"], "reference_profile_unavailable")
        self.assertEqual(drift["confidence"], "LOW")

    def test_drift_insufficient_data_returns_unknown(self):
        profile = load_reference_profile()
        inference = InferenceProfile(
            profile["model_name"],
            profile["model_version"],
            list(profile["numeric_feature_stats"]),
        )
        for _ in range(MIN_OBSERVATIONS - 1):
            inference.update({name: 0.0 for name in profile["numeric_feature_stats"]}, 0.01, "LOW")

        drift = evaluate_drift(profile, inference.snapshot())

        self.assertEqual(drift["status"], "UNKNOWN")
        self.assertEqual(drift["reason"], "insufficient_data")
        self.assertEqual(drift["confidence"], "LOW")
        self.assertEqual(drift["sample_size"], MIN_OBSERVATIONS - 1)

    def test_drift_status_changes_under_shifted_synthetic_input_with_enough_data(self):
        profile = load_reference_profile()
        inference = InferenceProfile(
            profile["model_name"],
            profile["model_version"],
            list(profile["numeric_feature_stats"]),
        )
        shifted = {name: 1.0 for name in profile["numeric_feature_stats"]}

        for _ in range(MIN_OBSERVATIONS):
            inference.update(shifted, 0.95, "CRITICAL")

        drift = evaluate_drift(profile, inference.snapshot())

        self.assertIn(drift["status"], {"WATCH", "DRIFT"})
        self.assertEqual(drift["confidence"], "LOW")
        self.assertEqual(drift["reason"], "non_production_reference_profile")
        self.assertTrue(any(signal["severity"] == "DRIFT" for signal in drift["signals"]))

    def test_confidence_increases_with_sample_size_for_production_reference(self):
        profile = dict(load_reference_profile())
        profile["reference_quality"] = "PRODUCTION"
        profile["source"] = "evaluation"
        confidences = []
        for sample_size in (MIN_OBSERVATIONS, MIN_OBSERVATIONS * 2, MIN_OBSERVATIONS * 5):
            inference = InferenceProfile(
                profile["model_name"],
                profile["model_version"],
                list(profile["numeric_feature_stats"]),
            )
            for index in range(sample_size):
                value = 0.2 if index % 2 == 0 else 0.8
                inference.update({name: value for name in profile["numeric_feature_stats"]}, value, "LOW")
            confidences.append(evaluate_drift(profile, inference.snapshot())["confidence"])

        self.assertEqual(confidences, ["LOW", "MEDIUM", "HIGH"])
        self.assertLess(CONFIDENCE_ORDER[confidences[0]], CONFIDENCE_ORDER[confidences[1]])
        self.assertLess(CONFIDENCE_ORDER[confidences[1]], CONFIDENCE_ORDER[confidences[2]])


class MlGovernanceEndpointTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        server.INFERENCE_PROFILE.reset()
        cls.httpd = ThreadingHTTPServer(("127.0.0.1", 0), server.FraudInferenceHandler)
        cls.port = cls.httpd.server_address[1]
        cls.thread = threading.Thread(target=cls.httpd.serve_forever, daemon=True)
        cls.thread.start()

    @classmethod
    def tearDownClass(cls):
        cls.httpd.shutdown()
        cls.httpd.server_close()
        cls.thread.join(timeout=5)

    def request(self, method: str, path: str, body: bytes | None = None, headers: dict[str, str] | None = None):
        connection = HTTPConnection("127.0.0.1", self.port, timeout=5)
        connection.request(method, path, body=body, headers=headers or {})
        response = connection.getresponse()
        payload = response.read()
        status = response.status
        response_headers = dict(response.getheaders())
        connection.close()
        return status, response_headers, payload

    def get_json(self, path: str):
        status, headers, payload = self.request("GET", path)
        self.assertEqual(status, 200)
        self.assertEqual(headers["Content-Type"], "application/json")
        return json.loads(payload.decode("utf-8"))

    def score(self, payload: bytes):
        status, _, body = self.request(
            "POST",
            "/v1/fraud/score",
            body=payload,
            headers={"Content-Type": "application/json"},
        )
        self.assertEqual(status, 200)
        return json.loads(body.decode("utf-8"))

    def test_governance_model_endpoint_exposes_lineage(self):
        payload = self.get_json("/governance/model")

        self.assertEqual(set(payload), {"model", "reference_profile", "inference_profile", "drift"})
        self.assertEqual(payload["model"]["model_name"], server.MODEL_NAME)
        self.assertEqual(payload["model"]["model_version"], server.MODEL_VERSION)
        self.assertEqual(
            payload["model"]["governance"]["scope"],
            "additive runtime oversight; scoring semantics unchanged",
        )
        self.assertIn("feature_set", payload["model"])

    def test_reference_profile_endpoint_marks_synthetic_quality(self):
        payload = self.get_json("/governance/profile/reference")
        reference = payload["reference_profile"]

        self.assertEqual(reference["source"], "synthetic")
        self.assertEqual(reference["reference_quality"], "SYNTHETIC")
        self.assertEqual(reference["sample_size"], 1000)
        self.assertIn("data_window", reference)
        self.assertIn("generated_by", reference)

    def test_inference_profile_updates_after_successful_scoring(self):
        before = self.get_json("/governance/profile/inference")["inference_profile"]["observation_count"]
        self.score(
            b'{"features":{"recentTransactionCount":1,"recentAmountSum":{"amount":45.0,"currency":"USD"},'
            b'"transactionVelocityPerMinute":0.05,"merchantFrequency7d":1,"deviceNovelty":false,'
            b'"countryMismatch":false,"proxyOrVpnDetected":false,"featureFlags":[]}}'
        )
        after = self.get_json("/governance/profile/inference")["inference_profile"]

        self.assertEqual(after["observation_count"], before + 1)
        self.assertIn("profile_started_at", after)
        self.assertIn("last_updated_at", after)
        self.assertIn("profile_uptime_seconds", after)
        serialized = json.dumps(after)
        for field in SENSITIVE_FIELDS:
            self.assertNotIn(field, serialized)

    def test_governance_endpoints_return_bounded_aggregate_json(self):
        for path in (
            "/governance/profile/reference",
            "/governance/profile/inference",
            "/governance/drift",
        ):
            payload = self.get_json(path)
            serialized = json.dumps(payload)
            self.assertLess(len(serialized), 50000)
            for field in SENSITIVE_FIELDS:
                self.assertNotIn(field, serialized)

    def test_drift_unknown_until_minimum_sample_size(self):
        server.INFERENCE_PROFILE.reset()

        drift = self.get_json("/governance/drift")

        self.assertEqual(drift["drift"]["status"], "UNKNOWN")
        self.assertEqual(drift["drift"]["confidence"], "LOW")
        self.assertEqual(drift["drift"]["reason"], "insufficient_data")
        self.assertEqual(drift["drift"]["inference_profile_status"], "RESET_RECENTLY")

    def test_existing_scoring_response_contract_remains_compatible(self):
        response = self.score(
            b'{"features":{"recentTransactionCount":8,"recentAmountSum":{"amount":7200.0,"currency":"USD"},'
            b'"transactionVelocityPerMinute":0.7,"merchantFrequency7d":9,"deviceNovelty":true,'
            b'"countryMismatch":true,"proxyOrVpnDetected":true,'
            b'"featureFlags":["DEVICE_NOVELTY","COUNTRY_MISMATCH","PROXY_OR_VPN","HIGH_VELOCITY"]}}'
        )

        self.assertEqual(
            set(response),
            {
                "available",
                "fraudScore",
                "riskLevel",
                "modelName",
                "modelVersion",
                "inferenceTimestamp",
                "reasonCodes",
                "scoreDetails",
                "explanationMetadata",
                "fallbackReason",
            },
        )

    def test_governance_metrics_remain_low_cardinality(self):
        self.get_json("/governance/drift")
        status, headers, payload = self.request("GET", "/metrics")
        self.assertEqual(status, 200)
        self.assertIn("text/plain", headers["Content-Type"])
        metrics = payload.decode("utf-8")

        self.assertIn("fraud_ml_governance_drift_status", metrics)
        self.assertIn("fraud_ml_governance_drift_confidence", metrics)
        self.assertIn("fraud_ml_governance_reference_profile_loaded", metrics)
        self.assertIn("fraud_ml_governance_profile_observations_total", metrics)
        self.assertNotIn("sample_size=", metrics)
        self.assertNotIn("feature_name=", metrics)
        for field in SENSITIVE_FIELDS:
            self.assertNotIn(field, metrics)


if __name__ == "__main__":
    unittest.main()
