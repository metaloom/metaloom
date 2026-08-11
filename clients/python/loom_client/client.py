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
"""The client object.

:class:`LoomClient` inherits every method group in :mod:`loom_client.methods` and
supplies the request-building helpers those groups call.
"""

from __future__ import annotations

import os
import urllib.request
import uuid as _uuid_mod
from typing import Any, TypeVar

from .assets import AssetId
from .methods import ALL_METHOD_GROUPS
from .multipart import encode as _encode_multipart
from .multipart import encode_file as _encode_multipart_file
from .request import LoomRequest
from .response import BinaryResponse
from .transport import Transport

T = TypeVar("T")

#: The port Loom's REST API listens on by default.
DEFAULT_PORT = 8092

#: The default request timeout, in seconds.
DEFAULT_TIMEOUT = 10.0


class LoomClient(*ALL_METHOD_GROUPS):  # type: ignore[misc]
    """A synchronous client for the Loom REST API.

    Every call returns a :class:`~loom_client.request.LoomRequest` that has not been
    sent yet. Call ``.body()`` for the parsed model, or ``.execute()`` when you need
    the status code or a response header::

        with LoomClient(host="localhost", port=8092) as client:
            client.authenticate("admin", "finger")
            print(client.rest_info().body().version)
            for user in client.list_users().iter():
                print(user.username)

    Args:
        host: Server hostname.
        port: Server port.
        scheme: ``http`` or ``https``.
        path_prefix: Extra path segments in front of ``/api/v1``, for a Loom served
            under a sub-path by a reverse proxy.
        timeout: Per-request timeout in seconds. Override for one call with
            ``.timeout(seconds)`` on the request.
        token: A pre-issued API token, if you have one; otherwise call
            :meth:`authenticate`.
        connect_timeout: Accepted for symmetry with the Java client, which has
            separate connect/read/write timeouts. ``urllib`` has only one, so this is
            folded into ``timeout`` rather than being silently ignored.
        read_timeout: As ``connect_timeout``.
        opener: A custom ``urllib`` opener, for proxies, TLS contexts or test doubles.
    """

    def __init__(
        self,
        host: str = "localhost",
        port: int = DEFAULT_PORT,
        *,
        scheme: str = "http",
        path_prefix: str = "",
        timeout: float = DEFAULT_TIMEOUT,
        token: str | None = None,
        connect_timeout: float | None = None,
        read_timeout: float | None = None,
        opener: urllib.request.OpenerDirector | None = None,
    ) -> None:
        effective_timeout = max(t for t in (timeout, connect_timeout, read_timeout) if t is not None)
        self._transport = Transport(
            scheme=scheme,
            host=host,
            port=port,
            path_prefix=path_prefix,
            timeout=effective_timeout,
            token=token,
            opener=opener,
        )

    # -- configuration ----------------------------------------------------

    @classmethod
    def from_env(cls, **overrides: Any) -> LoomClient:
        """Build a client from ``LOOM_*`` environment variables.

        Reads ``LOOM_HOST``, ``LOOM_PORT``, ``LOOM_SCHEME``, ``LOOM_PATH_PREFIX``,
        ``LOOM_TIMEOUT`` and ``LOOM_TOKEN``. Keyword arguments win over the
        environment. If ``LOOM_TOKEN`` is unset but ``LOOM_USER`` and
        ``LOOM_PASSWORD`` are, the client logs in before being returned.
        """
        settings: dict[str, Any] = {
            "host": os.environ.get("LOOM_HOST", "localhost"),
            "port": int(os.environ.get("LOOM_PORT", DEFAULT_PORT)),
            "scheme": os.environ.get("LOOM_SCHEME", "http"),
            "path_prefix": os.environ.get("LOOM_PATH_PREFIX", ""),
            "timeout": float(os.environ.get("LOOM_TIMEOUT", DEFAULT_TIMEOUT)),
            "token": os.environ.get("LOOM_TOKEN"),
        }
        settings.update(overrides)
        client = cls(**settings)
        if not client.token:
            user = os.environ.get("LOOM_USER")
            password = os.environ.get("LOOM_PASSWORD")
            if user and password:
                client.authenticate(user, password)
        return client

    @property
    def token(self) -> str | None:
        """The bearer token sent with every request, or ``None``."""
        return self._transport.token

    @token.setter
    def token(self, value: str | None) -> None:
        self._transport.token = value

    @property
    def share_session_token(self) -> str | None:
        """The share session token sent as ``X-Loom-Share-Session``, or ``None``.

        Returned by :meth:`~loom_client.methods.share.ShareMethods.open_share`.
        Entirely separate from :attr:`token`: it authorises one customer-facing
        share link and nothing else, and setting it does not make the client a
        logged-in user.
        """
        return self._transport.share_session_token

    @share_session_token.setter
    def share_session_token(self, value: str | None) -> None:
        self._transport.share_session_token = value

    def set_share_session_token(self, token: str | None) -> LoomClient:
        """Set the share session token. Returns ``self`` so it chains."""
        self._transport.share_session_token = token
        return self

    def set_token(self, token: str | None) -> LoomClient:
        """Set the bearer token. Returns ``self`` so it chains."""
        self._transport.token = token
        return self

    def authenticate(self, username: str, password: str) -> LoomClient:
        """Log in and install the resulting token. Returns ``self`` so it chains.

        Equivalent to ``client.set_token(client.login(u, p).body().token)`` — a
        two-step that is otherwise written out at the top of nearly every script.
        """
        response = self.login(username, password).body()
        return self.set_token(response.token if response else None)

    @property
    def base_url(self) -> str:
        """The absolute URL of the API root, e.g. ``http://localhost:8092/api/v1``."""
        return self._transport.url_for("")

    # -- lifecycle --------------------------------------------------------

    def close(self) -> None:
        """Release the underlying opener. Safe to call more than once."""
        self._transport.close()

    def __enter__(self) -> LoomClient:
        return self

    def __exit__(self, *exc: Any) -> None:
        self.close()

    def __repr__(self) -> str:
        return f"LoomClient({self.base_url!r}, authenticated={self.token is not None})"

    # -- request builders used by the method groups -----------------------
    #
    # These mirror the protected helpers on the Java AbstractLoomOkHttpClient. The
    # method groups only ever go through them, so the wire behaviour of all 200-odd
    # methods is defined in this one place.

    def _get(self, path: str, model: type[T]) -> LoomRequest[T]:
        return LoomRequest(self._transport, "GET", path, model=model)

    def _post(self, path: str, payload: Any, model: type[T] | None = None) -> LoomRequest[T]:
        return LoomRequest(self._transport, "POST", path, model=model, payload=payload)

    def _post_empty(self, path: str, model: type[T] | None = None) -> LoomRequest[T]:
        """POST with no payload, for routes that act on the path alone."""
        return LoomRequest(self._transport, "POST", path, model=model)

    def _put(self, path: str, payload: Any, model: type[T] | None = None) -> LoomRequest[T]:
        return LoomRequest(self._transport, "PUT", path, model=model, payload=payload)

    def _patch(self, path: str, payload: Any, model: type[T] | None = None) -> LoomRequest[T]:
        return LoomRequest(self._transport, "PATCH", path, model=model, payload=payload)

    def _delete(self, path: str, model: type[T] | None = None) -> LoomRequest[T]:
        return LoomRequest(self._transport, "DELETE", path, model=model)

    def _download(self, path: str) -> LoomRequest[BinaryResponse]:
        return LoomRequest(self._transport, "GET", path, model=None, binary=True)

    def _upload_binary(
        self, path: str, data: bytes, mime_type: str | None, model: type[T] | None = None
    ) -> LoomRequest[T]:
        """POST a raw body — no multipart wrapper, just the bytes."""
        return LoomRequest(
            self._transport,
            "POST",
            path,
            model=model,
            body_bytes=data,
            content_type=mime_type or "application/octet-stream",
        )

    def _upload_multipart(
        self,
        path: str,
        source: str | bytes,
        model: type[T] | None = None,
        *,
        filename: str | None = None,
        mime_type: str | None = None,
        fields: tuple[tuple[str, str | None], ...] = (),
    ) -> LoomRequest[T]:
        """POST ``multipart/form-data`` with the file part named ``file``.

        ``source`` is either a path to read or the bytes themselves; bytes require an
        explicit ``filename``.
        """
        if isinstance(source, (bytes, bytearray)):
            if not filename:
                raise ValueError("filename is required when uploading raw bytes")
            body, content_type = _encode_multipart(
                bytes(source), filename=filename, mime_type=mime_type, fields=fields
            )
        else:
            body, content_type = _encode_multipart_file(
                source, filename=filename, mime_type=mime_type, fields=fields
            )
        return LoomRequest(
            self._transport,
            "POST",
            path,
            model=model,
            body_bytes=body,
            content_type=content_type,
        )

    # -- identifier helpers ------------------------------------------------

    @staticmethod
    def _uuid(value: _uuid_mod.UUID | str) -> str:
        """Normalise a UUID argument to its string form."""
        return str(value)

    @staticmethod
    def _asset(value: AssetId | _uuid_mod.UUID | str) -> str:
        """Resolve an asset argument to its path, by UUID or by SHA-512."""
        return AssetId.of(value).path

    @staticmethod
    def _asset_sub(value: AssetId | _uuid_mod.UUID | str) -> str:
        """Resolve an asset argument for a *sub-resource* path.

        The server registers the content-addressed form only for the asset itself --
        ``/assets/sha512/{sha512}`` exists, ``/assets/sha512/{sha512}/tags`` does not.
        Building the latter anyway produces a 404 that says nothing about why, so a
        hash is rejected here with an explanation instead.

        The Java client does build those paths, through its ``SHA512`` overloads of
        ``tagAsset``, ``assignTaskToAsset`` and friends. Those calls cannot succeed.
        """
        asset = AssetId.of(value)
        if not asset.is_uuid:
            raise ValueError(
                "This endpoint is nested under an asset and the server only registers "
                "the nested routes by UUID, not by SHA-512. Resolve the asset first: "
                "load_asset(sha512).body().uuid"
            )
        return asset.path
