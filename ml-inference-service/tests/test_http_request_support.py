from __future__ import annotations

import io
import unittest
from email.message import Message
from unittest.mock import patch

from app.http.request_support import HttpRequestSupport


class _Connection:
    def __init__(self) -> None:
        self.timeout = None

    def gettimeout(self):
        return self.timeout

    def settimeout(self, timeout) -> None:
        self.timeout = timeout


class _Request(HttpRequestSupport):
    def __init__(self, payload: bytes, *, chunked: bool = True) -> None:
        self.rfile = io.BytesIO(payload)
        self.headers = {"Transfer-Encoding": "chunked"} if chunked else {"Content-Length": str(len(payload))}
        self.connection = _Connection()


class _Clock:
    def __init__(self) -> None:
        self.now = 0.0


class _SlowReader(io.BytesIO):
    def __init__(self, payload: bytes, clock: _Clock, delay: float) -> None:
        super().__init__(payload)
        self.clock = clock
        self.delay = delay

    def read(self, size: int = -1) -> bytes:
        self.clock.now += self.delay
        return super().read(min(size, 1) if size >= 0 else 1)

    def read1(self, size: int = -1) -> bytes:
        return self.read(size)


class _SlowRequest(_Request):
    REQUEST_BODY_DEADLINE_SECONDS = 2.0

    def __init__(self, payload: bytes, *, chunked: bool = True) -> None:
        super().__init__(payload, chunked=chunked)
        self.clock = _Clock()
        self.rfile = _SlowReader(payload, self.clock, 0.2)

    def _monotonic_time(self) -> float:
        return self.clock.now


