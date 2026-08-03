"""Clusters. Mirrors ``io.metaloom.loom.client.common.method.ClusterMethods``."""

from __future__ import annotations

import uuid as _uuid_mod
from typing import TYPE_CHECKING

from ..models.cluster import (
    ClusterCreateRequest,
    ClusterListResponse,
    ClusterResponse,
    ClusterUpdateRequest,
)

if TYPE_CHECKING:
    from ..request import LoomRequest


class ClusterMethods:
    """CRUD on ``/clusters``.

    A cluster groups assets that a similarity pass decided belong together."""

    def load_cluster(self, cluster_uuid: _uuid_mod.UUID | str) -> LoomRequest[ClusterResponse]:
        """Load a single cluster."""
        return self._get(f"clusters/{self._uuid(cluster_uuid)}", ClusterResponse)

    def create_cluster(self, request: ClusterCreateRequest) -> LoomRequest[ClusterResponse]:
        """Create a cluster."""
        return self._post("clusters", request, ClusterResponse)

    def update_cluster(
        self, cluster_uuid: _uuid_mod.UUID | str, request: ClusterUpdateRequest
    ) -> LoomRequest[ClusterResponse]:
        """Update a cluster. Only the fields you set are changed."""
        return self._post(f"clusters/{self._uuid(cluster_uuid)}", request, ClusterResponse)

    def list_clusters(self) -> LoomRequest[ClusterListResponse]:
        """List clusters. Supports ``limit``, ``from_``, ``filter``, ``sort`` and ``iter``."""
        return self._get("clusters", ClusterListResponse)

    def delete_cluster(self, cluster_uuid: _uuid_mod.UUID | str) -> LoomRequest[None]:
        """Delete a cluster. Answers 204 with no body."""
        return self._delete(f"clusters/{self._uuid(cluster_uuid)}")
