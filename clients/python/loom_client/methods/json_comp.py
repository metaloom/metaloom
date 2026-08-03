"""JSON components. Mirrors ``io.metaloom.loom.client.common.method.JsonCompMethods``."""

from __future__ import annotations

import uuid as _uuid_mod
from typing import TYPE_CHECKING

from ..models.jsoncomp import (
    JsonCompCreateRequest,
    JsonCompListResponse,
    JsonCompResponse,
)

if TYPE_CHECKING:
    from ..request import LoomRequest


class JsonCompMethods:
    """Free-form JSON payloads under ``/assets/{uuid}/json-comps``.

    The escape hatch for node output that has no typed component of its own."""

    def create_asset_json_comp(
        self, asset_uuid: _uuid_mod.UUID | str, request: JsonCompCreateRequest
    ) -> LoomRequest[JsonCompResponse]:
        """Attach a free-form JSON component to an asset."""
        return self._post(f"assets/{self._uuid(asset_uuid)}/json-comps", request, JsonCompResponse)

    def load_asset_json_comp(
        self, asset_uuid: _uuid_mod.UUID | str, comp_uuid: _uuid_mod.UUID | str
    ) -> LoomRequest[JsonCompResponse]:
        """Load a single JSON component of an asset."""
        return self._get(
            f"assets/{self._uuid(asset_uuid)}/json-comps/{self._uuid(comp_uuid)}", JsonCompResponse
        )

    def list_asset_json_comps(self, asset_uuid: _uuid_mod.UUID | str) -> LoomRequest[JsonCompListResponse]:
        """List an asset's JSON components."""
        return self._get(f"assets/{self._uuid(asset_uuid)}/json-comps", JsonCompListResponse)

    def delete_asset_json_comp(
        self, asset_uuid: _uuid_mod.UUID | str, comp_uuid: _uuid_mod.UUID | str
    ) -> LoomRequest[None]:
        """Delete a JSON component. Answers 204 with no body."""
        return self._delete(f"assets/{self._uuid(asset_uuid)}/json-comps/{self._uuid(comp_uuid)}")
