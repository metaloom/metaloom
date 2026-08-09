"""Collections. Mirrors ``io.metaloom.loom.client.common.method.CollectionMethods``."""

from __future__ import annotations

import uuid as _uuid_mod
from typing import TYPE_CHECKING

from ..models.asset import AssetListResponse
from ..models.collection import (
    CollectionAssetBulkRequest,
    CollectionAssetBulkResponse,
    CollectionAssetRequest,
    CollectionCreateRequest,
    CollectionListResponse,
    CollectionResponse,
    CollectionUpdateRequest,
)

if TYPE_CHECKING:
    from ..request import LoomRequest


class CollectionMethods:
    """CRUD on ``/collections``.

    A collection is a user-curated set of assets."""

    def load_collection(self, collection_uuid: _uuid_mod.UUID | str) -> LoomRequest[CollectionResponse]:
        """Load a single collection."""
        return self._get(f"collections/{self._uuid(collection_uuid)}", CollectionResponse)

    def create_collection(self, request: CollectionCreateRequest) -> LoomRequest[CollectionResponse]:
        """Create a collection."""
        return self._post("collections", request, CollectionResponse)

    def update_collection(
        self, collection_uuid: _uuid_mod.UUID | str, request: CollectionUpdateRequest
    ) -> LoomRequest[CollectionResponse]:
        """Update a collection. Only the fields you set are changed."""
        return self._post(f"collections/{self._uuid(collection_uuid)}", request, CollectionResponse)

    def list_collections(self) -> LoomRequest[CollectionListResponse]:
        """List collections. Supports ``limit``, ``from_``, ``filter``, ``sort`` and ``iter``."""
        return self._get("collections", CollectionListResponse)

    def delete_collection(self, collection_uuid: _uuid_mod.UUID | str) -> LoomRequest[None]:
        """Delete a collection. Answers 204 with no body."""
        return self._delete(f"collections/{self._uuid(collection_uuid)}")

    def add_collection_asset(
        self, collection_uuid: _uuid_mod.UUID | str, request: CollectionAssetRequest | _uuid_mod.UUID | str
    ) -> LoomRequest[CollectionResponse]:
        """Add an asset to the collection.

        Answers 201 when the asset became a new member and 200 when it already was one --
        both successes. Membership is a set, so re-adding is a no-op rather than an error.

        Accepts either a request object or a bare asset uuid."""
        if not isinstance(request, CollectionAssetRequest):
            request = CollectionAssetRequest(asset_uuid=str(request))
        return self._post(f"collections/{self._uuid(collection_uuid)}/assets", request, CollectionResponse)

    def add_collection_assets(
        self, collection_uuid: _uuid_mod.UUID | str, request: CollectionAssetBulkRequest
    ) -> LoomRequest[CollectionAssetBulkResponse]:
        """Add several assets to the collection in one call.

        An asset uuid that names nothing is counted in ``failed``; the rest are still linked."""
        return self._put(f"collections/{self._uuid(collection_uuid)}/assets", request, CollectionAssetBulkResponse)

    def remove_collection_asset(
        self, collection_uuid: _uuid_mod.UUID | str, asset_uuid: _uuid_mod.UUID | str
    ) -> LoomRequest[None]:
        """Remove an asset from the collection. Answers 204 with no body."""
        return self._delete(f"collections/{self._uuid(collection_uuid)}/assets/{self._uuid(asset_uuid)}")

    def list_collection_assets(self, collection_uuid: _uuid_mod.UUID | str) -> LoomRequest[AssetListResponse]:
        """List the assets in the collection. Supports ``limit``, ``from_`` and ``iter``."""
        return self._get(f"collections/{self._uuid(collection_uuid)}/assets", AssetListResponse)

    def list_asset_collections(self, asset_uuid: _uuid_mod.UUID | str) -> LoomRequest[CollectionListResponse]:
        """List the collections the asset belongs to."""
        return self._get(f"assets/{self._uuid(asset_uuid)}/collections", CollectionListResponse)
