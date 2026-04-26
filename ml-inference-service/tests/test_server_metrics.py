import threading
import unittest
import os
from contextlib import redirect_stdout
from http.client import HTTPConnection
from http.server import ThreadingHTTPServer
from io import StringIO

os.environ.setdefault("INTERNAL_AUTH_MODE", "DISABLED_LOCAL_ONLY")

from app import server


def parse_metric_value(metrics_text: str, metric_name: str, labels: dict[str, str]) -> float:
    for line in metrics_text.splitlines():
        if not line.startswith(f"{metric_name}{{"):
            continue
        label_block, value = line.split("} ", 1)
        parsed = {}
        for entry in label_block[len(metric_name) + 1:].split(","):
            key, raw = entry.split("=", 1)
            parsed[key] = raw.strip('"')
        if parsed == labels:
            return float(value)
    return 0.0


class MlMetricsEndpointTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
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
        response_headers = dict(response.getheaders())
        status = response.status
        connection.close()
        return status, response_headers, payload

    def metrics_text(self) -> str:
        status, headers, payload = self.request("GET", "/metrics")
        self.assertEqual(status, 200)
        self.assertIn("text/plain", headers["Content-Type"])
        return payload.decode("utf-8")

    def test_metrics_endpoint_returns_prometheus_payload(self):
        metrics = self.metrics_text()
        self.assertIn("fraud_ml_inference_requests_total", metrics)
        self.assertIn("fraud_ml_model_info", metrics)

    def test_scoring_request_increments_request_counter_and_latency(self):
        before = self.metrics_text()
        payload = (
            b'{"features":{"recentTransactionCount":1,"recentAmountSum":{"amount":45.0,"currency":"USD"},'
            b'"transactionVelocityPerMinute":0.05,"merchantFrequency7d":1,"deviceNovelty":false,'
            b'"countryMismatch":false,"proxyOrVpnDetected":false,"featureFlags":[]}}'
        )
        status, _, _ = self.request("POST", "/v1/fraud/score", body=payload, headers={"Content-Type": "application/json"})
        self.assertEqual(status, 200)
        after = self.metrics_text()

        labels = {
            "endpoint": "/v1/fraud/score",
            "method": "POST",
            "status": "200",
            "outcome": "success",
        }
        self.assertGreater(
            parse_metric_value(after, "fraud_ml_inference_requests_total", labels),
            parse_metric_value(before, "fraud_ml_inference_requests_total", labels),
        )
        self.assertGreater(
            parse_metric_value(after, "fraud_ml_inference_request_latency_seconds_count", labels),
            parse_metric_value(before, "fraud_ml_inference_request_latency_seconds_count", labels),
        )

    def test_malformed_request_increments_rejected_metrics(self):
        before = self.metrics_text()
        status, _, _ = self.request(
            "POST",
            "/v1/fraud/score",
            body=b'{"features":',
            headers={"Content-Type": "application/json"},
        )
        self.assertEqual(status, 400)
        after = self.metrics_text()

        request_labels = {
            "endpoint": "/v1/fraud/score",
            "method": "POST",
            "status": "400",
            "outcome": "rejected",
        }
        error_labels = {
            "endpoint": "/v1/fraud/score",
            "method": "POST",
            "outcome": "rejected",
        }
        self.assertGreater(
            parse_metric_value(after, "fraud_ml_inference_requests_total", request_labels),
            parse_metric_value(before, "fraud_ml_inference_requests_total", request_labels),
        )
        self.assertGreater(
            parse_metric_value(after, "fraud_ml_inference_errors_total", error_labels),
            parse_metric_value(before, "fraud_ml_inference_errors_total", error_labels),
        )

    def test_disabled_local_only_mode_allows_local_anonymous_internal_call(self):
        previous_mode = os.environ.get("INTERNAL_AUTH_MODE")
        try:
            os.environ["INTERNAL_AUTH_MODE"] = "DISABLED_LOCAL_ONLY"
            status, _, payload = self.request("GET", "/governance/model")

            self.assertEqual(status, 200)
            self.assertIn(b"model", payload)
        finally:
            if previous_mode is None:
                os.environ.pop("INTERNAL_AUTH_MODE", None)
            else:
                os.environ["INTERNAL_AUTH_MODE"] = previous_mode

    def test_token_validator_rejects_anonymous_governance_request(self):
        previous_mode = os.environ.get("INTERNAL_AUTH_MODE")
        before = self.metrics_text()
        try:
            os.environ["INTERNAL_AUTH_MODE"] = "TOKEN_VALIDATOR"
            status, _, payload = self.request("GET", "/governance/model")

            self.assertEqual(status, 401)
            self.assertIn(b"Internal service authentication is required.", payload)
            after = self.metrics_text()
            self.assertGreater(
                parse_metric_value(after, "fraud_internal_auth_failure_total", {
                    "target_service": "ml-inference-service",
                    "reason": "missing_internal_credentials",
                }),
                parse_metric_value(before, "fraud_internal_auth_failure_total", {
                    "target_service": "ml-inference-service",
                    "reason": "missing_internal_credentials",
                }),
            )
        finally:
            if previous_mode is None:
                os.environ.pop("INTERNAL_AUTH_MODE", None)
            else:
                os.environ["INTERNAL_AUTH_MODE"] = previous_mode

    def test_token_validator_rejects_unknown_service(self):
        previous_mode = os.environ.get("INTERNAL_AUTH_MODE")
        previous_credentials = server.INTERNAL_SERVICE_CREDENTIALS
        try:
            os.environ["INTERNAL_AUTH_MODE"] = "TOKEN_VALIDATOR"
            server.INTERNAL_SERVICE_CREDENTIALS = {
                "known-service": server.InternalServiceCredential("secret", frozenset({"governance-read"}))
            }

            status, _, payload = self.request(
                "GET",
                "/governance/model",
                headers={
                    "X-Internal-Service-Name": "unknown-service",
                    "X-Internal-Service-Token": "bad",
                },
            )

            self.assertEqual(status, 403)
            self.assertIn(b"Internal service is not authorized", payload)
        finally:
            server.INTERNAL_SERVICE_CREDENTIALS = previous_credentials
            if previous_mode is None:
                os.environ.pop("INTERNAL_AUTH_MODE", None)
            else:
                os.environ["INTERNAL_AUTH_MODE"] = previous_mode

    def test_token_validator_rejects_invalid_token_without_logging_token(self):
        previous_mode = os.environ.get("INTERNAL_AUTH_MODE")
        previous_credentials = server.INTERNAL_SERVICE_CREDENTIALS
        try:
            os.environ["INTERNAL_AUTH_MODE"] = "TOKEN_VALIDATOR"
            server.INTERNAL_SERVICE_CREDENTIALS = {
                "known-service": server.InternalServiceCredential("secret", frozenset({"governance-read"}))
            }

            output = StringIO()
            with redirect_stdout(output):
                status, _, payload = self.request(
                    "GET",
                    "/governance/model",
                    headers={
                        "X-Internal-Service-Name": "known-service",
                        "X-Internal-Service-Token": "wrong-secret-token",
                    },
                )

            self.assertEqual(status, 403)
            self.assertIn(b"Internal service is not authorized", payload)
            self.assertNotIn("wrong-secret-token", output.getvalue())
            self.assertIn("internal_auth_rejected", output.getvalue())
        finally:
            server.INTERNAL_SERVICE_CREDENTIALS = previous_credentials
            if previous_mode is None:
                os.environ.pop("INTERNAL_AUTH_MODE", None)
            else:
                os.environ["INTERNAL_AUTH_MODE"] = previous_mode

    def test_token_validator_allows_known_service_and_records_success_metric(self):
        previous_mode = os.environ.get("INTERNAL_AUTH_MODE")
        previous_credentials = server.INTERNAL_SERVICE_CREDENTIALS
        before = self.metrics_text()
        try:
            os.environ["INTERNAL_AUTH_MODE"] = "TOKEN_VALIDATOR"
            server.INTERNAL_SERVICE_CREDENTIALS = {
                "known-service": server.InternalServiceCredential("secret", frozenset({"governance-read"}))
            }

            status, _, payload = self.request(
                "GET",
                "/governance/model",
                headers={
                    "X-Internal-Service-Name": "known-service",
                    "X-Internal-Service-Token": "secret",
                },
            )

            self.assertEqual(status, 200)
            self.assertIn(b"model", payload)
            after = self.metrics_text()
            self.assertGreater(
                parse_metric_value(after, "fraud_internal_auth_success_total", {
                    "source_service": "known-service",
                    "target_service": "ml-inference-service",
                }),
                parse_metric_value(before, "fraud_internal_auth_success_total", {
                    "source_service": "known-service",
                    "target_service": "ml-inference-service",
                }),
            )
        finally:
            server.INTERNAL_SERVICE_CREDENTIALS = previous_credentials
            if previous_mode is None:
                os.environ.pop("INTERNAL_AUTH_MODE", None)
            else:
                os.environ["INTERNAL_AUTH_MODE"] = previous_mode

    def test_internal_auth_startup_rejects_disabled_local_only_in_prod_profile(self):
        with self.assertRaises(RuntimeError):
            server._validate_internal_auth_startup(
                mode="DISABLED_LOCAL_ONLY",
                profile="production",
                credentials={},
            )

    def test_internal_auth_startup_requires_allowlist_in_prod_token_validator(self):
        with self.assertRaises(RuntimeError):
            server._validate_internal_auth_startup(
                mode="TOKEN_VALIDATOR",
                profile="prod",
                credentials={},
            )

    def test_scoring_behavior_is_unchanged_when_internal_auth_is_valid(self):
        previous_mode = os.environ.get("INTERNAL_AUTH_MODE")
        previous_credentials = server.INTERNAL_SERVICE_CREDENTIALS
        try:
            os.environ["INTERNAL_AUTH_MODE"] = "TOKEN_VALIDATOR"
            server.INTERNAL_SERVICE_CREDENTIALS = {
                "fraud-scoring-service": server.InternalServiceCredential("secret", frozenset({"ml-score"}))
            }

            status, _, payload = self.request(
                "POST",
                "/v1/fraud/score",
                body=b'{"features":{"amount":42,"recentTransactionCount":1}}',
                headers={
                    "Content-Type": "application/json",
                    "X-Internal-Service-Name": "fraud-scoring-service",
                    "X-Internal-Service-Token": "secret",
                },
            )

            self.assertEqual(status, 200)
            self.assertIn(b"fraudScore", payload)
            self.assertIn(b"riskLevel", payload)
        finally:
            server.INTERNAL_SERVICE_CREDENTIALS = previous_credentials
            if previous_mode is None:
                os.environ.pop("INTERNAL_AUTH_MODE", None)
            else:
                os.environ["INTERNAL_AUTH_MODE"] = previous_mode

    def test_model_version_info_and_load_status_exist(self):
        metrics = self.metrics_text()
        info_labels = {
            "model_name": server.MODEL_NAME,
            "model_version": server.MODEL_VERSION,
        }
        load_labels = {
            "outcome": "success",
            "model_name": server.MODEL_NAME,
            "model_version": server.MODEL_VERSION,
        }
        self.assertEqual(parse_metric_value(metrics, "fraud_ml_model_info", info_labels), 1.0)
        self.assertEqual(parse_metric_value(metrics, "fraud_ml_model_load_status", load_labels), 1.0)

    def test_model_lifecycle_metrics_exist_and_remain_low_cardinality(self):
        metrics = self.metrics_text()
        lifecycle_info_labels = {
            "lifecycle_mode": "READ_ONLY",
            "model_name": server.MODEL_NAME,
            "model_version": server.MODEL_VERSION,
        }

        self.assertEqual(parse_metric_value(metrics, "fraud_ml_model_lifecycle_info", lifecycle_info_labels), 1.0)
        self.assertIn("fraud_ml_model_lifecycle_events_total", metrics)
        self.assertIn("fraud_ml_model_lifecycle_history_available", metrics)
        self.assertNotIn("artifact_path=", metrics)
        self.assertNotIn("checksum=", metrics)
        self.assertNotIn("event_id=", metrics)
        self.assertNotIn("timestamp=", metrics)

    def test_advisory_metrics_exist_and_remain_low_cardinality(self):
        metrics = self.metrics_text()

        self.assertIn("fraud_ml_governance_advisory_events_emitted_total", metrics)
        self.assertIn("fraud_ml_governance_advisory_events_persisted_total", metrics)
        self.assertIn("fraud_ml_governance_advisory_persistence_failures_total", metrics)
        self.assertIn("severity=", metrics)
        self.assertIn("model_name=", metrics)
        self.assertIn("model_version=", metrics)
        self.assertIn("status=", metrics)
        self.assertNotIn("event_id=", metrics)
        self.assertNotIn("timestamp=", metrics)
        self.assertNotIn("payload=", metrics)

    def test_metrics_do_not_expose_high_cardinality_labels(self):
        metrics = self.metrics_text()
        self.assertNotIn("transactionId", metrics)
        self.assertNotIn("customerId", metrics)
        self.assertNotIn("userId", metrics)
        self.assertNotIn("correlationId", metrics)
        self.assertNotIn("alertId", metrics)
        self.assertNotIn("reasonCode", metrics)
