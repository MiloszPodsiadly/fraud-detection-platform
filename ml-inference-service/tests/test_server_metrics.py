import threading
import unittest
from http.client import HTTPConnection
from http.server import ThreadingHTTPServer

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

    def test_metrics_do_not_expose_high_cardinality_labels(self):
        metrics = self.metrics_text()
        self.assertNotIn("transactionId", metrics)
        self.assertNotIn("customerId", metrics)
        self.assertNotIn("userId", metrics)
        self.assertNotIn("correlationId", metrics)
        self.assertNotIn("alertId", metrics)
        self.assertNotIn("reasonCode", metrics)
