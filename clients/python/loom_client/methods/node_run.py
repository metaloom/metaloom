"""Ad-hoc node execution. Mirrors ``io.metaloom.loom.client.common.method.NodeRunMethods``."""

from __future__ import annotations

import uuid as _uuid_mod
from typing import TYPE_CHECKING

from ..models.base import GenericMessageResponse
from ..models.noderun import (
    NodeProbeRequest,
    NodeProbeResponse,
    NodeRunListResponse,
    NodeRunRequest,
    NodeRunResponse,
    NodeRunStatusResponse,
)

if TYPE_CHECKING:
    from ..request import LoomRequest


class NodeRunMethods:
    """Running processing nodes on chosen assets without a stored pipeline.

    Two shapes, because the work has two shapes. :meth:`probe_node` runs one node
    against one asset and answers with the result. :meth:`start_node_run` takes a
    graph and a set of assets and answers with a handle, because a pass over two
    hundred images does not fit in a request.

    Every method requires ``EXECUTE_MCP_NODE`` and is scoped to the caller: an
    ad-hoc run belongs to whoever started it and is not reachable through the
    pipeline routes."""

    def probe_node(self, request: NodeProbeRequest) -> LoomRequest[NodeProbeResponse]:
        """Run a single node against a single asset and wait for the result.

        A node that cannot be run reports why in ``message`` rather than failing the
        request -- the request was well formed, the answer is just "not this node"."""
        return self._post("node-runs/probes", request, NodeProbeResponse)

    def start_node_run(self, request: NodeRunRequest) -> LoomRequest[NodeRunResponse]:
        """Start an ad-hoc node run and get a handle back immediately.

        The work continues after the call returns; poll it with :meth:`load_node_run`."""
        return self._post("node-runs", request, NodeRunResponse)

    def list_node_runs(self) -> LoomRequest[NodeRunListResponse]:
        """List the caller's own ad-hoc node runs, newest first."""
        return self._get("node-runs", NodeRunListResponse)

    def load_node_run(self, run_uuid: _uuid_mod.UUID | str) -> LoomRequest[NodeRunStatusResponse]:
        """Load the status and per-item results of one of the caller's ad-hoc node runs."""
        return self._get(f"node-runs/{self._uuid(run_uuid)}", NodeRunStatusResponse)

    def cancel_node_run(self, run_uuid: _uuid_mod.UUID | str) -> LoomRequest[GenericMessageResponse]:
        """Stop one of the caller's ad-hoc node runs.

        Nodes already running on a worker finish -- the dispatcher has no reverse
        signal -- but nothing further is started."""
        return self._post(f"node-runs/{self._uuid(run_uuid)}/cancel", None, GenericMessageResponse)
