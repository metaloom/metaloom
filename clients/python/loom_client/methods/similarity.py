"""Similarity. Mirrors ``io.metaloom.loom.client.common.method.SimilarityMethods``."""

from __future__ import annotations

import uuid as _uuid_mod
from typing import TYPE_CHECKING

from ..models.similarity import SimilarAssetListResponse

if TYPE_CHECKING:
    from ..request import LoomRequest


class SimilarityMethods:
    """Nearest-neighbour lookup over asset embeddings."""

    def list_similar_assets(
        self,
        asset_uuid: _uuid_mod.UUID | str,
        algorithm: str | None = None,
        limit: int | None = None,
        threshold: float | None = None,
    ) -> LoomRequest[SimilarAssetListResponse]:
        """Find assets similar to this one.

        Args:
            asset_uuid: The asset to search from.
            algorithm: Similarity algorithm to use. Server default when omitted.
            limit: Maximum number of neighbours to return.
            threshold: Minimum similarity score, between 0 and 1.

        Every argument except the asset is omitted from the query string when ``None``,
        so the server's own defaults apply.
        """
        request = self._get(f"assets/{self._uuid(asset_uuid)}/similar-assets", SimilarAssetListResponse)
        if algorithm is not None:
            request.param("algorithm", algorithm)
        if limit is not None:
            request.param("limit", limit)
        if threshold is not None:
            request.param("threshold", threshold)
        return request

    def rebuild_similarity_index(self) -> LoomRequest[None]:
        """Rebuild the similarity index from scratch.

        Expensive on a large instance, and asynchronous -- the response only confirms
        the rebuild was accepted. Takes no body.
        """
        return self._post_empty("similarity-index/rebuild")
