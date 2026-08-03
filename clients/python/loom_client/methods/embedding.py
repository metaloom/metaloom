"""Embeddings. Mirrors ``io.metaloom.loom.client.common.method.EmbeddingMethods``."""

from __future__ import annotations

import uuid as _uuid_mod
from typing import TYPE_CHECKING

from ..models.embedding import (
    EmbeddingCreateRequest,
    EmbeddingListResponse,
    EmbeddingResponse,
    EmbeddingUpdateRequest,
)

if TYPE_CHECKING:
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
