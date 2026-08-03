"""Collections. Mirrors ``io.metaloom.loom.client.common.method.CollectionMethods``."""

from __future__ import annotations

import uuid as _uuid_mod
from typing import TYPE_CHECKING

from ..models.collection import (
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
