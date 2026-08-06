"""Embeddings. Mirrors ``io.metaloom.loom.client.common.method.EmbeddingMethods``."""

from __future__ import annotations

import uuid as _uuid_mod
from typing import TYPE_CHECKING

from ..models.embedding import (
    EmbeddingBulkCreateRequest,
    EmbeddingBulkResponse,
    EmbeddingCreateRequest,
    EmbeddingListResponse,
    EmbeddingResponse,
    EmbeddingUpdateRequest,
)

if TYPE_CHECKING:
    from ..models.asset import AssetId
    from ..request import LoomRequest


class EmbeddingMethods:
    """CRUD on ``/embeddings``.

    An embedding is a feature vector produced by a model, used for similarity search."""

    def load_embedding(self, embedding_uuid: _uuid_mod.UUID | str) -> LoomRequest[EmbeddingResponse]:
        """Load a single embedding."""
        return self._get(f"embeddings/{self._uuid(embedding_uuid)}", EmbeddingResponse)

    def create_embedding(self, request: EmbeddingCreateRequest) -> LoomRequest[EmbeddingResponse]:
        """Create a embedding."""
        return self._post("embeddings", request, EmbeddingResponse)

    def update_embedding(
        self, embedding_uuid: _uuid_mod.UUID | str, request: EmbeddingUpdateRequest
    ) -> LoomRequest[EmbeddingResponse]:
        """Update a embedding. Only the fields you set are changed."""
        return self._post(f"embeddings/{self._uuid(embedding_uuid)}", request, EmbeddingResponse)

    def list_embeddings(self) -> LoomRequest[EmbeddingListResponse]:
        """List embeddings. Supports ``limit``, ``from_``, ``filter``, ``sort`` and ``iter``."""
        return self._get("embeddings", EmbeddingListResponse)

    def delete_embedding(self, embedding_uuid: _uuid_mod.UUID | str) -> LoomRequest[None]:
        """Delete a embedding. Answers 204 with no body."""
        return self._delete(f"embeddings/{self._uuid(embedding_uuid)}")

    def bulk_create_asset_embeddings(
        self, asset_id: AssetId | _uuid_mod.UUID | str, request: EmbeddingBulkCreateRequest
    ) -> LoomRequest[EmbeddingBulkResponse]:
        """Record many embeddings for one asset in a single request.

        Each item is upserted on its natural key -- (asset, node kind, type, model, frame,
        subject) -- so re-running a producer rewrites its own rows instead of appending
        duplicates, and raising ``model`` adds rows beside the old ones rather than
        replacing them.

        Pair it with :meth:`bulk_create_asset_detections`: write the detections first and
        carry the UUIDs it returns into each ``detection_uuid``, so every vector points at
        the region it was computed from.

        The response reports ``total``/``created``/``failed``, so check the counts rather
        than only the HTTP status.
        """
        return self._post(
            f"{self._asset_sub(asset_id)}/embeddings/bulk", request, EmbeddingBulkResponse
        )
