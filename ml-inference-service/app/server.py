from __future__ import annotations

import json
import time
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any
from urllib.parse import parse_qs, urlparse

from prometheus_client import CONTENT_TYPE_LATEST, Counter, Gauge, Histogram, generate_latest

from app.governance.advisory import (
    AdvisoryEventService,
    AdvisoryPersistenceConfig,
    MAX_ADVISORY_LIMIT,
    create_advisory_repository,
)
from app.governance.actions import (
    ACTION_SEVERITIES,
    MAX_HISTORY_FOR_ACTIONS,
    recommend_drift_actions,
    with_model_lifecycle_context,
)
from app.governance.drift import evaluate_drift
from app.governance.lifecycle import (
    LifecyclePersistenceConfig,
    ModelLifecycleService,
    create_lifecycle_repository,
    current_model_lifecycle_metadata,
    lifecycle_metadata_summary,
)
from app.governance.profile import (
    InferenceProfile,
    governance_model_metadata,
    governance_response,
    load_reference_profile,
    reference_feature_names,
)
from app.governance.persistence import (
    GovernancePersistenceConfig,
    GovernanceSnapshotService,
    MAX_HISTORY_LIMIT,
    create_snapshot_repository,
    current_snapshot_document,
)
from app.model import DEFAULT_ARTIFACT_PATH, MODEL_NAME, MODEL_VERSION, FraudModel


