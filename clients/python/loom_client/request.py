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
"""The deferred request object every client method returns.

A client method fixes the verb, path and response model but does not send anything.
That leaves room to attach paging and filtering afterwards, which is why the paging
vocabulary lives in one place instead of on 228 method signatures::

    client.list_users().limit(50).sort("username").body()

Two terminals, so the common case stays short:

    ``body()``     the parsed model — what almost every caller wants
    ``execute()``  the full :class:`~loom_client.response.LoomResponse`, when the
                   status code or a response header matters
"""

from __future__ import annotations

import uuid as _uuid_mod
from collections.abc import Iterator
from typing import TYPE_CHECKING, Any, Generic, TypeVar

from .filters import Filter
from .params import DIRECTION, FILTER, FROM, LIMIT, SORT

if TYPE_CHECKING:  # pragma: no cover - import cycle only matters to type checkers
    from .response import LoomResponse
    from .transport import Transport

T = TypeVar("T")


class LoomRequest(Generic[T]):
    """A prepared but unsent call. Mirrors the Java ``LoomClientRequest<T>``.

    Instances are built by the client's ``_get``/``_post``/... helpers, never directly.
    The query-parameter methods mutate and return ``self``, so they chain.
    """

    __slots__ = (
        "_transport",
        "method",
        "path",
        "model",
        "payload",
        "body_bytes",
        "content_type",
        "binary",
        "params",
        "_timeout",
    )

    def __init__(
        self,
        transport: Transport,
        method: str,
        path: str,
        *,
        model: type[T] | None = None,
        payload: Any = None,
        body_bytes: bytes | None = None,
        content_type: str | None = None,
        binary: bool = False,
    ) -> None:
        self._transport = transport
        self.method = method
        # Some Java call sites pass "/tags" and others "tags"; OkHttp absorbs the
        # difference, a plain string join would produce "/api/v1//tags" and 404.
        self.path = path.strip("/")
        self.model = model
        self.payload = payload
        self.body_bytes = body_bytes
        self.content_type = content_type
        self.binary = binary
        # A list rather than a dict: `filter` is legitimately repeatable.
        self.params: list[tuple[str, str]] = []
        self._timeout: float | None = None

    # -- query parameters -------------------------------------------------

    def param(self, key: str, value: object) -> LoomRequest[T]:
        """Append an arbitrary query parameter."""
        self.params.append((key, str(value)))
        return self

    def limit(self, count: int) -> LoomRequest[T]:
        """Set the page size. The server defaults to 25."""
        return self.param(LIMIT, int(count))

    def from_(self, start_uuid: _uuid_mod.UUID | str) -> LoomRequest[T]:
        """Seek to the element with this UUID.

        Named with a trailing underscore because ``from`` is a Python keyword.
        """
        return self.param(FROM, start_uuid)

    def filter(self, expression: Filter | str) -> LoomRequest[T]:
        """Add an LHS filter term. Repeatable; the server ANDs the terms.

        See :mod:`loom_client.filters` for the builders.
        """
        return self.param(FILTER, expression)

    def sort(self, key: object) -> LoomRequest[T]:
        """Sort by a field. See :class:`loom_client.params.SortKey`."""
        return self.param(SORT, key)

    def direction(self, direction: object) -> LoomRequest[T]:
        """Set the sort order. See :class:`loom_client.params.SortDirection`."""
        return self.param(DIRECTION, direction)

    def timeout(self, seconds: float) -> LoomRequest[T]:
        """Override the client-wide timeout for this one call.

        Worth raising for uploads and downloads of large binaries.
        """
        self._timeout = seconds
        return self

    # -- terminals --------------------------------------------------------

    def execute(self) -> LoomResponse[T]:
        """Send the request and return the full response.

        Raises:
            LoomHttpError: On any non-2xx status.
            LoomConnectionError: If no response was received at all.
        """
        return self._transport.send(self)

    def body(self) -> T | None:
        """Send the request and return just the parsed body.

        ``None`` for routes that answer 204 with no content.
        """
        return self.execute().body

    def iter(self, page_size: int | None = None) -> Iterator[Any]:
        """Iterate every element across all pages of a list route.

        Seek paging in this API means following ``_metainfo.lastUuid`` into the next
        request's ``from`` parameter. Doing that by hand at every call site is the
        kind of loop everyone writes slightly differently, so it lives here::

            for user in client.list_users().iter():
                print(user.username)

        Args:
            page_size: Optional ``limit`` for each underlying request.

        Raises:
            TypeError: If the response model is not a list response.
        """
        if page_size is not None:
            self.limit(page_size)

        seen_cursors: set[str] = set()
        while True:
            response = self._transport.send(self)
            page = response.body
            data = getattr(page, "data", None)
            if data is None:
                raise TypeError(
                    f"{type(page).__name__} is not a list response, so it cannot be paged. "
                    f"Use body() instead of iter()."
                )
            yield from data

            metainfo = getattr(page, "metainfo", None)
            last_uuid = getattr(metainfo, "last_uuid", None) if metainfo else None
            # A short page means this was the last one. A repeated cursor would mean
            # the server is not advancing; stop rather than loop forever.
            if not data or not last_uuid or str(last_uuid) in seen_cursors:
                return
            seen_cursors.add(str(last_uuid))
            self.params = [(k, v) for k, v in self.params if k != FROM]
            self.from_(last_uuid)

    def __repr__(self) -> str:
        return f"LoomRequest({self.method} {self.path!r}, params={self.params!r})"