class HttpRequestSupportTest(unittest.TestCase):
    def test_reads_valid_chunked_request(self) -> None:
        request = _Request(b'7\r\n{"a":1}\r\n0\r\n\r\n')

        self.assertEqual(b'{"a":1}', request._read_body(128_000))

    def test_reads_valid_fixed_length_request(self) -> None:
        request = _Request(b'{"a":1}', chunked=False)

        self.assertEqual(b'{"a":1}', request._read_body(128_000))

    def test_rejects_transfer_encoding_with_content_length(self) -> None:
        request = _Request(b'7\r\n{"a":1}\r\n0\r\n\r\n')
        request.headers["Content-Length"] = "7"

        with self.assertRaisesRegex(ValueError, "cannot be combined"):
            request._read_body(128_000)

    def test_rejects_conflicting_repeated_content_length(self) -> None:
        request = _Request(b'{"a":1}', chunked=False)
        headers = Message()
        headers.add_header("Content-Length", "7")
        headers.add_header("Content-Length", "8")
        request.headers = headers

        with self.assertRaisesRegex(ValueError, "Conflicting Content-Length"):
            request._read_body(128_000)

    def test_accepts_equivalent_repeated_content_length(self) -> None:
        request = _Request(b'{"a":1}', chunked=False)
        headers = Message()
        headers.add_header("Content-Length", "7")
        headers.add_header("Content-Length", "7")
        request.headers = headers

        self.assertEqual(b'{"a":1}', request._read_body(128_000))

    def test_rejects_unsupported_or_ambiguous_transfer_encoding(self) -> None:
        for value in ("gzip", "gzip, chunked", "chunked, chunked", "chunked;level=1", "chunked,"):
            with self.subTest(value=value):
                request = _Request(b'7\r\n{"a":1}\r\n0\r\n\r\n')
                request.headers["Transfer-Encoding"] = value

                with self.assertRaisesRegex(ValueError, "Transfer-Encoding"):
                    request._read_body(128_000)

    def test_rejects_malformed_chunk_extensions(self) -> None:
        for size_line in (b"7;=value", b"7;name=", b'7;name="unfinished', b"7;;name=value"):
            with self.subTest(size_line=size_line):
                request = _Request(size_line + b'\r\n{"a":1}\r\n0\r\n\r\n')

                with self.assertRaisesRegex(ValueError, "Invalid chunk extension"):
                    request._read_body(128_000)

    def test_accepts_well_formed_chunk_extensions(self) -> None:
        request = _Request(b'7;name=token;quoted="hello world"\r\n{"a":1}\r\n0\r\n\r\n')

        self.assertEqual(b'{"a":1}', request._read_body(128_000))

    def test_reads_valid_bounded_trailers(self) -> None:
        request = _Request(b'7\r\n{"a":1}\r\n0\r\nDigest: sha-256=value\r\nTrace-Id: one\r\n\r\n')

        self.assertEqual(b'{"a":1}', request._read_body(128_000))

    def test_rejects_excessive_trailer_lines(self) -> None:
        request = _Request(b'1\r\nx\r\n0\r\nA: 1\r\nB: 2\r\n\r\n')
        request.MAX_TRAILER_LINES = 1

        with self.assertRaisesRegex(ValueError, "Too many chunk trailer lines"):
            request._read_body(128_000)

    def test_rejects_excessive_trailer_bytes(self) -> None:
        request = _Request(b'1\r\nx\r\n0\r\nA: 1234\r\n\r\n')
        request.MAX_TRAILER_BYTES = 8

        with self.assertRaisesRegex(ValueError, "Chunk trailer section is too large"):
            request._read_body(128_000)

    def test_rejects_oversized_trailer_line(self) -> None:
        request = _Request(b'1\r\nx\r\n0\r\nLong: value\r\n\r\n')
        request.MAX_TRAILER_LINE_BYTES = 8

        with self.assertRaisesRegex(ValueError, "Chunk trailer line is too large"):
            request._read_body(128_000)

    def test_rejects_premature_trailer_eof(self) -> None:
        request = _Request(b'1\r\nx\r\n0\r\nTrace-Id: unfinished')

        with self.assertRaisesRegex(ValueError, "Incomplete chunk trailer section"):
            request._read_body(128_000)

    def test_rejects_framing_fields_in_trailers(self) -> None:
        request = _Request(b'7\r\n{"a":1}\r\n0\r\nContent-Length: 7\r\n\r\n')

        with self.assertRaisesRegex(ValueError, "forbidden in chunk trailers"):
            request._read_body(128_000)

    def test_rejects_malformed_chunk_termination(self) -> None:
        request = _Request(b'1\r\nx\n0\r\n\r\n')

        with self.assertRaisesRegex(ValueError, "Invalid chunk terminator"):
            request._read_body(128_000)

    def test_rejects_slow_trailer_stream_at_absolute_deadline(self) -> None:
        request = _SlowRequest(b'1\r\nx\r\n0\r\nTrace-Id: slow\r\n\r\n')

        with self.assertRaisesRegex(ValueError, "read deadline exceeded"):
            request._read_body(128_000)

    def test_preserves_content_length_request_behavior(self) -> None:
        request = _Request(b'{"a":1}', chunked=False)

        self.assertEqual(b'{"a":1}', request._read_body(128_000))

    def test_rejects_incomplete_content_length_request(self) -> None:
        request = _Request(b'{"a":1}', chunked=False)
        request.headers["Content-Length"] = "8"

        with self.assertRaisesRegex(ValueError, "Incomplete request body"):
            request._read_body(128_000)

    def test_rejects_slow_content_length_request_at_absolute_deadline(self) -> None:
        request = _SlowRequest(b'{"a":1}', chunked=False)
        request.REQUEST_BODY_DEADLINE_SECONDS = 0.5

        with self.assertRaisesRegex(ValueError, "read deadline exceeded"):
            request._read_body(128_000)

    def test_invalid_chunked_request_never_reaches_scoring(self) -> None:
        from app import server

        handler = object.__new__(server.FraudInferenceHandler)
        handler.path = "/v1/fraud/score"
        handler.headers = {"Transfer-Encoding": "chunked"}
        handler.rfile = io.BytesIO(b'1\r\nx\n0\r\n\r\n')
        handler.connection = _Connection()
        handler._require_internal_auth = lambda path, authority: True
        handler._send_error = lambda *args, **kwargs: None
        handler._record_error = lambda *args, **kwargs: None
        handler._record_request = lambda *args, **kwargs: None
        handler._log_event = lambda *args, **kwargs: None

        with patch.object(server.MODEL, "score") as score:
            handler.do_POST()

        score.assert_not_called()

    def test_ambiguous_framing_never_reaches_scoring(self) -> None:
        from app import server

        handler = object.__new__(server.FraudInferenceHandler)
        handler.path = "/v1/fraud/score"
        handler.headers = {"Transfer-Encoding": "chunked", "Content-Length": "7"}
        handler.rfile = io.BytesIO(b'7\r\n{"a":1}\r\n0\r\n\r\n')
        handler.connection = _Connection()
        handler._require_internal_auth = lambda path, authority: True
        handler._send_error = lambda *args, **kwargs: None
        handler._record_error = lambda *args, **kwargs: None
        handler._record_request = lambda *args, **kwargs: None
        handler._log_event = lambda *args, **kwargs: None

        with patch.object(server.MODEL, "score") as score:
            handler.do_POST()

        score.assert_not_called()


if __name__ == "__main__":
    unittest.main()
