# Copyright 2024 Johannes Schüth
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
"""The HTTP layer: URL assembly, headers, and status-to-exception mapping.

Built on ``urllib`` so the client has no third-party dependencies at runtime.
"""

from __future__ import annotations

import json
import logging
import urllib.error
import urllib.parse
import urllib.request
from typing import TYPE_CHECKING, Any

from .errors import LoomConnectionError, LoomError, http_error
from .response import BinaryResponse, Headers, LoomResponse

if TYPE_CHECKING:  # pragma: no cover
    from .request import LoomRequest

log = logging.getLogger("loom_client")

#: Every route this client speaks lives under this prefix. There is no v2.
API_V1_PATH = "api/v1"

JSON_CONTENT_TYPE = "application/json"


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    """Surface redirects instead of following them.

    The stdlib handler turns a POST into a GET on 301/302, which would silently
    convert a write into a read. Loom issues no redirects under ``/api/v1``, but a
    misconfigured reverse proxy in front of it can — and that should be a loud error,
    not a request that quietly did nothing.
    """

    def redirect_request(self, req, fp, code, msg, headers, newurl):  # noqa: D102
        return None


class Transport:
    """Sends :class:`~loom_client.request.LoomRequest` objects over HTTP.

    Owns the connection settings and the bearer token. Created by
    :class:`~loom_client.client.LoomClient`; not intended to be used directly.
    """

    def __init__(
        self,
        scheme: str = "http",
        host: str = "localhost",
        port: int = 8092,
        path_prefix: str = "",
        timeout: float = 10.0,
        token: str | None = None,
        share_session_token: str | None = None,
        opener: urllib.request.OpenerDirector | None = None,
    ) -> None:
        self.scheme = scheme
        self.host = host
        self.port = port
        self.path_prefix = path_prefix.strip("/")
        self.timeout = timeout
        self.token = token
        #: The customer-facing share credential, kept apart from ``token`` on purpose.
        #: A share visitor is not a user, so it travels in its own header and can
        #: never be mistaken for a bearer token by the authentication handler.
        self.share_session_token = share_session_token
        # No HTTPCookieProcessor: the server also issues a `__Host-loom_token`
        # cookie, but honouring it would let a stale cookie authenticate a request
        # the caller believes is anonymous. The Java client ignores it too.
        self._opener = opener or urllib.request.build_opener(_NoRedirect)

    # -- URL ---------------------------------------------------------------

    def url_for(self, path: str, params: list[tuple[str, str]] | None = None) -> str:
        """Assemble the absolute URL for a path relative to ``/api/v1``."""
        segments = [s for s in (self.path_prefix, API_V1_PATH, path.strip("/")) if s]
        url = f"{self.scheme}://{self.host}:{self.port}/" + "/".join(segments)
        if params:
            url += "?" + urllib.parse.urlencode(params)
        return url

    # -- sending -----------------------------------------------------------

    def send(self, request: LoomRequest) -> LoomResponse:
        """Execute a request and return the response.

        Raises:
            LoomHttpError: On any non-2xx status.
            LoomConnectionError: If no HTTP response was received.
        """
        url = self.url_for(request.path, request.params)
        data, content_type = self._encode_body(request)

        http_request = urllib.request.Request(url, data=data, method=request.method)
        http_request.add_header("Accept", JSON_CONTENT_TYPE)
        if content_type:
            http_request.add_header("Content-Type", content_type)
        if self.token:
            http_request.add_header("Authorization", "Bearer " + self.token)
        if self.share_session_token:
            http_request.add_header("X-Loom-Share-Session", self.share_session_token)

        timeout = request._timeout if request._timeout is not None else self.timeout
        log.debug("%s %s", request.method, url)

        try:
            response = self._opener.open(http_request, timeout=timeout)
        except urllib.error.HTTPError as e:
            body = e.read().decode("utf-8", errors="replace")
            e.close()
            raise http_error(e.code, e.reason or "", body, request.method, url) from None
        except urllib.error.URLError as e:
            raise LoomConnectionError(f"{request.method} {url} failed: {e.reason}") from e
        except OSError as e:  # socket.timeout and friends escape URLError on some paths
            raise LoomConnectionError(f"{request.method} {url} failed: {e}") from e

        headers = Headers(dict(response.headers.items()))

        if request.binary:
            # The caller owns the stream and must close it.
            return LoomResponse(
                BinaryResponse(response, response.status, headers),
                response.status,
                response.reason or "",
                headers,
            )

        with response:
            raw = response.read()
        status = response.status
        reason = response.reason or ""

        if request.model is None or not raw:
            return LoomResponse(None, status, reason, headers)

        try:
            parsed = json.loads(raw)
        except ValueError as e:
            raise LoomError(
                f"{request.method} {url} returned {status} with a body that is not JSON: {raw[:200]!r}"
            ) from e
        return LoomResponse(request.model.from_dict(parsed), status, reason, headers)

    def _encode_body(self, request: LoomRequest) -> tuple[bytes | None, str | None]:
        if request.body_bytes is not None:
            return request.body_bytes, request.content_type
        if request.payload is not None:
            payload: Any = request.payload
            if hasattr(payload, "to_dict"):
                payload = payload.to_dict()
            return json.dumps(payload).encode("utf-8"), JSON_CONTENT_TYPE
        if request.method in ("POST", "PUT", "PATCH"):
            # Several routes take no payload (POST /assets/:uuid/tasks/:taskUuid and
            # friends). Send an explicit empty body so Content-Length: 0 is set
            # rather than omitted.
            return b"", None
        return None, None

    def close(self) -> None:
        """Release the opener. Safe to call more than once."""
        self._opener.close()
