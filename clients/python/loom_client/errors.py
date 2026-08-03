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
"""Exceptions raised by the client.

Loom reports failures as a ``GenericMessageResponse`` carrying only a ``message``
field — the internal ``LoomRestErrorCode`` never reaches the wire. The HTTP status
is therefore the only machine-readable signal, which is why every status the API
uses gets its own subclass:

    LoomError
    ├── LoomConnectionError          DNS failure, refused, TLS, socket timeout
    └── LoomHttpError                any non-2xx response
        ├── LoomBadRequestError      400
        ├── LoomUnauthorizedError    401
        ├── LoomForbiddenError       403
        ├── LoomNotFoundError        404
        ├── LoomConflictError        409
        └── LoomServerError          500, 503

``LoomHttpError`` keeps ``.status``, so the older idiom used elsewhere in this
repository still works::

    except LoomHttpError as e:
        if e.status == 404:
            ...
"""

from __future__ import annotations

import json


class LoomError(Exception):
    """Base class for everything this client raises."""


class LoomConnectionError(LoomError):
    """The request never produced an HTTP response.

    Raised for DNS failures, refused connections, TLS errors and socket timeouts.
    """


class LoomHttpError(LoomError):
    """A non-2xx HTTP response.

    Attributes:
        status: The HTTP status code.
        reason: The HTTP reason phrase, e.g. ``"Not Found"``.
        body: The raw response body, decoded as UTF-8 with replacement.
        method: The HTTP verb of the failed request.
        url: The full URL of the failed request.
    """

    def __init__(
        self,
        status: int,
        reason: str = "",
        body: str = "",
        method: str = "",
        url: str = "",
    ) -> None:
        self.status = status
        self.reason = reason
        self.body = body
        self.method = method
        self.url = url
        super().__init__(self.__str__())

    @property
    def message(self) -> str:
        """The ``message`` field of the error body, or the raw body.

        The server answers errors with ``GenericMessageResponse``, but a proxy or a
        crash can produce something else entirely — so a body that is not JSON, or
        is JSON without a ``message``, falls back to the raw text rather than
        raising a second exception while handling the first.
        """
        if not self.body:
            return self.reason
        try:
            parsed = json.loads(self.body)
        except (ValueError, TypeError):
            return self.body
        if isinstance(parsed, dict) and isinstance(parsed.get("message"), str):
            return parsed["message"]
        return self.body

    def __str__(self) -> str:
        where = f"{self.method} {self.url}".strip()
        prefix = f"{where} -> " if where else ""
        return f"{prefix}{self.status} {self.reason}: {self.message}"


class LoomBadRequestError(LoomHttpError):
    """400 — the request was rejected as malformed or invalid.

    Also raised when a ``replace_*`` (PUT) body omits a replaceable property; see
    the note on PUT completeness in the package documentation.
    """


class LoomUnauthorizedError(LoomHttpError):
    """401 — no token was sent, or the token is expired or invalid."""


class LoomForbiddenError(LoomHttpError):
    """403 — authenticated, but missing the permission the endpoint requires."""


class LoomNotFoundError(LoomHttpError):
    """404 — no such element, or no route at that path."""


class LoomConflictError(LoomHttpError):
    """409 — the request conflicts with existing state, e.g. a duplicate name."""


class LoomServerError(LoomHttpError):
    """500 or 503 — the server failed, or no processor accepted the work."""


_STATUS_MAP: dict[int, type[LoomHttpError]] = {
    400: LoomBadRequestError,
    401: LoomUnauthorizedError,
    403: LoomForbiddenError,
    404: LoomNotFoundError,
    409: LoomConflictError,
    500: LoomServerError,
    503: LoomServerError,
}


def http_error(status: int, reason: str, body: str, method: str, url: str) -> LoomHttpError:
    """Build the most specific ``LoomHttpError`` subclass for a status code."""
    cls = _STATUS_MAP.get(status, LoomHttpError)
    return cls(status, reason, body, method, url)
