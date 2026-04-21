from __future__ import annotations

import json
import time
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any
from urllib.parse import urlparse

from app.model import MODEL_NAME, MODEL_VERSION, FraudModel


HOST = "0.0.0.0"
PORT = 8090
MODEL = FraudModel()


class FraudInferenceHandler(BaseHTTPRequestHandler):
    server_version = "FraudMLInference/1.0"

    def do_GET(self) -> None:
        path = urlparse(self.path).path
        if path == "/health":
            self._send_json(200, {"status": "UP", "modelName": MODEL_NAME, "modelVersion": MODEL_VERSION})
            self._log_event("health_check", statusCode=200)
            return
        self._send_json(404, {"error": "Not found"})
        self._log_event("not_found", method="GET", path=path, statusCode=404)

    def do_POST(self) -> None:
        started_at = time.perf_counter()
        path = urlparse(self.path).path
        if path != "/v1/fraud/score":
            self._send_json(404, {"error": "Not found"})
            self._log_event("not_found", method="POST", path=path, statusCode=404)
            return

        payload = self._read_json()
        if payload is None:
            self._send_json(400, {"error": "Malformed JSON request."})
            self._log_event("score_rejected", statusCode=400, reason="malformed_json")
            return

        features = payload.get("features")
        if not isinstance(features, dict):
            self._send_json(422, {"error": "Field 'features' must be an object."})
            self._log_event(
                "score_rejected",
                transactionId=payload.get("transactionId"),
                correlationId=payload.get("correlationId"),
                statusCode=422,
                reason="features_not_object",
            )
            return

        response = MODEL.score(features)
        self._send_json(200, response)
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
