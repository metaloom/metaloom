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
"""Asset identity.

Assets are addressable two ways — by UUID (``/assets/{uuid}``) or by the SHA-512 of
their binary (``/assets/sha512/{sha512}``). Content-addressing is what lets a
processor record a result before it knows the asset's UUID, or without a round trip
to look it up.

Every asset-scoped client method accepts ``str``, ``uuid.UUID`` or ``AssetId`` and
resolves it here, so the two forms are interchangeable at the call site::

    client.load_asset("3f1b...-...")                    # UUID
    client.load_asset("cf83e1357eefb8bd...")            # SHA-512
    client.load_asset(AssetId.of_sha512(digest))        # explicit
"""

from __future__ import annotations

import re
import uuid as _uuid_mod
from dataclasses import dataclass

_UUID_RE = re.compile(r"^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
_SHA512_RE = re.compile(r"^[0-9a-fA-F]{128}$")


@dataclass(frozen=True)
class AssetId:
    """Either an asset UUID or the SHA-512 of its binary.

    Mirrors ``io.metaloom.loom.api.asset.AssetId``.
    """

    value: str
    is_uuid: bool

    @classmethod
    def of(cls, value: AssetId | _uuid_mod.UUID | str) -> AssetId:
        """Coerce a UUID, a SHA-512 hex digest, or an existing ``AssetId``.

        Raises:
            ValueError: If the string is neither a UUID nor 128 hex characters.
        """
        if isinstance(value, AssetId):
            return value
        if isinstance(value, _uuid_mod.UUID):
            return cls(str(value), True)
        text = str(value).strip()
        if _UUID_RE.match(text):
            return cls(text.lower(), True)
        if _SHA512_RE.match(text):
            return cls(text.lower(), False)
        raise ValueError(
            f"{text!r} is neither a UUID nor a 128-character SHA-512 hex digest, "
            f"so it cannot address an asset."
        )

    @classmethod
    def of_uuid(cls, value: _uuid_mod.UUID | str) -> AssetId:
        """Build an ``AssetId`` from a UUID without sniffing the format."""
        return cls(str(value).strip().lower(), True)

    @classmethod
    def of_sha512(cls, value: str) -> AssetId:
        """Build an ``AssetId`` from a SHA-512 hex digest without sniffing the format."""
        return cls(str(value).strip().lower(), False)

    @property
    def path(self) -> str:
        """The path segment addressing this asset, without a leading slash."""
        return f"assets/{self.value}" if self.is_uuid else f"assets/sha512/{self.value}"

    def __str__(self) -> str:
        return self.value


def asset_path(value: AssetId | _uuid_mod.UUID | str) -> str:
    """Shorthand for ``AssetId.of(value).path``."""
    return AssetId.of(value).path
