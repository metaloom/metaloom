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
"""Response wrappers.

``LoomResponse`` is what ``LoomRequest.execute()`` returns: the parsed model plus
the status line and headers. ``BinaryResponse`` is the body of a download route —
it holds the socket open, so it must be closed.
"""

from __future__ import annotations

import shutil
import urllib.parse
from collections.abc import Iterator, Mapping
from typing import IO, Any, Generic, TypeVar

T = TypeVar("T")


class Headers(Mapping[str, str]):
    """Case-insensitive read-only view of the response headers.

    HTTP header names are case-insensitive, but ``dict(response.headers)`` is not —
    so a caller asking for ``"Content-Type"`` against a server that sent
    ``content-type`` would silently miss it.
    """

    def __init__(self, items: Mapping[str, str] | list[tuple[str, str]] | None = None) -> None:
        pairs = items.items() if isinstance(items, Mapping) else (items or [])
        self._items: dict[str, tuple[str, str]] = {k.lower(): (k, v) for k, v in pairs}

    def __getitem__(self, key: str) -> str:
        return self._items[key.lower()][1]

    def __iter__(self) -> Iterator[str]:
        return (original for original, _ in self._items.values())

    def __len__(self) -> int:
        return len(self._items)

    def __repr__(self) -> str:
        return f"Headers({dict(self.items())!r})"


class LoomResponse(Generic[T]):
    """A completed request: the parsed body plus the HTTP status line.

    Mirrors the Java ``LoomClientResponse<T>``. ``body`` is ``None`` for routes that
    answer 204 with no content (every ``delete_*`` method).
    """

    __slots__ = ("body", "status", "reason", "headers")

    def __init__(
        self,
        body: T | None,
        status: int,
        reason: str = "",
        headers: Mapping[str, str] | None = None,
    ) -> None:
        self.body = body
        self.status = status
        self.reason = reason
        self.headers = headers if isinstance(headers, Headers) else Headers(headers)

    def header(self, name: str) -> str | None:
        """Return a single header value, or ``None`` when it is absent."""
        return self.headers.get(name)

    def __repr__(self) -> str:
        return f"LoomResponse(status={self.status}, body={self.body!r})"


class BinaryResponse:
    """A streaming binary body, e.g. an asset binary or an attachment.

    The underlying socket stays open until the response is closed, so either use it
    as a context manager or call :meth:`close`::

        with client.download_asset_binary(asset_uuid).body() as binary:
            binary.save("/tmp/out.jpg")
    """

    __slots__ = ("stream", "status", "headers", "_closed")

    def __init__(self, stream: IO[bytes], status: int, headers: Mapping[str, str]) -> None:
        self.stream = stream
        self.status = status
        self.headers = headers if isinstance(headers, Headers) else Headers(headers)
        self._closed = False

    @property
    def content_type(self) -> str | None:
        return self.headers.get("Content-Type")

    @property
    def content_length(self) -> int | None:
        raw = self.headers.get("Content-Length")
        try:
            return int(raw) if raw is not None else None
        except ValueError:
            return None

    @property
    def filename(self) -> str | None:
        """The filename from ``Content-Disposition``, or ``None``.

        Loom emits the RFC 5987 form (``filename*=utf-8''<percent-encoded>``), which
        is what gets decoded here. The plain ``filename="x"`` form is also accepted
        so that a proxy rewriting the header does not silently yield ``None``.
        """
        return _parse_filename(self.headers.get("Content-Disposition"))

    def read(self) -> bytes:
        """Read the whole body into memory."""
        return self.stream.read()

    def chunks(self, size: int = 8192) -> Iterator[bytes]:
        """Yield the body in chunks. Prefer this over :meth:`read` for large files."""
        while True:
            chunk = self.stream.read(size)
            if not chunk:
                return
            yield chunk

    def save(self, path: str) -> int:
        """Stream the body to ``path`` and return the number of bytes written.

        Closes the response afterwards, including on failure.
        """
        try:
            with open(path, "wb") as out:
                shutil.copyfileobj(self.stream, out)
                return out.tell()
        finally:
            self.close()

    def close(self) -> None:
        """Close the underlying stream. Safe to call more than once."""
        if not self._closed:
            self._closed = True
            self.stream.close()

    def __enter__(self) -> BinaryResponse:
        return self

    def __exit__(self, *exc: Any) -> None:
        self.close()

    def __repr__(self) -> str:
        return f"BinaryResponse(status={self.status}, content_type={self.content_type!r})"


def _parse_filename(disposition: str | None) -> str | None:
    if not disposition:
        return None
    for part in disposition.split(";"):
        part = part.strip()
        if part.lower().startswith("filename*="):
            value = part[len("filename*=") :].strip()
            # RFC 5987: <charset>'<language>'<percent-encoded-value>
            head, _, encoded = value.rpartition("'")
            charset = head.split("'")[0] or "utf-8"
            try:
                return urllib.parse.unquote(encoded, encoding=charset, errors="replace")
            except LookupError:
                return urllib.parse.unquote(encoded)
    for part in disposition.split(";"):
        part = part.strip()
        if part.lower().startswith("filename="):
            return part[len("filename=") :].strip().strip('"')
    return None
