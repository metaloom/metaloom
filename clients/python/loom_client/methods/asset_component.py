"""Asset components. Mirrors ``io.metaloom.loom.client.common.method.AssetComponentMethods``."""

from __future__ import annotations

import uuid as _uuid_mod
from typing import TYPE_CHECKING

from ..models.asset import (
    AssetComponentCreateRequest,
    AssetComponentListResponse,
    AssetComponentResponse,
    AssetComponentUpdateRequest,
)

if TYPE_CHECKING:
    from ..request import LoomRequest


class AssetComponentMethods:
    """Generic components under ``/assets/{uuid}/components``.

    A component is a typed piece of derived data a processing node produced for an
    asset. The typed component families -- transcripts, fingerprints, segments, JSON
    -- have their own routes and their own method groups; this is the generic one.
    """

    def load_asset_component(
        self, asset_uuid: _uuid_mod.UUID | str, comp_uuid: _uuid_mod.UUID | str
    ) -> LoomRequest[AssetComponentResponse]:
        """Load a single component of an asset."""
        return self._get(
            f"assets/{self._uuid(asset_uuid)}/components/{self._uuid(comp_uuid)}",
            AssetComponentResponse,
        )

    def create_asset_component(
        self, asset_uuid: _uuid_mod.UUID | str, request: AssetComponentCreateRequest
    ) -> LoomRequest[AssetComponentResponse]:
        """Attach a component to an asset."""
        return self._post(f"assets/{self._uuid(asset_uuid)}/components", request, AssetComponentResponse)

    def update_asset_component(
        self,
        asset_uuid: _uuid_mod.UUID | str,
        comp_uuid: _uuid_mod.UUID | str,
        request: AssetComponentUpdateRequest,
    ) -> LoomRequest[AssetComponentResponse]:
        """Update a component. Only the fields you set are changed."""
        return self._post(
            f"assets/{self._uuid(asset_uuid)}/components/{self._uuid(comp_uuid)}",
            request,
            AssetComponentResponse,
        )

    def list_asset_components(
        self, asset_uuid: _uuid_mod.UUID | str
    ) -> LoomRequest[AssetComponentListResponse]:
        """List an asset's components."""
        return self._get(f"assets/{self._uuid(asset_uuid)}/components", AssetComponentListResponse)

    def delete_asset_component(
        self, asset_uuid: _uuid_mod.UUID | str, comp_uuid: _uuid_mod.UUID | str
    ) -> LoomRequest[None]:
        """Delete a component. Answers 204 with no body."""
        return self._delete(f"assets/{self._uuid(asset_uuid)}/components/{self._uuid(comp_uuid)}")