HOST = "0.0.0.0"
PORT = 8090
MODEL = FraudModel()
REFERENCE_PROFILE = load_reference_profile()
MODEL_GOVERNANCE = governance_model_metadata(MODEL, DEFAULT_ARTIFACT_PATH)
MODEL_LOADED_AT = datetime.now(timezone.utc)
MODEL_LIFECYCLE = current_model_lifecycle_metadata(
    MODEL_GOVERNANCE,
    DEFAULT_ARTIFACT_PATH,
    REFERENCE_PROFILE,
    MODEL_LOADED_AT,
)
INFERENCE_PROFILE = InferenceProfile(
    MODEL_NAME,
    MODEL_VERSION,
    reference_feature_names(REFERENCE_PROFILE) or MODEL_GOVERNANCE["feature_set"],
)
PERSISTENCE_CONFIG = GovernancePersistenceConfig.from_env()
SNAPSHOT_SERVICE = GovernanceSnapshotService(
    create_snapshot_repository(PERSISTENCE_CONFIG),
    PERSISTENCE_CONFIG,
)
LIFECYCLE_CONFIG = LifecyclePersistenceConfig.from_env()
LIFECYCLE_SERVICE = ModelLifecycleService(
    create_lifecycle_repository(LIFECYCLE_CONFIG),
    LIFECYCLE_CONFIG,
)
ADVISORY_CONFIG = AdvisoryPersistenceConfig.from_env()
ADVISORY_SERVICE = AdvisoryEventService(
    create_advisory_repository(ADVISORY_CONFIG),
    ADVISORY_CONFIG,
)
REQUEST_COUNTER = Counter(
    "fraud_ml_inference_requests_total",
    "Total ML inference HTTP requests by endpoint, method, status, and outcome.",
    ("endpoint", "method", "status", "outcome"),
)
REQUEST_LATENCY = Histogram(
    "fraud_ml_inference_request_latency_seconds",
    "Latency of ML inference HTTP requests.",
    ("endpoint", "method", "status", "outcome"),
    buckets=(0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1.0, 2.5, 5.0, 10.0),
)
ERROR_COUNTER = Counter(
    "fraud_ml_inference_errors_total",
    "Rejected or failed ML inference requests.",
    ("endpoint", "method", "outcome"),
)
MODEL_LOAD_STATUS = Gauge(
    "fraud_ml_model_load_status",
    "Model load status for the active runtime.",
    ("outcome", "model_name", "model_version"),
)
MODEL_INFO = Gauge(
    "fraud_ml_model_info",
    "Active model metadata for the ML inference runtime.",
    ("model_name", "model_version"),
)
GOVERNANCE_DRIFT_STATUS = Gauge(
    "fraud_ml_governance_drift_status",
    "Current ML governance drift status. Exactly one status label is set to 1 after a drift check.",
    ("model_name", "model_version", "status"),
)
GOVERNANCE_FEATURE_DRIFT_DETECTED = Gauge(
    "fraud_ml_governance_feature_drift_detected",
    "Whether any feature drift signal is active for the current drift severity.",
    ("model_name", "model_version", "severity"),
)
GOVERNANCE_SCORE_DRIFT_DETECTED = Gauge(
    "fraud_ml_governance_score_drift_detected",
    "Whether any score drift signal is active for the current drift severity.",
    ("model_name", "model_version", "severity"),
)
GOVERNANCE_PROFILE_OBSERVATIONS = Counter(
    "fraud_ml_governance_profile_observations_total",
    "Successful scoring observations included in the aggregate inference profile.",
    ("model_name", "model_version"),
)
GOVERNANCE_REFERENCE_PROFILE_LOADED = Gauge(
    "fraud_ml_governance_reference_profile_loaded",
    "Reference profile load status for ML governance.",
    ("model_name", "model_version", "status"),
)
GOVERNANCE_DRIFT_CONFIDENCE = Gauge(
    "fraud_ml_governance_drift_confidence",
    "Current ML governance drift confidence. Exactly one confidence label is set to 1 after a drift check.",
    ("model_name", "model_version", "confidence"),
)
GOVERNANCE_SNAPSHOTS_PERSISTED = Counter(
    "fraud_ml_governance_snapshots_persisted_total",
    "Aggregate governance snapshots persisted successfully.",
    ("model_name", "model_version", "status"),
)
GOVERNANCE_SNAPSHOT_PERSISTENCE_FAILURES = Counter(
    "fraud_ml_governance_snapshot_persistence_failures_total",
    "Aggregate governance snapshot persistence failures.",
    ("model_name", "model_version", "status"),
)
GOVERNANCE_SNAPSHOT_HISTORY_AVAILABLE = Gauge(
    "fraud_ml_governance_snapshot_history_available",
    "Whether persisted governance snapshot history is currently available.",
    ("model_name", "model_version", "status"),
)
GOVERNANCE_DRIFT_ACTION_RECOMMENDATION = Gauge(
    "fraud_ml_governance_drift_action_recommendation",
    "Current advisory drift action recommendation.",
    ("model_name", "model_version", "severity"),
)
MODEL_LIFECYCLE_INFO = Gauge(
    "fraud_ml_model_lifecycle_info",
    "Read-only model lifecycle metadata for the active runtime.",
    ("model_name", "model_version", "lifecycle_mode"),
)
MODEL_LIFECYCLE_EVENTS = Counter(
    "fraud_ml_model_lifecycle_events_total",
    "Read-only model lifecycle events by event type and persistence status.",
    ("event_type", "model_name", "model_version", "status"),
)
MODEL_LIFECYCLE_HISTORY_AVAILABLE = Gauge(
    "fraud_ml_model_lifecycle_history_available",
    "Whether model lifecycle event history is available from persistent storage.",
    ("model_name", "model_version", "status"),
)
GOVERNANCE_ADVISORY_EVENTS_EMITTED = Counter(
    "fraud_ml_governance_advisory_events_emitted_total",
    "Governance advisory events emitted by severity and storage status.",
    ("severity", "model_name", "model_version", "status"),
)
GOVERNANCE_ADVISORY_EVENTS_PERSISTED = Counter(
    "fraud_ml_governance_advisory_events_persisted_total",
    "Governance advisory events persisted successfully.",
    ("severity", "model_name", "model_version", "status"),
)
GOVERNANCE_ADVISORY_PERSISTENCE_FAILURES = Counter(
    "fraud_ml_governance_advisory_persistence_failures_total",
    "Governance advisory event persistence failures.",
    ("severity", "model_name", "model_version", "status"),
)

MODEL_LOAD_STATUS.labels("success", MODEL_NAME, MODEL_VERSION).set(1)
MODEL_LOAD_STATUS.labels("failure", MODEL_NAME, MODEL_VERSION).set(0)
MODEL_INFO.labels(MODEL_NAME, MODEL_VERSION).set(1)
MODEL_LIFECYCLE_INFO.labels(MODEL_NAME, MODEL_VERSION, "READ_ONLY").set(1)
GOVERNANCE_REFERENCE_PROFILE_LOADED.labels(MODEL_NAME, MODEL_VERSION, "loaded").set(
    1 if REFERENCE_PROFILE.get("available") else 0
)
GOVERNANCE_REFERENCE_PROFILE_LOADED.labels(MODEL_NAME, MODEL_VERSION, str(REFERENCE_PROFILE.get("status"))).set(1)
for _status in ("OK", "WATCH", "DRIFT", "UNKNOWN"):
    GOVERNANCE_DRIFT_STATUS.labels(MODEL_NAME, MODEL_VERSION, _status).set(1 if _status == "UNKNOWN" else 0)
