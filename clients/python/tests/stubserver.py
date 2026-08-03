"""A real HTTP server for the unit tests.

Monkeypatching ``urlopen`` would not exercise the parts most likely to be wrong:
URL joining, query encoding, the exact bytes of a multipart body, ``Content-Length``
on an empty POST, header casing. Those only show up over a socket, and
``http.server`` is in the standard library, so there is no reason not to use one.

Usage::

    class MyTest(StubServerTestCase):
        def test_something(self):
            self.stub.enqueue(json_body={"version": "1.0.0"})
            self.client.rest_info().body()
            self.assertEqual(self.stub.last.path, "/api/v1")
"""

from __future__ import annotations

import json
import threading
import unittest
from dataclasses import dataclass, field
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any

from loom_client import LoomClient


@dataclass
class RecordedRequest:
    """One request as the server saw it."""

    method: str
    path: str
    query: str
    headers: dict[str, str]
    body: bytes

    @property
    def json(self) -> Any:
        return json.loads(self.body) if self.body else None

    @property
    def query_pairs(self) -> list[tuple[str, str]]:
        """Query parameters in order, preserving repeats.

        ``parse_qs`` would collapse the repeated ``filter`` parameter, which is
        exactly what needs checking.
        """
        import urllib.parse

        return urllib.parse.parse_qsl(self.query, keep_blank_values=True)

    def header(self, name: str) -> str | None:
        lowered = {k.lower(): v for k, v in self.headers.items()}
        return lowered.get(name.lower())


@dataclass
class StubResponse:
    """One scripted reply."""

    status: int = 200
    body: bytes = b""
    headers: dict[str, str] = field(default_factory=dict)


class StubServer:
    """A throwaway HTTP server that records requests and replays scripted replies."""

    def __init__(self) -> None:
        self.requests: list[RecordedRequest] = []
        self._responses: list[StubResponse] = []
        self._lock = threading.Lock()
        stub = self

        class Handler(BaseHTTPRequestHandler):
            protocol_version = "HTTP/1.1"

            def log_message(self, *args: Any) -> None:
                pass  # keep the test output readable

            def _handle(self) -> None:
                path, _, query = self.path.partition("?")
                length = int(self.headers.get("Content-Length") or 0)
                body = self.rfile.read(length) if length else b""
                with stub._lock:
                    stub.requests.append(
                        RecordedRequest(
                            method=self.command,
                            path=path,
                            query=query,
                            headers=dict(self.headers.items()),
                            body=body,
                        )
                    )
                    reply = stub._responses.pop(0) if stub._responses else StubResponse(200, b"{}")

                self.send_response(reply.status)
                for name, value in reply.headers.items():
                    self.send_header(name, value)
                if "Content-Type" not in reply.headers and reply.body:
                    self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(reply.body)))
                self.end_headers()
                if reply.body:
                    self.wfile.write(reply.body)

            do_GET = do_POST = do_PUT = do_PATCH = do_DELETE = do_HEAD = _handle

        self._server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self._thread = threading.Thread(target=self._server.serve_forever, daemon=True)

    # -- lifecycle --------------------------------------------------------

    def start(self) -> StubServer:
        self._thread.start()
        return self

    def stop(self) -> None:
        self._server.shutdown()
        self._server.server_close()
        self._thread.join(timeout=5)

    @property
    def port(self) -> int:
        return self._server.server_address[1]

    # -- scripting --------------------------------------------------------

    def enqueue(
        self,
        status: int = 200,
        *,
        json_body: Any = None,
        raw: bytes | None = None,
        headers: dict[str, str] | None = None,
    ) -> None:
        """Queue one reply. Replies are consumed in order, oldest first."""
        if raw is not None:
            body = raw
        elif json_body is not None:
            body = json.dumps(json_body).encode()
        else:
            body = b""
        with self._lock:
            self._responses.append(StubResponse(status, body, headers or {}))

    def reset(self) -> None:
        with self._lock:
            self.requests.clear()
            self._responses.clear()

    # -- assertions helpers ------------------------------------------------

    @property
    def last(self) -> RecordedRequest:
        """The most recent request. Fails loudly if nothing was received."""
        if not self.requests:
            raise AssertionError("the stub server received no requests")
        return self.requests[-1]


class StubServerTestCase(unittest.TestCase):
    """Base class giving each test a fresh :class:`StubServer` and a client for it."""

    stub: StubServer
    client: LoomClient

    @classmethod
    def setUpClass(cls) -> None:
        cls.stub = StubServer().start()

    @classmethod
    def tearDownClass(cls) -> None:
        cls.stub.stop()

    def setUp(self) -> None:
        self.stub.reset()
        self.client = LoomClient(host="127.0.0.1", port=self.stub.port, timeout=5)
        self.addCleanup(self.client.close)
