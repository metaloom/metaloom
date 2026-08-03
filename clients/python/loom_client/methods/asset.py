"""Assets. Mirrors ``io.metaloom.loom.client.common.method.AssetMethods``."""

from __future__ import annotations

import uuid as _uuid_mod
from typing import TYPE_CHECKING

from ..assets import AssetId
from ..models.asset import (
    AssetBulkCreateRequest,
    AssetBulkResponse,
    AssetBulkUpdateRequest,
    AssetCreateRequest,
    AssetListResponse,
    AssetResponse,
    AssetUpdateRequest,
)

if TYPE_CHECKING:
    from ..request import LoomRequest


class AssetMethods:
    """CRUD on ``/assets``, plus the bulk routes.

    Assets are addressable two ways, by UUID or by the SHA-512 of their binary. Every
    method here takes ``str``, ``uuid.UUID`` or :class:`~loom_client.assets.AssetId`
    and routes to ``/assets/{uuid}`` or ``/assets/sha512/{sha512}`` accordingly::

        client.load_asset("3f1b2c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d")   # by UUID
        client.load_asset("cf83e1357eefb8bd" + "...")               # by SHA-512

    Java models this as overloads taking ``UUID`` or ``SHA512``; Python has no
    overloading, so the single parameter accepts both.
    """

    def load_asset(self, asset_id: AssetId | _uuid_mod.UUID | str) -> LoomRequest[AssetResponse]:
        """Load a single asset, by UUID or SHA-512."""
        return self._get(self._asset(asset_id), AssetResponse)

    def create_asset(self, request: AssetCreateRequest) -> LoomRequest[AssetResponse]:
        """Create an asset record.

        This registers metadata only. To send bytes, use
        :meth:`~loom_client.methods.asset_binary.AssetBinaryMethods.upload_asset`.
        """
        return self._post("assets", request, AssetResponse)

    def update_asset(
        self, asset_id: AssetId | _uuid_mod.UUID | str, request: AssetUpdateRequest
    ) -> LoomRequest[AssetResponse]:
        """Update an asset. Only the fields you set are changed."""
        return self._post(self._asset(asset_id), request, AssetResponse)

    def patch_asset(
        self, asset_id: AssetId | _uuid_mod.UUID | str, request: AssetUpdateRequest
    ) -> LoomRequest[AssetResponse]:
        """Partially update an asset. Equivalent to :meth:`update_asset`."""
        return self._patch(self._asset(asset_id), request, AssetResponse)

    def replace_asset(
        self, asset_id: AssetId | _uuid_mod.UUID | str, request: AssetUpdateRequest
    ) -> LoomRequest[AssetResponse]:
        """Fully replace an asset.

        PUT requires every replaceable property to be present in the body and answers
        400 naming the ones you left out, so load the asset, change what you need and
        send the whole thing back. A handful of fields -- ``image``, ``video``,
        ``audio``, ``document``, ``geo``, ``timeline``, ``s3``, ``consistency`` and
        ``fingerprint`` -- are exempt from that check.
        """
        return self._put(self._asset(asset_id), request, AssetResponse)

    def delete_asset(self, asset_id: AssetId | _uuid_mod.UUID | str) -> LoomRequest[None]:
        """Delete an asset. Answers 204 with no body."""
        return self._delete(self._asset(asset_id))

    def list_assets(self) -> LoomRequest[AssetListResponse]:
        """List assets. Supports ``limit``, ``from_``, ``filter``, ``sort`` and ``iter``."""
        return self._get("assets", AssetListResponse)

    def bulk_create_assets(self, request: AssetBulkCreateRequest) -> LoomRequest[AssetBulkResponse]:
        """Create many assets in one request.

        The response reports per-item status, so a partial failure does not fail the
        whole call -- check each entry rather than only the HTTP status.
        """
        return self._post("assets/bulk/create", request, AssetBulkResponse)

    def bulk_update_assets(self, request: AssetBulkUpdateRequest) -> LoomRequest[AssetBulkResponse]:
        """Update many assets in one request. Reports per-item status, as with create."""
        return self._post("assets/bulk/update", request, AssetBulkResponse)