for _confidence in ("LOW", "MEDIUM", "HIGH"):
    GOVERNANCE_DRIFT_CONFIDENCE.labels(MODEL_NAME, MODEL_VERSION, _confidence).set(1 if _confidence == "LOW" else 0)
for _severity in ("WATCH", "DRIFT"):
    GOVERNANCE_FEATURE_DRIFT_DETECTED.labels(MODEL_NAME, MODEL_VERSION, _severity).set(0)
    GOVERNANCE_SCORE_DRIFT_DETECTED.labels(MODEL_NAME, MODEL_VERSION, _severity).set(0)
GOVERNANCE_SNAPSHOT_HISTORY_AVAILABLE.labels(MODEL_NAME, MODEL_VERSION, "available").set(0)
GOVERNANCE_SNAPSHOT_HISTORY_AVAILABLE.labels(MODEL_NAME, MODEL_VERSION, "unavailable").set(1)
for _severity in ACTION_SEVERITIES:
    GOVERNANCE_DRIFT_ACTION_RECOMMENDATION.labels(MODEL_NAME, MODEL_VERSION, _severity).set(0)
for _status in ("available", "partial", "unavailable"):
    MODEL_LIFECYCLE_HISTORY_AVAILABLE.labels(MODEL_NAME, MODEL_VERSION, _status).set(0)


def _initialize_lifecycle_tracking() -> None:
    summary = lifecycle_metadata_summary(MODEL_LIFECYCLE)
    summary["feature_count"] = len(MODEL_GOVERNANCE.get("feature_set") or [])
    for event_type, source, reason, metadata in (
            ("MODEL_LOADED", "model_runtime", "active model loaded by inference runtime", summary),
            ("MODEL_METADATA_DETECTED", "model_artifact", "safe model metadata detected from artifact", summary),
    ):
        event, status = LIFECYCLE_SERVICE.record_event(event_type, MODEL_LIFECYCLE, source, reason, metadata)
        _record_lifecycle_event(event, status)
    if REFERENCE_PROFILE.get("available"):
        event, status = LIFECYCLE_SERVICE.record_event(
            "REFERENCE_PROFILE_LOADED",
            MODEL_LIFECYCLE,
            "reference_profile",
            "reference profile loaded for drift context",
            {"reference_profile_id": MODEL_LIFECYCLE.get("reference_profile_id")},
        )
        _record_lifecycle_event(event, status)
    event, status = LIFECYCLE_SERVICE.record_history_status(MODEL_LIFECYCLE)
    _record_lifecycle_event(event, status)
    _record_lifecycle_history_available("AVAILABLE" if event["event_type"] == "GOVERNANCE_HISTORY_AVAILABLE" else "PARTIAL")


def _record_lifecycle_event(event: dict[str, Any], status: str) -> None:
    MODEL_LIFECYCLE_EVENTS.labels(
        str(event.get("event_type", "UNKNOWN")),
        str(event.get("model_name", "unknown")),
        str(event.get("model_version", "unknown")),
        status,
    ).inc()


def _record_lifecycle_history_available(status: str) -> None:
    normalized = str(status).lower()
    if normalized not in {"available", "partial", "unavailable"}:
        normalized = "unavailable"
    for candidate in ("available", "partial", "unavailable"):
        MODEL_LIFECYCLE_HISTORY_AVAILABLE.labels(MODEL_NAME, MODEL_VERSION, candidate).set(
            1 if candidate == normalized else 0
        )


_initialize_lifecycle_tracking()


