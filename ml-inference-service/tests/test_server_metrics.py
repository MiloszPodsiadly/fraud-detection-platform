import threading
import unittest
import os
import hashlib
import time
import json
from contextlib import redirect_stdout
from http.client import HTTPConnection
from http.server import ThreadingHTTPServer
from io import StringIO

import jwt

os.environ.setdefault("INTERNAL_AUTH_MODE", "DISABLED_LOCAL_ONLY")

from app import server

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
SERVICE_IDENTITY_DIR = os.path.join(REPO_ROOT, "deployment", "service-identity")
SCORING_PRIVATE_KEY = os.path.join(SERVICE_IDENTITY_DIR, "fraud-scoring-service-private.pem")
ALERT_PRIVATE_KEY = os.path.join(SERVICE_IDENTITY_DIR, "alert-service-private.pem")
JWKS_PATH = os.path.join(SERVICE_IDENTITY_DIR, "jwks.json")


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


def jwt_service_token(
        service_name: str = "fraud-scoring-service",
        authorities: list[str] | None = None,
        issuer: str = "fraud-platform-local",
        audience: str = "ml-inference-service",
        secret: str = "local-dev-jwt-service-secret-32bytes",
        expires_in_seconds: int = 300,
        include_authorities: bool = True,
        include_iat: bool = True,
        include_exp: bool = True,
        issued_at_offset_seconds: int = 0,
        algorithm: str = "HS256",
) -> str:
    now = int(time.time())
    payload = {
        "iss": issuer,
        "aud": audience,
        "service_name": service_name,
    }
    if include_iat:
        payload["iat"] = now + issued_at_offset_seconds
    if include_exp:
        payload["exp"] = now + expires_in_seconds
    if include_authorities:
        payload["authorities"] = authorities or ["ml-score"]
    key = "" if algorithm == "none" else secret
    return jwt.encode(payload, key=key, algorithm=algorithm)


