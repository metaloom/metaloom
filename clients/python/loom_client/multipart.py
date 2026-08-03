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
"""``multipart/form-data`` encoding for the three upload routes.

Loom accepts uploads on ``POST /assets/upload``, ``POST /assets/:uuid/binary/data``
and ``POST /attachments``. All three expect the file part to be named ``file``,
with any extra values sent as ordinary form fields alongside it.

The body is assembled in memory. The server sets no body-size limit, so a large
video would otherwise become a multi-gigabyte ``bytes`` object; ``MAX_UPLOAD_BYTES``
turns that into a clear error instead of an out-of-memory kill.
"""

from __future__ import annotations

import os
import secrets
from collections.abc import Sequence

from .errors import LoomError

#: Refuse to buffer more than this in a single upload.
MAX_UPLOAD_BYTES = 64 * 1024 * 1024

DEFAULT_MIME_TYPE = "application/octet-stream"


def encode(
    data: bytes,
    *,
    filename: str,
    mime_type: str | None = None,
    fields: Sequence[tuple[str, str | None]] = (),
) -> tuple[bytes, str]:
    """Encode one file part plus optional form fields.

    Args:
        data: The file content.
        filename: Sent as the part's filename.
        mime_type: Part content type; defaults to ``application/octet-stream``.
        fields: ``(name, value)`` pairs written before the file part. A pair whose
            value is ``None`` is skipped, which is how optional parameters such as
            ``poolUuid`` are omitted.

    Returns:
        The encoded body and the matching ``Content-Type`` header value.

    Raises:
        LoomError: If ``data`` exceeds :data:`MAX_UPLOAD_BYTES`.
    """
    if len(data) > MAX_UPLOAD_BYTES:
        raise LoomError(
            f"Upload of {len(data)} bytes exceeds the in-memory limit of "
            f"{MAX_UPLOAD_BYTES} bytes. Split the upload, or raise "
            f"loom_client.multipart.MAX_UPLOAD_BYTES if the memory is available."
        )

    # A random boundary is the only safe choice: a fixed one silently corrupts any
    # upload whose bytes happen to contain it.
    boundary = "----loom" + secrets.token_hex(16)
    out = bytearray()

    for name, value in fields:
        if value is None:
            continue
        out += f"--{boundary}\r\n".encode()
        out += f'Content-Disposition: form-data; name="{_escape(name)}"\r\n\r\n'.encode()
        out += str(value).encode("utf-8")
        out += b"\r\n"

    out += f"--{boundary}\r\n".encode()
    out += (f'Content-Disposition: form-data; name="file"; filename="{_escape(filename)}"\r\n').encode()
    out += f"Content-Type: {mime_type or DEFAULT_MIME_TYPE}\r\n\r\n".encode()
    out += data
    out += f"\r\n--{boundary}--\r\n".encode()

    return bytes(out), f"multipart/form-data; boundary={boundary}"


def encode_file(
    path: str,
    *,
    filename: str | None = None,
    mime_type: str | None = None,
    fields: Sequence[tuple[str, str | None]] = (),
) -> tuple[bytes, str]:
    """Read ``path`` and encode it as the file part.

    The basename of ``path`` is used as the filename unless ``filename`` overrides it.
    """
    size = os.path.getsize(path)
    if size > MAX_UPLOAD_BYTES:
        raise LoomError(
            f"{path} is {size} bytes, which exceeds the in-memory upload limit of "
            f"{MAX_UPLOAD_BYTES} bytes. Raise loom_client.multipart.MAX_UPLOAD_BYTES "
            f"if the memory is available."
        )
    with open(path, "rb") as f:
        data = f.read()
    return encode(
        data,
        filename=filename or os.path.basename(path),
        mime_type=mime_type,
        fields=fields,
    )


def _escape(value: str) -> str:
    """Neutralise characters that would break out of a quoted header parameter."""
    return value.replace("\\", "\\\\").replace('"', "%22").replace("\r", "").replace("\n", "")