class FraudInferenceHandler(BaseHTTPRequestHandler):
    server_version = "FraudMLInference/1.0"

    def do_GET(self) -> None:
        parsed_url = urlparse(self.path)
        path = parsed_url.path
        if path == "/health":
            started_at = time.perf_counter()
            self._send_json(200, {"status": "UP", "modelName": MODEL_NAME, "modelVersion": MODEL_VERSION})
            self._record_request(path, "GET", 200, "success", started_at)
            self._log_event("health_check", statusCode=200)
            return
        if path == "/metrics":
            self._send_metrics()
            return
        if path == "/governance/model":
            started_at = time.perf_counter()
            inference = INFERENCE_PROFILE.snapshot()
            self._send_json(200, governance_response(MODEL_GOVERNANCE, REFERENCE_PROFILE, inference))
            self._record_request(path, "GET", 200, "success", started_at)
            return
        if path == "/governance/model/current":
            started_at = time.perf_counter()
            self._send_json(200, MODEL_LIFECYCLE)
            self._record_request(path, "GET", 200, "success", started_at)
            return
        if path == "/governance/model/lifecycle":
            started_at = time.perf_counter()
            history = LIFECYCLE_SERVICE.history_response(MODEL_LIFECYCLE)
            _record_lifecycle_history_available(history["status"])
            self._send_json(200, history)
            self._record_request(path, "GET", 200, "success", started_at)
            return
        if path == "/governance/profile/reference":
            started_at = time.perf_counter()
            inference = INFERENCE_PROFILE.snapshot()
            self._send_json(
                200,
                governance_response(
                    MODEL_GOVERNANCE,
                    REFERENCE_PROFILE,
                    inference,
                    include_reference_details=True,
                ),
            )
            self._record_request(path, "GET", 200, "success", started_at)
            return
        if path == "/governance/profile/inference":
            started_at = time.perf_counter()
            inference = INFERENCE_PROFILE.snapshot()
            self._send_json(
                200,
                governance_response(
                    MODEL_GOVERNANCE,
                    REFERENCE_PROFILE,
                    inference,
                    include_inference_details=True,
                ),
            )
            self._record_request(path, "GET", 200, "success", started_at)
            return
        if path == "/governance/drift":
            started_at = time.perf_counter()
            inference = INFERENCE_PROFILE.snapshot()
            drift = evaluate_drift(REFERENCE_PROFILE, inference)
            self._record_governance_drift(drift)
            self._send_json(200, governance_response(MODEL_GOVERNANCE, REFERENCE_PROFILE, inference, drift))
            self._record_request(path, "GET", 200, "success", started_at)
            return
        if path == "/governance/drift/actions":
            started_at = time.perf_counter()
            inference = INFERENCE_PROFILE.snapshot()
            drift = evaluate_drift(REFERENCE_PROFILE, inference)
            history = self._snapshot_history_for_actions()
            actions = recommend_drift_actions(MODEL_GOVERNANCE, drift, history)
            actions = with_model_lifecycle_context(actions, LIFECYCLE_SERVICE.lifecycle_context(MODEL_LIFECYCLE))
            self._maybe_emit_advisory_event(actions)
            self._record_governance_drift(drift)
            self._record_drift_action(actions)
            self._send_json(200, actions)
            self._record_request(path, "GET", 200, "success", started_at)
            return
        if path == "/governance/advisories":
            started_at = time.perf_counter()
            limit = self._advisory_limit(parsed_url.query)
            response = ADVISORY_SERVICE.history_response(limit)
            self._send_json(200, response)
            self._record_request(path, "GET", 200, "success", started_at)
            return
        if path == "/governance/history":
            started_at = time.perf_counter()
            limit = self._history_limit(parsed_url.query)
            inference = INFERENCE_PROFILE.snapshot()
            fallback_snapshot = current_snapshot_document(
                SNAPSHOT_SERVICE,
                MODEL_GOVERNANCE,
                REFERENCE_PROFILE,
                inference,
            )
            history = SNAPSHOT_SERVICE.history_response(limit, fallback_snapshot)
            self._record_snapshot_history_available(history["status"] == "AVAILABLE")
            self._send_json(200, history)
            self._record_request(path, "GET", 200, "success", started_at)
            return
        self._send_json(404, {"error": "Not found"})
        self._record_request(path, "GET", 404, "not_found")
        self._log_event("not_found", method="GET", path=path, statusCode=404)

    def do_POST(self) -> None:
        started_at = time.perf_counter()
        path = urlparse(self.path).path
        if path != "/v1/fraud/score":
            self._send_json(404, {"error": "Not found"})
            self._record_request(path, "POST", 404, "not_found", started_at)
            self._log_event("not_found", method="POST", path=path, statusCode=404)
            return

        payload = self._read_json()
        if payload is None:
            self._send_json(400, {"error": "Malformed JSON request."})
            self._record_error(path, "POST", "rejected")
            self._record_request(path, "POST", 400, "rejected", started_at)
            self._log_event("score_rejected", statusCode=400, reason="malformed_json")
            return

        features = payload.get("features")
        if not isinstance(features, dict):
            self._send_json(422, {"error": "Field 'features' must be an object."})
            self._record_error(path, "POST", "rejected")
            self._record_request(path, "POST", 422, "rejected", started_at)
            self._log_event(
                "score_rejected",
                transactionId=payload.get("transactionId"),
                correlationId=payload.get("correlationId"),
                statusCode=422,
                reason="features_not_object",
            )
            return

        try:
            response = MODEL.score(features)
        except Exception:
            self._send_json(500, {"error": "Model inference failed."})
            self._record_error(path, "POST", "inference_error")
            self._record_request(path, "POST", 500, "inference_error", started_at)
            self._log_event("score_failed", statusCode=500)
            return

        self._send_json(200, response)
        self._update_inference_profile(response)
        self._maybe_persist_governance_snapshot()
        self._record_request(path, "POST", 200, "success", started_at)
        self._log_score(payload, features, response, started_at)

    def log_message(self, format: str, *args: Any) -> None:
        return

    def _read_json(self) -> dict[str, Any] | None:
        try:
            raw_body = self._read_body(max_bytes=128_000)
            body = json.loads(raw_body.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError, ValueError):
            return None
        return body if isinstance(body, dict) else None

    def _read_body(self, max_bytes: int) -> bytes:
        transfer_encoding = self.headers.get("Transfer-Encoding", "").lower()
        if transfer_encoding == "chunked":
            return self._read_chunked_body(max_bytes)
        return self._read_fixed_body(max_bytes)

    def _read_fixed_body(self, max_bytes: int) -> bytes:
        try:
            content_length = int(self.headers.get("Content-Length", "0"))
        except ValueError as exc:
            raise ValueError("Invalid Content-Length header.") from exc
        if content_length <= 0 or content_length > max_bytes:
            raise ValueError("Request body length is outside allowed bounds.")
        return self.rfile.read(content_length)

    def _read_chunked_body(self, max_bytes: int) -> bytes:
        chunks: list[bytes] = []
        total_size = 0

        while True:
            size_line = self.rfile.readline(64).strip()
            if not size_line:
                raise ValueError("Missing chunk size.")
            try:
                chunk_size = int(size_line.split(b";", 1)[0], 16)
            except ValueError as exc:
                raise ValueError("Invalid chunk size.") from exc

            if chunk_size == 0:
                self._consume_trailing_chunk_headers()
                break

            total_size += chunk_size
            if total_size > max_bytes:
                raise ValueError("Chunked request body is too large.")

            chunk = self.rfile.read(chunk_size)
            if len(chunk) != chunk_size:
                raise ValueError("Incomplete chunked request body.")
            chunks.append(chunk)

            if self.rfile.read(2) != b"\r\n":
                raise ValueError("Invalid chunk terminator.")

        if total_size <= 0:
            raise ValueError("Empty chunked request body.")
        return b"".join(chunks)

    def _consume_trailing_chunk_headers(self) -> None:
        while True:
            line = self.rfile.readline(8192)
            if line in (b"\r\n", b"\n", b""):
                return

    def _send_json(self, status_code: int, payload: dict[str, Any]) -> None:
        body = json.dumps(payload, separators=(",", ":"), sort_keys=True).encode("utf-8")
        self.send_response(status_code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _send_metrics(self) -> None:
        started_at = time.perf_counter()
        body = generate_latest()
        self.send_response(200)
        self.send_header("Content-Type", CONTENT_TYPE_LATEST)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)
        self._record_request("/metrics", "GET", 200, "success", started_at)

    def _log_score(
            self,
            payload: dict[str, Any],
            features: dict[str, Any],
            response: dict[str, Any],
            started_at: float,
    ) -> None:
        elapsed_ms = round((time.perf_counter() - started_at) * 1000, 2)
        self._log_event(
            "score_completed",
            transactionId=payload.get("transactionId"),
            correlationId=payload.get("correlationId"),
            statusCode=200,
            modelName=response.get("modelName"),
            modelVersion=response.get("modelVersion"),
            fraudScore=response.get("fraudScore"),
            riskLevel=response.get("riskLevel"),
            reasonCodes=response.get("reasonCodes", []),
            featureFlags=features.get("featureFlags", []),
            latencyMs=elapsed_ms,
        )

    def _record_request(
            self,
            endpoint: str,
            method: str,
            status_code: int,
            outcome: str,
            started_at: float | None = None,
    ) -> None:
        normalized_endpoint = self._normalized_endpoint(endpoint)
        status = str(status_code)
        REQUEST_COUNTER.labels(normalized_endpoint, method, status, outcome).inc()
        if started_at is not None:
            REQUEST_LATENCY.labels(normalized_endpoint, method, status, outcome).observe(
                max(time.perf_counter() - started_at, 0.0)
            )

    def _record_error(self, endpoint: str, method: str, outcome: str) -> None:
        ERROR_COUNTER.labels(self._normalized_endpoint(endpoint), method, outcome).inc()

    def _history_limit(self, query: str) -> int:
        values = parse_qs(query).get("limit", [])
        if not values:
            return MAX_HISTORY_LIMIT
        try:
            requested = int(values[0])
        except (TypeError, ValueError):
            return MAX_HISTORY_LIMIT
        return max(min(requested, MAX_HISTORY_LIMIT), 1)

    def _advisory_limit(self, query: str) -> int:
        values = parse_qs(query).get("limit", [])
        if not values:
            return MAX_ADVISORY_LIMIT
        try:
            requested = int(values[0])
        except (TypeError, ValueError):
            return MAX_ADVISORY_LIMIT
        return max(min(requested, MAX_ADVISORY_LIMIT), 1)

    def _update_inference_profile(self, response: dict[str, Any]) -> None:
        try:
            score_details = response.get("scoreDetails")
            normalized_features = score_details.get("normalizedFeatures") if isinstance(score_details, dict) else {}
            if not isinstance(normalized_features, dict):
                normalized_features = {}
            INFERENCE_PROFILE.update(
                normalized_features,
                response.get("fraudScore"),
                response.get("riskLevel"),
            )
            GOVERNANCE_PROFILE_OBSERVATIONS.labels(MODEL_NAME, MODEL_VERSION).inc()
        except Exception as exc:
            self._log_event("governance_profile_update_failed", errorType=exc.__class__.__name__)

    def _maybe_persist_governance_snapshot(self) -> None:
        if not SNAPSHOT_SERVICE.should_persist_after_success():
            return
        try:
            inference = INFERENCE_PROFILE.snapshot()
            drift = evaluate_drift(REFERENCE_PROFILE, inference)
            SNAPSHOT_SERVICE.persist_snapshot(MODEL_GOVERNANCE, REFERENCE_PROFILE, inference, drift)
            GOVERNANCE_SNAPSHOTS_PERSISTED.labels(MODEL_NAME, MODEL_VERSION, "success").inc()
            self._record_snapshot_history_available(True)
        except Exception as exc:
            GOVERNANCE_SNAPSHOT_PERSISTENCE_FAILURES.labels(MODEL_NAME, MODEL_VERSION, "failure").inc()
            self._record_snapshot_history_available(False)
            self._log_event(
                "governance_snapshot_persistence_failed",
                level="warning",
                errorType=exc.__class__.__name__,
            )

    def _record_governance_drift(self, drift: dict[str, Any]) -> None:
        status = str(drift.get("status", "UNKNOWN"))
        for candidate in ("OK", "WATCH", "DRIFT", "UNKNOWN"):
            GOVERNANCE_DRIFT_STATUS.labels(MODEL_NAME, MODEL_VERSION, candidate).set(1 if candidate == status else 0)
        confidence = str(drift.get("confidence", "LOW"))
        for candidate in ("LOW", "MEDIUM", "HIGH"):
            GOVERNANCE_DRIFT_CONFIDENCE.labels(MODEL_NAME, MODEL_VERSION, candidate).set(
                1 if candidate == confidence else 0
            )

        feature_drift = {"WATCH": 0, "DRIFT": 0}
        score_drift = {"WATCH": 0, "DRIFT": 0}
        for signal in drift.get("signals", []):
            if not isinstance(signal, dict):
                continue
            severity = str(signal.get("severity"))
            drift_type = str(signal.get("drift_type", ""))
            if severity not in {"WATCH", "DRIFT"}:
                continue
            if drift_type.startswith("feature_") or drift_type == "missing_feature_rate":
                feature_drift[severity] = 1
            if drift_type.startswith("score_") or drift_type == "high_risk_rate_shift":
                score_drift[severity] = 1
        for severity in ("WATCH", "DRIFT"):
            GOVERNANCE_FEATURE_DRIFT_DETECTED.labels(MODEL_NAME, MODEL_VERSION, severity).set(feature_drift[severity])
            GOVERNANCE_SCORE_DRIFT_DETECTED.labels(MODEL_NAME, MODEL_VERSION, severity).set(score_drift[severity])

    def _record_snapshot_history_available(self, available: bool) -> None:
        GOVERNANCE_SNAPSHOT_HISTORY_AVAILABLE.labels(MODEL_NAME, MODEL_VERSION, "available").set(1 if available else 0)
        GOVERNANCE_SNAPSHOT_HISTORY_AVAILABLE.labels(MODEL_NAME, MODEL_VERSION, "unavailable").set(
            0 if available else 1
        )

    def _snapshot_history_for_actions(self) -> list[dict[str, Any]]:
        try:
            history = SNAPSHOT_SERVICE.repository.history(MAX_HISTORY_FOR_ACTIONS)
            self._record_snapshot_history_available(True)
            return history
        except Exception:
            self._record_snapshot_history_available(False)
            return []

    def _record_drift_action(self, actions: dict[str, Any]) -> None:
        severity = str(actions.get("severity", "INFO"))
        if severity not in ACTION_SEVERITIES:
            severity = "INFO"
        for candidate_severity in ACTION_SEVERITIES:
            GOVERNANCE_DRIFT_ACTION_RECOMMENDATION.labels(MODEL_NAME, MODEL_VERSION, candidate_severity).set(0)
        GOVERNANCE_DRIFT_ACTION_RECOMMENDATION.labels(MODEL_NAME, MODEL_VERSION, severity).set(1)

    def _maybe_emit_advisory_event(self, actions: dict[str, Any]) -> None:
        lifecycle_context = actions.get("model_lifecycle")
        if not isinstance(lifecycle_context, dict):
            lifecycle_context = {}
        event, status = ADVISORY_SERVICE.emit_if_needed(actions, MODEL_GOVERNANCE, lifecycle_context)
        if event is None:
            return
        severity = str(event.get("severity", "LOW"))
        model_name = str(event.get("model_name", MODEL_NAME))
        model_version = str(event.get("model_version", MODEL_VERSION))
        GOVERNANCE_ADVISORY_EVENTS_EMITTED.labels(severity, model_name, model_version, status).inc()
        if status == "persisted":
            GOVERNANCE_ADVISORY_EVENTS_PERSISTED.labels(severity, model_name, model_version, "success").inc()
        else:
            GOVERNANCE_ADVISORY_PERSISTENCE_FAILURES.labels(severity, model_name, model_version, "failure").inc()

    def _normalized_endpoint(self, path: str) -> str:
        known_paths = {
            "/health",
            "/metrics",
            "/v1/fraud/score",
            "/governance/model",
            "/governance/model/current",
            "/governance/model/lifecycle",
            "/governance/profile/reference",
            "/governance/profile/inference",
            "/governance/drift",
            "/governance/drift/actions",
            "/governance/advisories",
            "/governance/history",
        }
        return path if path in known_paths else "/unknown"

    def _log_event(self, event: str, **fields: Any) -> None:
        payload = {
            "timestamp": datetime.now(timezone.utc).isoformat(timespec="milliseconds"),
            "service": "ml-inference-service",
            "event": event,
            **fields,
        }
        print(json.dumps(payload, separators=(",", ":"), sort_keys=True), flush=True)


def main() -> None:
    server = ThreadingHTTPServer((HOST, PORT), FraudInferenceHandler)
    print(json.dumps({
        "timestamp": datetime.now(timezone.utc).isoformat(timespec="milliseconds"),
        "service": "ml-inference-service",
        "event": "service_started",
        "host": HOST,
        "port": PORT,
        "modelName": MODEL_NAME,
        "modelVersion": MODEL_VERSION,
    }, separators=(",", ":"), sort_keys=True), flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