def rs256_service_token(
        private_key_path: str = SCORING_PRIVATE_KEY,
        key_id: str = "scoring-key-1",
        service_name: str = "fraud-scoring-service",
        authorities: list[str] | None = None,
        issuer: str = "fraud-platform-local",
        audience: str = "ml-inference-service",
        expires_in_seconds: int = 300,
        include_authorities: bool = True,
        include_iat: bool = True,
        include_exp: bool = True,
        issued_at_offset_seconds: int = 0,
        algorithm: str = "RS256",
) -> str:
    now = int(time.time())
    payload = {
        "iss": issuer,
        "aud": audience,
        "service_name": service_name,
    }
    if include_iat:
        payload["iat"] = now + issued_at_offset_seconds
    if include_exp:
        payload["exp"] = now + expires_in_seconds
    if include_authorities:
        payload["authorities"] = authorities or ["ml-score"]
    with open(private_key_path, encoding="utf-8") as handle:
        key = handle.read()
    return jwt.encode(payload, key=key, algorithm=algorithm, headers={"kid": key_id})


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

    def mtls_certificate(self, san_uri: str) -> dict:
        return {
            "subjectAltName": (("URI", san_uri),),
            "notBefore": "Apr 27 12:00:00 2020 GMT",
            "notAfter": "Apr 27 12:00:00 2030 GMT",
        }

    def restore_env(self, saved: dict[str, str | None]) -> None:
        for key, value in saved.items():
            if value is None:
                os.environ.pop(key, None)
            else:
                os.environ[key] = value

    def test_metrics_endpoint_returns_prometheus_payload(self):
        metrics = self.metrics_text()
        self.assertIn("fraud_ml_inference_requests_total", metrics)
        self.assertIn("fraud_ml_model_info", metrics)

    def test_health_endpoint_includes_mtls_certificate_state(self):
        status, _, payload = self.request("GET", "/health")

        self.assertEqual(status, 200)
        response = json.loads(payload.decode("utf-8"))
        self.assertIn("mtlsCert", response)
        self.assertIn(response["mtlsCert"]["status"], {"UP", "WARN", "DOWN"})

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
                    "mode": "TOKEN_VALIDATOR",
                    "reason": "missing_internal_credentials",
                }),
                parse_metric_value(before, "fraud_internal_auth_failure_total", {
                    "target_service": "ml-inference-service",
                    "mode": "TOKEN_VALIDATOR",
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
                    "mode": "TOKEN_VALIDATOR",
                }),
                parse_metric_value(before, "fraud_internal_auth_success_total", {
                    "source_service": "known-service",
                    "target_service": "ml-inference-service",
                    "mode": "TOKEN_VALIDATOR",
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
        previous_hash_mode = os.environ.get("INTERNAL_AUTH_TOKEN_HASH_MODE")
        previous_allow = os.environ.get("INTERNAL_AUTH_ALLOW_TOKEN_VALIDATOR_IN_PROD")
        try:
            os.environ["INTERNAL_AUTH_TOKEN_HASH_MODE"] = "true"
            os.environ["INTERNAL_AUTH_ALLOW_TOKEN_VALIDATOR_IN_PROD"] = "true"
            with self.assertRaises(RuntimeError):
                server._validate_internal_auth_startup(
                    mode="TOKEN_VALIDATOR",
                    profile="prod",
                    credentials={},
                )
        finally:
            if previous_hash_mode is None:
                os.environ.pop("INTERNAL_AUTH_TOKEN_HASH_MODE", None)
            else:
                os.environ["INTERNAL_AUTH_TOKEN_HASH_MODE"] = previous_hash_mode
            if previous_allow is None:
                os.environ.pop("INTERNAL_AUTH_ALLOW_TOKEN_VALIDATOR_IN_PROD", None)
            else:
                os.environ["INTERNAL_AUTH_ALLOW_TOKEN_VALIDATOR_IN_PROD"] = previous_allow

    def test_prod_token_validator_requires_hash_mode(self):
        previous_hash_mode = os.environ.get("INTERNAL_AUTH_TOKEN_HASH_MODE")
        previous_allow = os.environ.get("INTERNAL_AUTH_ALLOW_TOKEN_VALIDATOR_IN_PROD")
        try:
            os.environ["INTERNAL_AUTH_TOKEN_HASH_MODE"] = "false"
            os.environ["INTERNAL_AUTH_ALLOW_TOKEN_VALIDATOR_IN_PROD"] = "true"
            with self.assertRaises(RuntimeError) as raised:
                server._validate_internal_auth_startup(
                    mode="TOKEN_VALIDATOR",
                    profile="production",
                    credentials={
                        "known-service": server.InternalServiceCredential("secret", frozenset({"governance-read"}))
                    },
                )

            self.assertNotIn("secret", str(raised.exception))
        finally:
            if previous_hash_mode is None:
                os.environ.pop("INTERNAL_AUTH_TOKEN_HASH_MODE", None)
            else:
                os.environ["INTERNAL_AUTH_TOKEN_HASH_MODE"] = previous_hash_mode
            if previous_allow is None:
                os.environ.pop("INTERNAL_AUTH_ALLOW_TOKEN_VALIDATOR_IN_PROD", None)
            else:
                os.environ["INTERNAL_AUTH_ALLOW_TOKEN_VALIDATOR_IN_PROD"] = previous_allow

    def test_prod_token_validator_requires_explicit_compatibility_opt_in(self):
        previous_hash_mode = os.environ.get("INTERNAL_AUTH_TOKEN_HASH_MODE")
        previous_allow = os.environ.get("INTERNAL_AUTH_ALLOW_TOKEN_VALIDATOR_IN_PROD")
        try:
            os.environ["INTERNAL_AUTH_TOKEN_HASH_MODE"] = "true"
            os.environ["INTERNAL_AUTH_ALLOW_TOKEN_VALIDATOR_IN_PROD"] = "false"
            with self.assertRaises(RuntimeError) as raised:
                server._validate_internal_auth_startup(
                    mode="TOKEN_VALIDATOR",
                    profile="prod",
                    credentials={
                        "known-service": server.InternalServiceCredential("secret", frozenset({"governance-read"}))
                    },
                )

            self.assertIn("explicit prod compatibility opt-in", str(raised.exception))
            self.assertNotIn("secret", str(raised.exception))
        finally:
            if previous_hash_mode is None:
                os.environ.pop("INTERNAL_AUTH_TOKEN_HASH_MODE", None)
            else:
                os.environ["INTERNAL_AUTH_TOKEN_HASH_MODE"] = previous_hash_mode
            if previous_allow is None:
                os.environ.pop("INTERNAL_AUTH_ALLOW_TOKEN_VALIDATOR_IN_PROD", None)
            else:
                os.environ["INTERNAL_AUTH_ALLOW_TOKEN_VALIDATOR_IN_PROD"] = previous_allow

    def test_token_hash_mode_accepts_hash_and_never_logs_hash_or_token(self):
        previous_mode = os.environ.get("INTERNAL_AUTH_MODE")
        previous_hash_mode = os.environ.get("INTERNAL_AUTH_TOKEN_HASH_MODE")
        previous_credentials = server.INTERNAL_SERVICE_CREDENTIALS
        token = "super-secret-token"
        token_hash = hashlib.sha256(token.encode("utf-8")).hexdigest()
        try:
            os.environ["INTERNAL_AUTH_MODE"] = "TOKEN_VALIDATOR"
            os.environ["INTERNAL_AUTH_TOKEN_HASH_MODE"] = "true"
            server.INTERNAL_SERVICE_CREDENTIALS = {
                "known-service": server.InternalServiceCredential(token_hash, frozenset({"governance-read"}))
            }

            output = StringIO()
            with redirect_stdout(output):
                status, _, payload = self.request(
                    "GET",
                    "/governance/model",
                    headers={
                        "X-Internal-Service-Name": "known-service",
                        "X-Internal-Service-Token": token,
                    },
                )

            self.assertEqual(status, 200)
            self.assertIn(b"model", payload)
            self.assertNotIn(token, output.getvalue())
            self.assertNotIn(token_hash, output.getvalue())
        finally:
            server.INTERNAL_SERVICE_CREDENTIALS = previous_credentials
            if previous_mode is None:
                os.environ.pop("INTERNAL_AUTH_MODE", None)
            else:
                os.environ["INTERNAL_AUTH_MODE"] = previous_mode
            if previous_hash_mode is None:
                os.environ.pop("INTERNAL_AUTH_TOKEN_HASH_MODE", None)
            else:
                os.environ["INTERNAL_AUTH_TOKEN_HASH_MODE"] = previous_hash_mode

    def test_malformed_allowed_service_config_does_not_create_permissive_auth(self):
        previous_hash_mode = os.environ.get("INTERNAL_AUTH_TOKEN_HASH_MODE")
        previous_allowed = os.environ.get("INTERNAL_AUTH_ALLOWED_SERVICES")
        try:
            os.environ["INTERNAL_AUTH_TOKEN_HASH_MODE"] = "true"
            os.environ["INTERNAL_AUTH_ALLOWED_SERVICES"] = "bad-entry,svc:not-a-sha256:governance-read,svc2::ml-score,svc3:486c7e8132888f1bbc9cb9165533394615e283500a51bfdfee509e8fe42b869a:"

            self.assertEqual(server._allowed_internal_services(), {})
        finally:
            if previous_hash_mode is None:
                os.environ.pop("INTERNAL_AUTH_TOKEN_HASH_MODE", None)
            else:
                os.environ["INTERNAL_AUTH_TOKEN_HASH_MODE"] = previous_hash_mode
            if previous_allowed is None:
                os.environ.pop("INTERNAL_AUTH_ALLOWED_SERVICES", None)
            else:
                os.environ["INTERNAL_AUTH_ALLOWED_SERVICES"] = previous_allowed

    def test_token_validator_rejects_service_without_required_authority(self):
        previous_mode = os.environ.get("INTERNAL_AUTH_MODE")
        previous_credentials = server.INTERNAL_SERVICE_CREDENTIALS
        try:
            os.environ["INTERNAL_AUTH_MODE"] = "TOKEN_VALIDATOR"
            server.INTERNAL_SERVICE_CREDENTIALS = {
                "known-service": server.InternalServiceCredential("secret", frozenset({"governance-read"}))
            }

            status, _, payload = self.request(
                "POST",
                "/v1/fraud/score",
                body=b'{"features":{"amount":42}}',
                headers={
                    "Content-Type": "application/json",
                    "X-Internal-Service-Name": "known-service",
                    "X-Internal-Service-Token": "secret",
                },
            )

            self.assertEqual(status, 403)
            self.assertIn(b"Internal service is not authorized for this endpoint.", payload)
        finally:
            server.INTERNAL_SERVICE_CREDENTIALS = previous_credentials
            if previous_mode is None:
                os.environ.pop("INTERNAL_AUTH_MODE", None)
            else:
                os.environ["INTERNAL_AUTH_MODE"] = previous_mode

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

    def test_jwt_service_identity_allows_known_service_and_preserves_scoring_response(self):
        saved = {key: os.environ.get(key) for key in (
            "INTERNAL_AUTH_MODE",
            "INTERNAL_AUTH_JWT_ISSUER",
            "INTERNAL_AUTH_JWT_AUDIENCE",
            "INTERNAL_AUTH_JWT_SECRET",
            "INTERNAL_AUTH_ALLOWED_SERVICE_AUTHORITIES",
        )}
        before = self.metrics_text()
        try:
            os.environ["INTERNAL_AUTH_MODE"] = "JWT_SERVICE_IDENTITY"
            os.environ["INTERNAL_AUTH_JWT_ISSUER"] = "fraud-platform-local"
            os.environ["INTERNAL_AUTH_JWT_AUDIENCE"] = "ml-inference-service"
            os.environ["INTERNAL_AUTH_JWT_SECRET"] = "local-dev-jwt-service-secret-32bytes"
            os.environ["INTERNAL_AUTH_ALLOWED_SERVICE_AUTHORITIES"] = "fraud-scoring-service:ml-score"

            status, _, payload = self.request(
                "POST",
                "/v1/fraud/score",
                body=b'{"features":{"amount":42,"recentTransactionCount":1}}',
                headers={
                    "Content-Type": "application/json",
                    "Authorization": f"Bearer {jwt_service_token()}",
                },
            )

            self.assertEqual(status, 200)
            self.assertIn(b"fraudScore", payload)
            self.assertIn(b"riskLevel", payload)
            after = self.metrics_text()
            self.assertGreater(
                parse_metric_value(after, "fraud_internal_auth_success_total", {
                    "source_service": "fraud-scoring-service",
                    "target_service": "ml-inference-service",
                    "mode": "JWT_SERVICE_IDENTITY",
                }),
                parse_metric_value(before, "fraud_internal_auth_success_total", {
                    "source_service": "fraud-scoring-service",
                    "target_service": "ml-inference-service",
                    "mode": "JWT_SERVICE_IDENTITY",
                }),
            )
        finally:
            for key, value in saved.items():
                if value is None:
                    os.environ.pop(key, None)
                else:
                    os.environ[key] = value

    def test_mtls_service_identity_accepts_san_uri_for_scoring_service(self):
        saved = {key: os.environ.get(key) for key in (
            "INTERNAL_AUTH_ALLOWED_SERVICE_AUTHORITIES",
            "INTERNAL_AUTH_MTLS_SPIFFE_TRUST_DOMAIN",
        )}
        try:
            os.environ["INTERNAL_AUTH_ALLOWED_SERVICE_AUTHORITIES"] = "fraud-scoring-service:ml-score,alert-service:governance-read"
            os.environ["INTERNAL_AUTH_MTLS_SPIFFE_TRUST_DOMAIN"] = "fraud-platform"

            principal, status, reason = server._mtls_service_principal_from_certificate(
                self.mtls_certificate("spiffe://fraud-platform/fraud-scoring-service"),
                "ml-score",
            )

            self.assertEqual(status, 200)
            self.assertEqual(reason, "allowed")
            self.assertIsNotNone(principal)
            self.assertEqual(principal.service_name, "fraud-scoring-service")
            self.assertEqual(principal.auth_mode, "MTLS_SERVICE_IDENTITY")
            self.assertIsNotNone(principal.certificate_not_before)
            self.assertIsNotNone(principal.certificate_expires_at)
        finally:
            self.restore_env(saved)

    def test_mtls_client_certificate_expiry_and_age_metrics_are_exposed(self):
        saved = {key: os.environ.get(key) for key in (
            "INTERNAL_AUTH_ALLOWED_SERVICE_AUTHORITIES",
            "INTERNAL_AUTH_MTLS_SPIFFE_TRUST_DOMAIN",
        )}
        try:
            os.environ["INTERNAL_AUTH_ALLOWED_SERVICE_AUTHORITIES"] = "fraud-scoring-service:ml-score"
            os.environ["INTERNAL_AUTH_MTLS_SPIFFE_TRUST_DOMAIN"] = "fraud-platform"

            principal, status, _ = server._mtls_service_principal_from_certificate(
                self.mtls_certificate("spiffe://fraud-platform/fraud-scoring-service"),
                "ml-score",
            )

            self.assertEqual(status, 200)
            self.assertIsNotNone(principal)
            server.FraudInferenceHandler._record_internal_auth_success(self, principal)
            after = self.metrics_text()
            labels = {
                "source_service": "fraud-scoring-service",
                "target_service": "ml-inference-service",
            }
            self.assertGreater(
                parse_metric_value(after, "fraud_internal_mtls_cert_expiry_seconds", labels),
                0,
            )
            self.assertGreater(
                parse_metric_value(after, "fraud_internal_mtls_cert_age_seconds", labels),
                0,
            )
        finally:
            self.restore_env(saved)

    def test_mtls_service_identity_rejects_unknown_cn_only_and_wrong_authority(self):
        saved = {key: os.environ.get(key) for key in (
            "INTERNAL_AUTH_ALLOWED_SERVICE_AUTHORITIES",
            "INTERNAL_AUTH_MTLS_SPIFFE_TRUST_DOMAIN",
        )}
        try:
            os.environ["INTERNAL_AUTH_ALLOWED_SERVICE_AUTHORITIES"] = "fraud-scoring-service:ml-score,alert-service:governance-read"
            os.environ["INTERNAL_AUTH_MTLS_SPIFFE_TRUST_DOMAIN"] = "fraud-platform"

            unknown = server._mtls_service_principal_from_certificate(
                self.mtls_certificate("spiffe://fraud-platform/unknown-service"),
                "ml-score",
            )
            cn_only = server._mtls_service_principal_from_certificate(
                {"subject": ((("commonName", "fraud-scoring-service"),),), "notAfter": "Apr 27 12:00:00 2030 GMT"},
                "ml-score",
            )
            alert_cannot_score = server._mtls_service_principal_from_certificate(
                self.mtls_certificate("spiffe://fraud-platform/alert-service"),
                "ml-score",
            )
            scoring_cannot_governance = server._mtls_service_principal_from_certificate(
                self.mtls_certificate("spiffe://fraud-platform/fraud-scoring-service"),
                "governance-read",
            )

            self.assertEqual(unknown[1:], (403, "unknown_internal_service"))
            self.assertEqual(cn_only[1:], (403, "unknown_internal_service"))
            self.assertEqual(alert_cannot_score[1:], (403, "missing_internal_authority"))
            self.assertEqual(scoring_cannot_governance[1:], (403, "missing_internal_authority"))
        finally:
            self.restore_env(saved)

    def test_mtls_service_identity_rejects_expired_client_certificate(self):
        saved = {key: os.environ.get(key) for key in (
            "INTERNAL_AUTH_ALLOWED_SERVICE_AUTHORITIES",
            "INTERNAL_AUTH_MTLS_SPIFFE_TRUST_DOMAIN",
        )}
        try:
            os.environ["INTERNAL_AUTH_ALLOWED_SERVICE_AUTHORITIES"] = "fraud-scoring-service:ml-score"
            os.environ["INTERNAL_AUTH_MTLS_SPIFFE_TRUST_DOMAIN"] = "fraud-platform"

            expired = {
                "subjectAltName": (("URI", "spiffe://fraud-platform/fraud-scoring-service"),),
                "notBefore": "Apr 27 12:00:00 2020 GMT",
                "notAfter": "Apr 27 12:00:00 2021 GMT",
            }
            principal, status, reason = server._mtls_service_principal_from_certificate(expired, "ml-score")

            self.assertIsNone(principal)
            self.assertEqual(status, 403)
            self.assertEqual(reason, "invalid_client_certificate")
        finally:
            self.restore_env(saved)

    def test_mtls_mode_does_not_accept_header_based_identity(self):
        saved = {key: os.environ.get(key) for key in (
            "INTERNAL_AUTH_MODE",
            "INTERNAL_AUTH_ALLOWED_SERVICE_AUTHORITIES",
        )}
        try:
            os.environ["INTERNAL_AUTH_MODE"] = "MTLS_SERVICE_IDENTITY"
            os.environ["INTERNAL_AUTH_ALLOWED_SERVICE_AUTHORITIES"] = "fraud-scoring-service:ml-score"

            status, _, payload = self.request(
                "POST",
                "/v1/fraud/score",
                body=b'{"features":{"amount":42}}',
                headers={
                    "Content-Type": "application/json",
                    "X-Internal-Service-Name": "fraud-scoring-service",
                    "X-Internal-Service-Token": "secret",
                    "Authorization": f"Bearer {jwt_service_token()}",
                },
            )

            self.assertEqual(status, 401)
            self.assertIn(b"Internal service authentication is required.", payload)
        finally:
            self.restore_env(saved)

    def test_mtls_service_identity_startup_requires_complete_configuration(self):
        saved = {key: os.environ.get(key) for key in (
            "INTERNAL_AUTH_MTLS_SERVER_CERTFILE",
            "INTERNAL_AUTH_MTLS_SERVER_KEYFILE",
            "INTERNAL_AUTH_MTLS_CA_FILE",
            "INTERNAL_AUTH_MTLS_CA_FILES",
            "INTERNAL_AUTH_ALLOWED_SERVICE_AUTHORITIES",
        )}
        try:
            for key in saved:
                os.environ.pop(key, None)
            with self.assertRaises(RuntimeError):
                server._validate_internal_auth_startup(mode="MTLS_SERVICE_IDENTITY", profile="prod")
        finally:
            self.restore_env(saved)

    def test_mtls_missing_certificate_records_bounded_handshake_failure_metric(self):
        saved = {key: os.environ.get(key) for key in (
            "INTERNAL_AUTH_MODE",
            "INTERNAL_AUTH_ALLOWED_SERVICE_AUTHORITIES",
        )}
        try:
            os.environ["INTERNAL_AUTH_MODE"] = "MTLS_SERVICE_IDENTITY"
            os.environ["INTERNAL_AUTH_ALLOWED_SERVICE_AUTHORITIES"] = "fraud-scoring-service:ml-score"
            before = self.metrics_text()

            status, _, _ = self.request(
                "POST",
                "/v1/fraud/score",
                body=b'{"features":{"amount":42}}',
                headers={"Content-Type": "application/json"},
            )

            self.assertEqual(status, 401)
            after = self.metrics_text()
            self.assertGreater(
                parse_metric_value(after, "fraud_internal_mtls_handshake_failures_total", {"reason": "MISSING_CERT"}),
                parse_metric_value(before, "fraud_internal_mtls_handshake_failures_total", {"reason": "MISSING_CERT"}),
            )
        finally:
            self.restore_env(saved)

    def test_jwt_service_identity_rejects_missing_expired_invalid_and_wrong_audience_tokens(self):
        saved = {key: os.environ.get(key) for key in (
            "INTERNAL_AUTH_MODE",
            "INTERNAL_AUTH_JWT_ISSUER",
            "INTERNAL_AUTH_JWT_AUDIENCE",
            "INTERNAL_AUTH_JWT_SECRET",
            "INTERNAL_AUTH_ALLOWED_SERVICE_AUTHORITIES",
        )}
        try:
            os.environ["INTERNAL_AUTH_MODE"] = "JWT_SERVICE_IDENTITY"
            os.environ["INTERNAL_AUTH_JWT_ISSUER"] = "fraud-platform-local"
            os.environ["INTERNAL_AUTH_JWT_AUDIENCE"] = "ml-inference-service"
            os.environ["INTERNAL_AUTH_JWT_SECRET"] = "local-dev-jwt-service-secret-32bytes"
            os.environ["INTERNAL_AUTH_ALLOWED_SERVICE_AUTHORITIES"] = "fraud-scoring-service:ml-score"

            missing_status, _, _ = self.request("POST", "/v1/fraud/score", body=b'{"features":{"amount":42}}')
            expired_status, _, _ = self.request(
                "POST",
                "/v1/fraud/score",
                body=b'{"features":{"amount":42}}',
                headers={
                    "Content-Type": "application/json",
                    "Authorization": f"Bearer {jwt_service_token(issued_at_offset_seconds=-100, expires_in_seconds=-40)}",
                },
            )
            wrong_audience_status, _, _ = self.request(
                "POST",
                "/v1/fraud/score",
                body=b'{"features":{"amount":42}}',
                headers={
                    "Content-Type": "application/json",
                    "Authorization": f"Bearer {jwt_service_token(audience='wrong-service')}",
                },
            )
            invalid_signature_status, _, _ = self.request(
                "POST",
                "/v1/fraud/score",
                body=b'{"features":{"amount":42}}',
                headers={
                    "Content-Type": "application/json",
                    "Authorization": f"Bearer {jwt_service_token(secret='wrong-secret')}",
                },
            )

            self.assertEqual(missing_status, 401)
            self.assertEqual(expired_status, 401)
            self.assertEqual(wrong_audience_status, 403)
            self.assertEqual(invalid_signature_status, 403)
        finally:
            for key, value in saved.items():
                if value is None:
                    os.environ.pop(key, None)
                else:
                    os.environ[key] = value

    def test_jwt_service_identity_rejects_wrong_issuer_malformed_none_alg_future_iat_and_missing_authorities(self):
        saved = {key: os.environ.get(key) for key in (
            "INTERNAL_AUTH_MODE",
            "INTERNAL_AUTH_JWT_ISSUER",
            "INTERNAL_AUTH_JWT_AUDIENCE",
            "INTERNAL_AUTH_JWT_SECRET",
            "INTERNAL_AUTH_ALLOWED_SERVICE_AUTHORITIES",
        )}
        try:
            os.environ["INTERNAL_AUTH_MODE"] = "JWT_SERVICE_IDENTITY"
            os.environ["INTERNAL_AUTH_JWT_ISSUER"] = "fraud-platform-local"
            os.environ["INTERNAL_AUTH_JWT_AUDIENCE"] = "ml-inference-service"
            os.environ["INTERNAL_AUTH_JWT_SECRET"] = "local-dev-jwt-service-secret-32bytes"
            os.environ["INTERNAL_AUTH_ALLOWED_SERVICE_AUTHORITIES"] = "fraud-scoring-service:ml-score"

            cases = {
                "wrong_issuer": jwt_service_token(issuer="attacker"),
                "malformed": "not-a-jwt",
                "none_alg": jwt_service_token(algorithm="none"),
                "future_iat": jwt_service_token(issued_at_offset_seconds=600),
                "missing_authorities": jwt_service_token(include_authorities=False),
            }

            for token in cases.values():
                status, _, _ = self.request(
                    "POST",
                    "/v1/fraud/score",
                    body=b'{"features":{"amount":42}}',
                    headers={
                        "Content-Type": "application/json",
                        "Authorization": f"Bearer {token}",
                    },
                )
                self.assertEqual(status, 403)
        finally:
            for key, value in saved.items():
                if value is None:
                    os.environ.pop(key, None)
                else:
                    os.environ[key] = value

    def test_jwt_service_identity_rejects_authority_escalation_and_mixed_authority_injection(self):
        saved = {key: os.environ.get(key) for key in (
            "INTERNAL_AUTH_MODE",
            "INTERNAL_AUTH_JWT_ISSUER",
            "INTERNAL_AUTH_JWT_AUDIENCE",
            "INTERNAL_AUTH_JWT_SECRET",
            "INTERNAL_AUTH_ALLOWED_SERVICE_AUTHORITIES",
        )}
        score_body = b'{"features":{"amount":42,"recentTransactionCount":1}}'
        try:
            os.environ["INTERNAL_AUTH_MODE"] = "JWT_SERVICE_IDENTITY"
            os.environ["INTERNAL_AUTH_JWT_ISSUER"] = "fraud-platform-local"
            os.environ["INTERNAL_AUTH_JWT_AUDIENCE"] = "ml-inference-service"
            os.environ["INTERNAL_AUTH_JWT_SECRET"] = "local-dev-jwt-service-secret-32bytes"
            os.environ["INTERNAL_AUTH_ALLOWED_SERVICE_AUTHORITIES"] = "fraud-scoring-service:ml-score,alert-service:governance-read"

            escalation_token = jwt_service_token(authorities=["governance-read"])
            score_escalation_status, _, _ = self.request(
                "POST",
                "/v1/fraud/score",
                body=score_body,
                headers={
                    "Content-Type": "application/json",
                    "Authorization": f"Bearer {escalation_token}",
                },
            )
            governance_escalation_status, _, _ = self.request(
                "GET",
                "/governance/model",
                headers={"Authorization": f"Bearer {escalation_token}"},
            )

            mixed_token = jwt_service_token(authorities=["ml-score", "governance-read"])
            score_mixed_status, _, _ = self.request(
                "POST",
                "/v1/fraud/score",
                body=score_body,
                headers={
                    "Content-Type": "application/json",
                    "Authorization": f"Bearer {mixed_token}",
                },
            )
            governance_mixed_status, _, _ = self.request(
                "GET",
                "/governance/model",
                headers={"Authorization": f"Bearer {mixed_token}"},
            )

            self.assertEqual(score_escalation_status, 403)
            self.assertEqual(governance_escalation_status, 403)
            self.assertEqual(score_mixed_status, 200)
            self.assertEqual(governance_mixed_status, 403)
        finally:
            for key, value in saved.items():
                if value is None:
                    os.environ.pop(key, None)
                else:
                    os.environ[key] = value

    def test_jwt_service_identity_rejects_unknown_service_and_missing_authority_without_logging_token(self):
        saved = {key: os.environ.get(key) for key in (
            "INTERNAL_AUTH_MODE",
            "INTERNAL_AUTH_JWT_ISSUER",
            "INTERNAL_AUTH_JWT_AUDIENCE",
            "INTERNAL_AUTH_JWT_SECRET",
            "INTERNAL_AUTH_ALLOWED_SERVICE_AUTHORITIES",
        )}
        token = jwt_service_token(service_name="unknown-service", authorities=["ml-score"])
        try:
            os.environ["INTERNAL_AUTH_MODE"] = "JWT_SERVICE_IDENTITY"
            os.environ["INTERNAL_AUTH_JWT_ISSUER"] = "fraud-platform-local"
            os.environ["INTERNAL_AUTH_JWT_AUDIENCE"] = "ml-inference-service"
            os.environ["INTERNAL_AUTH_JWT_SECRET"] = "local-dev-jwt-service-secret-32bytes"
            os.environ["INTERNAL_AUTH_ALLOWED_SERVICE_AUTHORITIES"] = "fraud-scoring-service:ml-score"

            output = StringIO()
            with redirect_stdout(output):
                unknown_status, _, _ = self.request(
                    "POST",
                    "/v1/fraud/score",
                    body=b'{"features":{"amount":42}}',
                    headers={
                        "Content-Type": "application/json",
                        "Authorization": f"Bearer {token}",
                    },
                )
                missing_authority_status, _, _ = self.request(
                    "GET",
                    "/governance/model",
                    headers={
                        "Authorization": f"Bearer {jwt_service_token(authorities=['ml-score'])}",
                    },
                )

            self.assertEqual(unknown_status, 403)
            self.assertEqual(missing_authority_status, 403)
            self.assertIn("internal_auth_rejected", output.getvalue())
            self.assertNotIn(token, output.getvalue())
        finally:
            for key, value in saved.items():
                if value is None:
                    os.environ.pop(key, None)
                else:
                    os.environ[key] = value

    def test_rs256_service_identity_enforces_kid_service_binding_and_authority_boundaries(self):
        saved = {key: os.environ.get(key) for key in (
            "INTERNAL_AUTH_MODE",
            "INTERNAL_AUTH_JWT_ALGORITHM",
            "INTERNAL_AUTH_JWT_ISSUER",
            "INTERNAL_AUTH_JWT_AUDIENCE",
            "INTERNAL_AUTH_JWKS_PATH",
            "INTERNAL_AUTH_JWKS_JSON",
            "INTERNAL_AUTH_ALLOWED_SERVICE_AUTHORITIES",
            "INTERNAL_AUTH_ALLOWED_SERVICE_KEYS",
        )}
        score_body = b'{"features":{"amount":42,"recentTransactionCount":1}}'
        valid_scoring_token = rs256_service_token()
        valid_alert_token = rs256_service_token(
            private_key_path=ALERT_PRIVATE_KEY,
            key_id="alert-key-1",
            service_name="alert-service",
            authorities=["governance-read"],
        )
        alert_score_token = rs256_service_token(
            private_key_path=ALERT_PRIVATE_KEY,
            key_id="alert-key-1",
            service_name="alert-service",
            authorities=["ml-score"],
        )
        alert_key_scoring_service_token = rs256_service_token(
            private_key_path=ALERT_PRIVATE_KEY,
            key_id="alert-key-1",
            service_name="fraud-scoring-service",
            authorities=["ml-score"],
        )
        wrong_key_service_token = rs256_service_token(
            private_key_path=SCORING_PRIVATE_KEY,
            key_id="scoring-key-1",
            service_name="alert-service",
            authorities=["governance-read"],
        )
        mixed_authority_token = rs256_service_token(authorities=["ml-score", "governance-read"])
        try:
            os.environ["INTERNAL_AUTH_MODE"] = "JWT_SERVICE_IDENTITY"
            os.environ["INTERNAL_AUTH_JWT_ALGORITHM"] = "RS256"
            os.environ["INTERNAL_AUTH_JWT_ISSUER"] = "fraud-platform-local"
            os.environ["INTERNAL_AUTH_JWT_AUDIENCE"] = "ml-inference-service"
            os.environ["INTERNAL_AUTH_JWKS_PATH"] = JWKS_PATH
            os.environ.pop("INTERNAL_AUTH_JWKS_JSON", None)
            os.environ["INTERNAL_AUTH_ALLOWED_SERVICE_AUTHORITIES"] = "fraud-scoring-service:ml-score,alert-service:governance-read"
            os.environ["INTERNAL_AUTH_ALLOWED_SERVICE_KEYS"] = "fraud-scoring-service:scoring-key-1,alert-service:alert-key-1"

            valid_status, _, _ = self.request(
                "POST",
                "/v1/fraud/score",
                body=score_body,
                headers={
                    "Content-Type": "application/json",
                    "Authorization": f"Bearer {valid_scoring_token}",
                },
            )
            valid_alert_status, _, _ = self.request(
                "GET",
                "/governance/model",
                headers={"Authorization": f"Bearer {valid_alert_token}"},
            )
            scoring_governance_status, _, _ = self.request(
                "GET",
                "/governance/model",
                headers={"Authorization": f"Bearer {mixed_authority_token}"},
            )
            alert_score_status, _, _ = self.request(
                "POST",
                "/v1/fraud/score",
                body=score_body,
                headers={
                    "Content-Type": "application/json",
                    "Authorization": f"Bearer {alert_score_token}",
                },
            )
            alert_key_scoring_status, _, _ = self.request(
                "POST",
                "/v1/fraud/score",
                body=score_body,
                headers={
                    "Content-Type": "application/json",
                    "Authorization": f"Bearer {alert_key_scoring_service_token}",
                },
            )
            wrong_key_status, _, _ = self.request(
                "GET",
                "/governance/model",
                headers={"Authorization": f"Bearer {wrong_key_service_token}"},
            )

            self.assertEqual(valid_status, 200)
            self.assertEqual(valid_alert_status, 200)
            self.assertEqual(scoring_governance_status, 403)
            self.assertEqual(alert_score_status, 403)
            self.assertEqual(alert_key_scoring_status, 403)
            self.assertEqual(wrong_key_status, 403)
        finally:
            for key, value in saved.items():
                if value is None:
                    os.environ.pop(key, None)
                else:
                    os.environ[key] = value

    def test_rs256_service_identity_rejects_unknown_kid_hs256_wrong_issuer_wrong_audience_expired_future_iat_and_no_logging(self):
        saved = {key: os.environ.get(key) for key in (
            "INTERNAL_AUTH_MODE",
            "INTERNAL_AUTH_JWT_ALGORITHM",
            "INTERNAL_AUTH_JWT_ISSUER",
            "INTERNAL_AUTH_JWT_AUDIENCE",
            "INTERNAL_AUTH_JWKS_PATH",
            "INTERNAL_AUTH_JWKS_JSON",
            "INTERNAL_AUTH_ALLOWED_SERVICE_AUTHORITIES",
            "INTERNAL_AUTH_ALLOWED_SERVICE_KEYS",
        )}
        tokens = {
            "unknown_kid": rs256_service_token(key_id="unknown-key"),
            "known_kid_unknown_service": rs256_service_token(service_name="unknown-service"),
            "wrong_issuer": rs256_service_token(issuer="attacker"),
            "wrong_audience": rs256_service_token(audience="wrong-service"),
            "expired": rs256_service_token(expires_in_seconds=-1),
            "future_iat": rs256_service_token(issued_at_offset_seconds=600),
            "missing_authorities": rs256_service_token(include_authorities=False),
            "hs256": jwt_service_token(),
            "none": jwt_service_token(algorithm="none"),
        }
        try:
            os.environ["INTERNAL_AUTH_MODE"] = "JWT_SERVICE_IDENTITY"
            os.environ["INTERNAL_AUTH_JWT_ALGORITHM"] = "RS256"
            os.environ["INTERNAL_AUTH_JWT_ISSUER"] = "fraud-platform-local"
            os.environ["INTERNAL_AUTH_JWT_AUDIENCE"] = "ml-inference-service"
            os.environ["INTERNAL_AUTH_JWKS_PATH"] = JWKS_PATH
            os.environ.pop("INTERNAL_AUTH_JWKS_JSON", None)
            os.environ["INTERNAL_AUTH_ALLOWED_SERVICE_AUTHORITIES"] = "fraud-scoring-service:ml-score"
            os.environ["INTERNAL_AUTH_ALLOWED_SERVICE_KEYS"] = "fraud-scoring-service:scoring-key-1"

            output = StringIO()
            with redirect_stdout(output):
                for token in tokens.values():
                    status, _, _ = self.request(
                        "POST",
                        "/v1/fraud/score",
                        body=b'{"features":{"amount":42}}',
                        headers={
                            "Content-Type": "application/json",
                            "Authorization": f"Bearer {token}",
                        },
                    )
                    self.assertIn(status, {401, 403})

            self.assertNotIn(tokens["unknown_kid"], output.getvalue())
            self.assertNotIn("BEGIN PRIVATE KEY", output.getvalue())
            self.assertNotIn("scoring-key-1", output.getvalue())
            with open(JWKS_PATH, encoding="utf-8") as handle:
                self.assertNotIn(handle.read(), output.getvalue())
        finally:
            for key, value in saved.items():
                if value is None:
                    os.environ.pop(key, None)
                else:
                    os.environ[key] = value

    def test_rs256_service_identity_enforces_strict_token_freshness(self):
        saved = {key: os.environ.get(key) for key in (
            "INTERNAL_AUTH_MODE",
            "INTERNAL_AUTH_JWT_ALGORITHM",
            "INTERNAL_AUTH_JWT_ISSUER",
            "INTERNAL_AUTH_JWT_AUDIENCE",
            "INTERNAL_AUTH_JWKS_PATH",
            "INTERNAL_AUTH_JWKS_JSON",
            "INTERNAL_AUTH_ALLOWED_SERVICE_AUTHORITIES",
            "INTERNAL_AUTH_ALLOWED_SERVICE_KEYS",
            "INTERNAL_AUTH_JWT_MAX_TOKEN_AGE_SECONDS",
            "INTERNAL_AUTH_JWT_MAX_ALLOWED_TTL_SECONDS",
            "INTERNAL_AUTH_JWT_CLOCK_SKEW_SECONDS",
        )}
        tokens = {
            "valid": rs256_service_token(),
            "expired": rs256_service_token(issued_at_offset_seconds=-100, expires_in_seconds=-40),
            "too_old": rs256_service_token(issued_at_offset_seconds=-301, expires_in_seconds=1),
            "future_iat": rs256_service_token(issued_at_offset_seconds=120),
            "missing_iat": rs256_service_token(include_iat=False),
            "exp_before_iat": rs256_service_token(expires_in_seconds=0),
        }
        try:
            os.environ["INTERNAL_AUTH_MODE"] = "JWT_SERVICE_IDENTITY"
            os.environ["INTERNAL_AUTH_JWT_ALGORITHM"] = "RS256"
            os.environ["INTERNAL_AUTH_JWT_ISSUER"] = "fraud-platform-local"
            os.environ["INTERNAL_AUTH_JWT_AUDIENCE"] = "ml-inference-service"
            os.environ["INTERNAL_AUTH_JWKS_PATH"] = JWKS_PATH
            os.environ.pop("INTERNAL_AUTH_JWKS_JSON", None)
            os.environ["INTERNAL_AUTH_ALLOWED_SERVICE_AUTHORITIES"] = "fraud-scoring-service:ml-score"
            os.environ["INTERNAL_AUTH_ALLOWED_SERVICE_KEYS"] = "fraud-scoring-service:scoring-key-1"
            os.environ["INTERNAL_AUTH_JWT_MAX_TOKEN_AGE_SECONDS"] = "300"
            os.environ["INTERNAL_AUTH_JWT_MAX_ALLOWED_TTL_SECONDS"] = "300"
            os.environ["INTERNAL_AUTH_JWT_CLOCK_SKEW_SECONDS"] = "30"

            valid_status, _, _ = self.request(
                "POST",
                "/v1/fraud/score",
                body=b'{"features":{"amount":42}}',
                headers={
                    "Content-Type": "application/json",
                    "Authorization": f"Bearer {tokens['valid']}",
                },
            )
            self.assertEqual(valid_status, 200)

            output = StringIO()
            with redirect_stdout(output):
                for name in ("expired", "too_old", "future_iat", "missing_iat", "exp_before_iat"):
                    status, _, _ = self.request(
                        "POST",
                        "/v1/fraud/score",
                        body=b'{"features":{"amount":42}}',
                        headers={
                            "Content-Type": "application/json",
                            "Authorization": f"Bearer {tokens[name]}",
                        },
                    )
                    self.assertIn(status, {401, 403})

            for token in tokens.values():
                self.assertNotIn(token, output.getvalue())

            metrics_status, _, metrics_payload = self.request("GET", "/metrics")
            metrics = metrics_payload.decode("utf-8")
            self.assertEqual(metrics_status, 200)
            self.assertIn('fraud_internal_auth_replay_rejected_total{reason="EXPIRED"}', metrics)
            self.assertIn('fraud_internal_auth_replay_rejected_total{reason="TOO_OLD"}', metrics)
            self.assertIn('fraud_internal_auth_replay_rejected_total{reason="FUTURE_IAT"}', metrics)
            self.assertIn('fraud_internal_auth_token_age_seconds_bucket{le="300.0",reason="TOO_OLD"}', metrics)
        finally:
            for key, value in saved.items():
                if value is None:
                    os.environ.pop(key, None)
                else:
                    os.environ[key] = value

    def test_rs256_service_identity_soft_replay_cache_rejects_reused_token_when_enabled(self):
        saved = {key: os.environ.get(key) for key in (
            "INTERNAL_AUTH_MODE",
            "INTERNAL_AUTH_JWT_ALGORITHM",
            "INTERNAL_AUTH_JWT_ISSUER",
            "INTERNAL_AUTH_JWT_AUDIENCE",
            "INTERNAL_AUTH_JWKS_PATH",
            "INTERNAL_AUTH_JWKS_JSON",
            "INTERNAL_AUTH_ALLOWED_SERVICE_AUTHORITIES",
            "INTERNAL_AUTH_ALLOWED_SERVICE_KEYS",
            "INTERNAL_AUTH_REPLAY_CACHE_ENABLED",
            "INTERNAL_AUTH_REPLAY_CACHE_MODE",
            "INTERNAL_AUTH_REPLAY_CACHE_MAX_ENTRIES",
        )}
        token = rs256_service_token()
        try:
            server.SOFT_REPLAY_CACHE.clear()
            os.environ["INTERNAL_AUTH_MODE"] = "JWT_SERVICE_IDENTITY"
            os.environ["INTERNAL_AUTH_JWT_ALGORITHM"] = "RS256"
            os.environ["INTERNAL_AUTH_JWT_ISSUER"] = "fraud-platform-local"
            os.environ["INTERNAL_AUTH_JWT_AUDIENCE"] = "ml-inference-service"
            os.environ["INTERNAL_AUTH_JWKS_PATH"] = JWKS_PATH
            os.environ.pop("INTERNAL_AUTH_JWKS_JSON", None)
            os.environ["INTERNAL_AUTH_ALLOWED_SERVICE_AUTHORITIES"] = "fraud-scoring-service:ml-score"
            os.environ["INTERNAL_AUTH_ALLOWED_SERVICE_KEYS"] = "fraud-scoring-service:scoring-key-1"
            os.environ["INTERNAL_AUTH_REPLAY_CACHE_ENABLED"] = "true"
            os.environ["INTERNAL_AUTH_REPLAY_CACHE_MODE"] = "reject"
            os.environ["INTERNAL_AUTH_REPLAY_CACHE_MAX_ENTRIES"] = "100"

            first_status, _, _ = self.request(
                "POST",
                "/v1/fraud/score",
                body=b'{"features":{"amount":42}}',
                headers={
                    "Content-Type": "application/json",
                    "Authorization": f"Bearer {token}",
                },
            )
            output = StringIO()
            with redirect_stdout(output):
                second_status, _, _ = self.request(
                    "POST",
                    "/v1/fraud/score",
                    body=b'{"features":{"amount":42}}',
                    headers={
                        "Content-Type": "application/json",
                        "Authorization": f"Bearer {token}",
                    },
                )

            self.assertEqual(first_status, 200)
            self.assertEqual(second_status, 403)
            self.assertIn("internal_auth_replay_detected", output.getvalue())
            self.assertNotIn(token, output.getvalue())
            metrics_status, _, metrics_payload = self.request("GET", "/metrics")
            self.assertEqual(metrics_status, 200)
            self.assertIn(
                'fraud_internal_auth_replay_rejected_total{reason="REPLAY_DETECTED"}',
                metrics_payload.decode("utf-8"),
            )
        finally:
            server.SOFT_REPLAY_CACHE.clear()
            for key, value in saved.items():
                if value is None:
                    os.environ.pop(key, None)
                else:
                    os.environ[key] = value

    def test_jwt_service_identity_startup_requires_complete_config_and_rejects_unknown_mode(self):
        saved = {key: os.environ.get(key) for key in (
            "INTERNAL_AUTH_JWT_ISSUER",
            "INTERNAL_AUTH_JWT_AUDIENCE",
            "INTERNAL_AUTH_JWT_SECRET",
            "INTERNAL_AUTH_ALLOWED_SERVICE_AUTHORITIES",
        )}
        try:
            for key in saved:
                os.environ.pop(key, None)
            with self.assertRaises(RuntimeError):
                server._validate_internal_auth_startup(mode="JWT_SERVICE_IDENTITY", profile="prod")
            with self.assertRaises(RuntimeError):
                server._validate_internal_auth_startup(mode="unknown-mode", profile="localdev")
        finally:
            for key, value in saved.items():
                if value is None:
                    os.environ.pop(key, None)
                else:
                    os.environ[key] = value

    def test_jwt_service_identity_startup_rejects_hs256_in_prod_like_profiles(self):
        saved = {key: os.environ.get(key) for key in (
            "INTERNAL_AUTH_JWT_ALGORITHM",
            "INTERNAL_AUTH_JWT_ISSUER",
            "INTERNAL_AUTH_JWT_AUDIENCE",
            "INTERNAL_AUTH_JWT_SECRET",
            "INTERNAL_AUTH_ALLOWED_SERVICE_AUTHORITIES",
        )}
        try:
            os.environ["INTERNAL_AUTH_JWT_ALGORITHM"] = "HS256"
            os.environ["INTERNAL_AUTH_JWT_ISSUER"] = "fraud-platform-local"
            os.environ["INTERNAL_AUTH_JWT_AUDIENCE"] = "ml-inference-service"
            os.environ["INTERNAL_AUTH_JWT_SECRET"] = "local-dev-jwt-service-secret-32bytes"
            os.environ["INTERNAL_AUTH_ALLOWED_SERVICE_AUTHORITIES"] = "fraud-scoring-service:ml-score"

            with self.assertRaises(RuntimeError):
                server._validate_internal_auth_startup(mode="JWT_SERVICE_IDENTITY", profile="prod")
        finally:
            for key, value in saved.items():
                if value is None:
                    os.environ.pop(key, None)
                else:
                    os.environ[key] = value

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
