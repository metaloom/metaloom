"""Asset pools. Mirrors ``io.metaloom.loom.client.common.method.AssetPoolMethods``."""

from __future__ import annotations

import uuid as _uuid_mod
from typing import TYPE_CHECKING

from ..models.pool import (
    AssetPoolCreateRequest,
    AssetPoolListResponse,
    AssetPoolResponse,
    AssetPoolUpdateRequest,
)

if TYPE_CHECKING:
    from ..request import LoomRequest


class AssetPoolMethods:
    """CRUD on ``/pools``.

    A pool is a storage target assets can be uploaded into."""

    def load_pool(self, pool_uuid: _uuid_mod.UUID | str) -> LoomRequest[AssetPoolResponse]:
        """Load a single pool."""
        return self._get(f"pools/{self._uuid(pool_uuid)}", AssetPoolResponse)

    def create_pool(self, request: AssetPoolCreateRequest) -> LoomRequest[AssetPoolResponse]:
        """Create a pool."""
        return self._post("pools", request, AssetPoolResponse)

    def update_pool(
        self, pool_uuid: _uuid_mod.UUID | str, request: AssetPoolUpdateRequest
    ) -> LoomRequest[AssetPoolResponse]:
        """Update a pool. Only the fields you set are changed."""
        return self._post(f"pools/{self._uuid(pool_uuid)}", request, AssetPoolResponse)

    def list_pools(self) -> LoomRequest[AssetPoolListResponse]:
        """List pools. Supports ``limit``, ``from_``, ``filter``, ``sort`` and ``iter``."""
        return self._get("pools", AssetPoolListResponse)

    def delete_pool(self, pool_uuid: _uuid_mod.UUID | str) -> LoomRequest[None]:
        """Delete a pool. Answers 204 with no body."""
        return self._delete(f"pools/{self._uuid(pool_uuid)}")
