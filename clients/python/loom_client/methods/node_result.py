"""Node results. Mirrors ``io.metaloom.loom.client.common.method.NodeResultMethods``."""

from __future__ import annotations

import uuid as _uuid_mod
from typing import TYPE_CHECKING

from ..models.noderesult import (
    NodeResultCreateRequest,
    NodeResultListResponse,
    NodeResultResponse,
)

if TYPE_CHECKING:
    from ..request import LoomRequest


class NodeResultMethods:
    """The processing ledger under ``/assets/{uuid}/node-results``.

    One entry per node run, recording that a node processed this asset and how it
    ended. Nodes write here alongside the typed component they produce, so the
    pipeline can tell what has already been done."""

    def create_asset_node_result(
        self, asset_uuid: _uuid_mod.UUID | str, request: NodeResultCreateRequest
    ) -> LoomRequest[NodeResultResponse]:
        """Record the outcome of a processing node run against an asset."""
        return self._post(f"assets/{self._uuid(asset_uuid)}/node-results", request, NodeResultResponse)

    def load_asset_node_result(
        self, asset_uuid: _uuid_mod.UUID | str, node_result_uuid: _uuid_mod.UUID | str
    ) -> LoomRequest[NodeResultResponse]:
        """Load a single node result of an asset."""
        return self._get(
            f"assets/{self._uuid(asset_uuid)}/node-results/{self._uuid(node_result_uuid)}", NodeResultResponse
        )

    def list_asset_node_results(
        self, asset_uuid: _uuid_mod.UUID | str
    ) -> LoomRequest[NodeResultListResponse]:
        """List an asset's node results."""
        return self._get(f"assets/{self._uuid(asset_uuid)}/node-results", NodeResultListResponse)

    def delete_asset_node_result(
        self, asset_uuid: _uuid_mod.UUID | str, node_result_uuid: _uuid_mod.UUID | str
    ) -> LoomRequest[None]:
        """Delete a node result. Answers 204 with no body."""
        return self._delete(f"assets/{self._uuid(asset_uuid)}/node-results/{self._uuid(node_result_uuid)}")
