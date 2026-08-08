"""Clusters. Mirrors ``io.metaloom.loom.client.common.method.ClusterMethods``."""

from __future__ import annotations

import uuid as _uuid_mod
from typing import TYPE_CHECKING

from ..assets import AssetId
from ..models.cluster import (
    ClusterBulkCreateRequest,
    ClusterBulkResponse,
    ClusterConfirmRequest,
    ClusterCreateRequest,
    ClusterListResponse,
    ClusterMemberListResponse,
    ClusterResponse,
    ClusterUpdateRequest,
)

if TYPE_CHECKING:
    from ..request import LoomRequest


class ClusterMethods:
    """CRUD on ``/clusters``, plus the face review loop.

    A cluster groups embeddings that a producer decided belong to one subject. A
    ``type='face'`` cluster is proposed per asset by the facedetect node with status
    ``PENDING``; a human then confirms it into a person or rejects it."""

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

    def list_clusters(
        self, status: str | None = None, type: str | None = None
    ) -> LoomRequest[ClusterListResponse]:
        """List clusters, optionally filtered to the review queue.

        Supports ``limit``, ``from_``, ``filter``, ``sort`` and ``iter``.

        Args:
            status: ``PENDING``, ``CONFIRMED`` or ``REJECTED``. Omitted when ``None``.
            type: Cluster type, e.g. ``face``. Omitted when ``None``.
        """
        request = self._get("clusters", ClusterListResponse)
        if status is not None:
            request.param("status", status)
        if type is not None:
            request.param("type", type)
        return request

    def delete_cluster(self, cluster_uuid: _uuid_mod.UUID | str) -> LoomRequest[None]:
        """Delete a cluster. Answers 204 with no body."""
        return self._delete(f"clusters/{self._uuid(cluster_uuid)}")

    def list_cluster_members(
        self, cluster_uuid: _uuid_mod.UUID | str
    ) -> LoomRequest[ClusterMemberListResponse]:
        """List the embeddings in a cluster.

        Each member carries the detection it came from and that detection's bounding
        box, so a face crop can be addressed without a second request per member.
        """
        return self._get(
            f"clusters/{self._uuid(cluster_uuid)}/members", ClusterMemberListResponse
        )

    def confirm_cluster(
        self, cluster_uuid: _uuid_mod.UUID | str, request: ClusterConfirmRequest
    ) -> LoomRequest[ClusterResponse]:
        """Confirm that a cluster is a person.

        Set ``person_uuid`` to link somebody already known, or leave it unset and give
        a name to create them. The second form additionally requires the
        ``CREATE_PERSON`` permission.
        """
        return self._post(
            f"clusters/{self._uuid(cluster_uuid)}/confirm", request, ClusterResponse
        )

    def reject_cluster(self, cluster_uuid: _uuid_mod.UUID | str) -> LoomRequest[ClusterResponse]:
        """Reject a cluster: it is not a subject worth keeping.

        The person pointer is left as it was, so an earlier verdict stays readable.
        """
        return self._post(f"clusters/{self._uuid(cluster_uuid)}/reject", None, ClusterResponse)

    def list_asset_clusters(
        self, asset_id: AssetId | _uuid_mod.UUID | str
    ) -> LoomRequest[ClusterListResponse]:
        """List the clusters computed within one asset."""
        return self._get(f"{self._asset_sub(asset_id)}/clusters", ClusterListResponse)

    def bulk_create_asset_clusters(
        self, asset_id: AssetId | _uuid_mod.UUID | str, request: ClusterBulkCreateRequest
    ) -> LoomRequest[ClusterBulkResponse]:
        """Write every cluster a producer found in one asset.

        Idempotent on ``(asset, node_kind, cluster_index)``: re-running the producing
        node rewrites its own proposals and never overwrites a review verdict recorded
        against them. Proposals the producer no longer makes are retired unless
        ``prune_stale`` is set to ``False``.
        """
        return self._post(
            f"{self._asset_sub(asset_id)}/clusters/bulk", request, ClusterBulkResponse
        )
