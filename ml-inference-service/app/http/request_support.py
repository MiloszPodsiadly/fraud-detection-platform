from __future__ import annotations

import json
from datetime import datetime, timezone
from http import HTTPStatus
from typing import Any


class HttpRequestSupport:
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

    def _send_error(
            self,
            status_code: int,
            error: str | None = None,
            message: str | None = None,
            details: list[str] | None = None,
    ) -> None:
        status = HTTPStatus(status_code)
        self._send_json(
            status_code,
            {
                "timestamp": datetime.now(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z"),
                "status": status_code,
                "error": error or status.phrase,
                "message": message or status.phrase,
                "details": list(details or []),
            },
        )
