from __future__ import annotations

import json
import re
import string
import time
from datetime import datetime, timezone
from http import HTTPStatus
from typing import Any


class HttpRequestSupport:
    REQUEST_BODY_DEADLINE_SECONDS = 5.0
    MAX_CHUNK_SIZE_LINE_BYTES = 64
    MAX_TRAILER_LINE_BYTES = 1_024
    MAX_TRAILER_BYTES = 8_192
    MAX_TRAILER_LINES = 32
    _TOKEN_PATTERN = rb"[!#$%&'*+\-.^_`|~0-9A-Za-z]+"
    _QUOTED_STRING_PATTERN = rb'"(?:[\t !#-\[\]-~]|\\[\t !-~])*"'
    _CHUNK_EXTENSIONS_PATTERN = re.compile(
        rb"(?:;" + _TOKEN_PATTERN + rb"(?:=(?:" + _TOKEN_PATTERN + rb"|" + _QUOTED_STRING_PATTERN + rb"))?)*"
    )
    _FORBIDDEN_TRAILER_FIELDS = frozenset({b"content-length", b"transfer-encoding", b"trailer"})

    def _read_json(self) -> dict[str, Any] | None:
        try:
            raw_body = self._read_body(max_bytes=128_000)
            body = json.loads(raw_body.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError, ValueError):
            return None
        return body if isinstance(body, dict) else None

    def _read_body(self, max_bytes: int) -> bytes:
        framing = self._validated_message_framing()
        if framing == "chunked":
            return self._read_chunked_body(max_bytes)
        return self._read_fixed_body(max_bytes, framing)

    def _validated_message_framing(self) -> str | int:
        transfer_encoding_values = self._header_values("Transfer-Encoding")
        content_length_values = self._header_values("Content-Length")
        if transfer_encoding_values and content_length_values:
            raise ValueError("Transfer-Encoding and Content-Length cannot be combined.")
        if transfer_encoding_values:
            codings = self._comma_separated_header_values(transfer_encoding_values, "Transfer-Encoding")
            if codings != ["chunked"]:
                raise ValueError("Unsupported or ambiguous Transfer-Encoding header.")
            return "chunked"
        if not content_length_values:
            raise ValueError("Content-Length header is required.")
        lengths = self._comma_separated_header_values(content_length_values, "Content-Length")
        if any(not value.isascii() or not value.isdecimal() for value in lengths):
            raise ValueError("Invalid Content-Length header.")
        if len(set(lengths)) != 1:
            raise ValueError("Conflicting Content-Length headers.")
        return int(lengths[0])

    def _header_values(self, name: str) -> list[str]:
        get_all = getattr(self.headers, "get_all", None)
        if callable(get_all):
            values = get_all(name, [])
            return [str(value) for value in values]
        return [str(value) for key, value in self.headers.items() if str(key).lower() == name.lower()]

    def _comma_separated_header_values(self, values: list[str], name: str) -> list[str]:
        tokens = [token.strip().lower() for value in values for token in value.split(",")]
        if not tokens or any(not token for token in tokens):
            raise ValueError(f"Invalid {name} header.")
        return tokens

    def _read_fixed_body(self, max_bytes: int, content_length: int) -> bytes:
        if content_length <= 0 or content_length > max_bytes:
            raise ValueError("Request body length is outside allowed bounds.")
        deadline = self._monotonic_time() + self.REQUEST_BODY_DEADLINE_SECONDS
        previous_timeout = self._socket_timeout()
        try:
            return self._read_exactly(content_length, deadline, "Incomplete request body.")
        finally:
            self._restore_socket_timeout(previous_timeout)

    def _read_chunked_body(self, max_bytes: int) -> bytes:
        chunks: list[bytes] = []
        total_size = 0
        deadline = self._monotonic_time() + self.REQUEST_BODY_DEADLINE_SECONDS
        previous_timeout = self._socket_timeout()

        try:
            while True:
                size_line = self._read_bounded_line(
                    self.MAX_CHUNK_SIZE_LINE_BYTES,
                    deadline,
                    "Chunk size line is too large.",
                    "Incomplete chunk size line.",
                )
                size_token, _, extensions = size_line[:-2].partition(b";")
                if not size_token:
                    raise ValueError("Missing chunk size.")
                if any(chr(value) not in string.hexdigits for value in size_token):
                    raise ValueError("Invalid chunk size.")
                if extensions and not self._CHUNK_EXTENSIONS_PATTERN.fullmatch(b";" + extensions):
                    raise ValueError("Invalid chunk extension.")
                chunk_size = int(size_token, 16)

                if chunk_size == 0:
                    self._consume_trailing_chunk_headers(deadline)
                    break

                total_size += chunk_size
                if total_size > max_bytes:
                    raise ValueError("Chunked request body is too large.")

                chunks.append(self._read_exactly(chunk_size, deadline, "Incomplete chunked request body."))
                if self._read_exactly(2, deadline, "Incomplete chunk terminator.") != b"\r\n":
                    raise ValueError("Invalid chunk terminator.")
        finally:
            self._restore_socket_timeout(previous_timeout)

        if total_size <= 0:
            raise ValueError("Empty chunked request body.")
        return b"".join(chunks)

    def _consume_trailing_chunk_headers(self, deadline: float) -> None:
        total_bytes = 0
        line_count = 0
        while True:
            line = self._read_bounded_line(
                self.MAX_TRAILER_LINE_BYTES,
                deadline,
                "Chunk trailer line is too large.",
                "Incomplete chunk trailer section.",
            )
            total_bytes += len(line)
            if total_bytes > self.MAX_TRAILER_BYTES:
                raise ValueError("Chunk trailer section is too large.")
            if line == b"\r\n":
                return
            line_count += 1
            if line_count > self.MAX_TRAILER_LINES:
                raise ValueError("Too many chunk trailer lines.")
            self._validate_trailer_line(line[:-2])

    def _read_bounded_line(
            self,
            max_bytes: int,
            deadline: float,
            oversized_message: str,
            eof_message: str,
    ) -> bytes:
        line = bytearray()
        while True:
            value = self._read_from_stream(1, deadline)
            if not value:
                raise ValueError(eof_message)
            line.extend(value)
            if len(line) > max_bytes:
                raise ValueError(oversized_message)
            if value == b"\n":
                if len(line) < 2 or line[-2:] != b"\r\n":
                    raise ValueError("Chunked request lines must end with CRLF.")
                return bytes(line)

    def _read_exactly(self, size: int, deadline: float, eof_message: str) -> bytes:
        content = bytearray()
        read = getattr(self.rfile, "read1", self.rfile.read)
        while len(content) < size:
            part = self._read_from_stream(min(8_192, size - len(content)), deadline, read)
            if not part:
                raise ValueError(eof_message)
            content.extend(part)
        return bytes(content)

    def _read_from_stream(self, size: int, deadline: float, read=None) -> bytes:
        remaining = deadline - self._monotonic_time()
        if remaining <= 0:
            raise ValueError("Request body read deadline exceeded.")
        self._set_socket_timeout(remaining)
        try:
            value = (read or self.rfile.read)(size)
        except (TimeoutError, OSError) as exc:
            raise ValueError("Request body read deadline exceeded.") from exc
        if self._monotonic_time() > deadline:
            raise ValueError("Request body read deadline exceeded.")
        return value

    def _validate_trailer_line(self, line: bytes) -> None:
        name, separator, value = line.partition(b":")
        token_characters = b"!#$%&'*+-.^_`|~" + bytes(string.ascii_letters + string.digits, "ascii")
        if not separator or not name or any(character not in token_characters for character in name):
            raise ValueError("Invalid chunk trailer header.")
        if name.lower() in self._FORBIDDEN_TRAILER_FIELDS:
            raise ValueError("Framing fields are forbidden in chunk trailers.")
        if any((character < 32 and character != 9) or character == 127 for character in value):
            raise ValueError("Invalid chunk trailer header.")

    def _monotonic_time(self) -> float:
        return time.monotonic()

    def _socket_timeout(self):
        connection = getattr(self, "connection", None)
        gettimeout = getattr(connection, "gettimeout", None)
        return gettimeout() if callable(gettimeout) else None

    def _set_socket_timeout(self, timeout: float) -> None:
        connection = getattr(self, "connection", None)
        settimeout = getattr(connection, "settimeout", None)
        if callable(settimeout):
            settimeout(timeout)

    def _restore_socket_timeout(self, timeout) -> None:
        connection = getattr(self, "connection", None)
        settimeout = getattr(connection, "settimeout", None)
        if callable(settimeout):
            settimeout(timeout)

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
