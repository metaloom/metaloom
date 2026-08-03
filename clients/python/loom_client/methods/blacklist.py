"""Blacklists. Mirrors ``io.metaloom.loom.client.common.method.BlacklistMethods``."""

from __future__ import annotations

import uuid as _uuid_mod
from typing import TYPE_CHECKING

from ..models.blacklist import (
    BlacklistCreateRequest,
    BlacklistListResponse,
    BlacklistResponse,
    BlacklistUpdateRequest,
)

if TYPE_CHECKING:
    from ..request import LoomRequest


class BlacklistMethods:
    """CRUD on ``/blacklists``.

    A blacklist holds hashes of content that must not be ingested."""

    def load_blacklist(self, blacklist_uuid: _uuid_mod.UUID | str) -> LoomRequest[BlacklistResponse]:
        """Load a single blacklist."""
        return self._get(f"blacklists/{self._uuid(blacklist_uuid)}", BlacklistResponse)

    def create_blacklist(self, request: BlacklistCreateRequest) -> LoomRequest[BlacklistResponse]:
        """Create a blacklist."""
        return self._post("blacklists", request, BlacklistResponse)

    def update_blacklist(
        self, blacklist_uuid: _uuid_mod.UUID | str, request: BlacklistUpdateRequest
    ) -> LoomRequest[BlacklistResponse]:
        """Update a blacklist. Only the fields you set are changed."""
        return self._post(f"blacklists/{self._uuid(blacklist_uuid)}", request, BlacklistResponse)

    def list_blacklists(self) -> LoomRequest[BlacklistListResponse]:
        """List blacklists. Supports ``limit``, ``from_``, ``filter``, ``sort`` and ``iter``."""
        return self._get("blacklists", BlacklistListResponse)

    def delete_blacklist(self, blacklist_uuid: _uuid_mod.UUID | str) -> LoomRequest[None]:
        """Delete a blacklist. Answers 204 with no body."""
        return self._delete(f"blacklists/{self._uuid(blacklist_uuid)}")
