"""Asset locations. Mirrors ``io.metaloom.loom.client.common.method.AssetLocationMethods``."""

from __future__ import annotations

import uuid as _uuid_mod
from typing import TYPE_CHECKING

from ..models.asset_location import (
    AssetLocationCreateRequest,
    AssetLocationListResponse,
    AssetLocationResponse,
    AssetLocationUpdateRequest,
)

if TYPE_CHECKING:
    from ..request import LoomRequest


class AssetLocationMethods:
    """CRUD on ``/locations``.

    A location records where an asset's bytes physically live -- a filesystem path,
    an S3 bucket, a social post.

    The server does not currently register these routes, so every call answers 404.
    They are kept for parity with the Java client, which has the same gap."""

    def load_location(self, location_uuid: _uuid_mod.UUID | str) -> LoomRequest[AssetLocationResponse]:
        """Load a single location."""
        return self._get(f"locations/{self._uuid(location_uuid)}", AssetLocationResponse)

    def create_location(self, request: AssetLocationCreateRequest) -> LoomRequest[AssetLocationResponse]:
        """Create a location."""
        return self._post("locations", request, AssetLocationResponse)

    def update_location(
        self, location_uuid: _uuid_mod.UUID | str, request: AssetLocationUpdateRequest
    ) -> LoomRequest[AssetLocationResponse]:
        """Update a location. Only the fields you set are changed."""
        return self._post(f"locations/{self._uuid(location_uuid)}", request, AssetLocationResponse)

    def list_locations(self) -> LoomRequest[AssetLocationListResponse]:
        """List locations. Supports ``limit``, ``from_``, ``filter``, ``sort`` and ``iter``."""
        return self._get("locations", AssetLocationListResponse)

    def delete_location(self, location_uuid: _uuid_mod.UUID | str) -> LoomRequest[None]:
        """Delete a location. Answers 204 with no body."""
        return self._delete(f"locations/{self._uuid(location_uuid)}")
