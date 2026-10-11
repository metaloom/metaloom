"""Node output reuse. Mirrors ``io.metaloom.loom.client.common.method.NodeOutputMethods``."""

from __future__ import annotations

import uuid as _uuid_mod
from typing import TYPE_CHECKING

from ..assets import AssetId
from ..models.nodeoutput import (
    NodeOutputCreateRequest,
    NodeOutputListResponse,
    NodeOutputLookupResponse,
    NodeOutputResponse,
)

if TYPE_CHECKING:
    from ..request import LoomRequest


class NodeOutputMethods:
    """The reuse store under ``/assets/{uuid}/node-outputs``.

    What a node emitted for an asset, keyed by a hash of its options and inputs (the
    reuse key). A node looks its key up before it works and replays a stored output
    instead of computing; after computing it stores what it emitted. Forgetting the
    outputs of an asset makes those nodes compute again on the next run."""

    def lookup_node_output(
        self, asset_id: AssetId | _uuid_mod.UUID | str, node_kind: str, reuse_key: str
    ) -> LoomRequest[NodeOutputLookupResponse]:
        """Load the asset, by UUID or SHA-512, together with the stored output for a node kind and
        reuse key. ``output`` is ``None`` when the node has to compute."""
        return self._get(f"{self._asset(asset_id)}/node-outputs/{node_kind}/{reuse_key}", NodeOutputLookupResponse)

    def create_asset_node_output(
        self, asset_uuid: _uuid_mod.UUID | str, request: NodeOutputCreateRequest
    ) -> LoomRequest[NodeOutputResponse]:
        """Store what a node emitted. Storing the same node kind and reuse key again replaces it."""
        return self._post(f"assets/{self._uuid(asset_uuid)}/node-outputs", request, NodeOutputResponse)

    def list_asset_node_outputs(
        self, asset_uuid: _uuid_mod.UUID | str, node_kind: str | None = None
    ) -> LoomRequest[NodeOutputListResponse]:
        """List an asset's stored node outputs, optionally for one node kind."""
        request = self._get(f"assets/{self._uuid(asset_uuid)}/node-outputs", NodeOutputListResponse)
        if node_kind is not None:
            request.param("nodeKind", node_kind)
        return request

    def delete_asset_node_outputs(
        self, asset_uuid: _uuid_mod.UUID | str, node_kind: str | None = None
    ) -> LoomRequest[None]:
        """Forget an asset's stored node outputs, optionally for one node kind, so those nodes compute
        again. The typed results they wrote are untouched. Answers 204 with no body."""
        request = self._delete(f"assets/{self._uuid(asset_uuid)}/node-outputs")
        if node_kind is not None:
            request.param("nodeKind", node_kind)
        return request
