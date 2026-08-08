"""Dedup groups. Mirrors ``io.metaloom.loom.client.common.method.DedupGroupMethods``."""

from __future__ import annotations

import uuid as _uuid_mod
from typing import TYPE_CHECKING

from ..models.dedup import (
    DedupGroupCreateRequest,
    DedupGroupListResponse,
    DedupGroupResponse,
    DedupGroupUpdateRequest,
)

if TYPE_CHECKING:
    from ..request import LoomRequest


class DedupGroupMethods:
    """Duplicate sets under ``/dedup-groups``.

    A dedup group collects assets a deduplication pass believes are the same content,
    and tracks the decision about what to do with them.

    Note that updates here use PATCH rather than the POST-to-update convention the rest
    of the API follows.
    """

    def load_dedup_group(self, group_uuid: _uuid_mod.UUID | str) -> LoomRequest[DedupGroupResponse]:
        """Load a single dedup group."""
        return self._get(f"dedup-groups/{self._uuid(group_uuid)}", DedupGroupResponse)

    def create_dedup_group(self, request: DedupGroupCreateRequest) -> LoomRequest[DedupGroupResponse]:
        """Create a dedup group."""
        return self._post("dedup-groups", request, DedupGroupResponse)

    def update_dedup_group(
        self, group_uuid: _uuid_mod.UUID | str, request: DedupGroupUpdateRequest
    ) -> LoomRequest[DedupGroupResponse]:
        """Update a dedup group. Sent as PATCH, unlike most update methods."""
        return self._patch(f"dedup-groups/{self._uuid(group_uuid)}", request, DedupGroupResponse)

    def list_dedup_groups(
        self,
        status: str | None = None,
        from_uuid: _uuid_mod.UUID | str | None = None,
        limit: int | None = None,
    ) -> LoomRequest[DedupGroupListResponse]:
        """List dedup groups, optionally filtered by resolution status.

        The route is keyset paged and caps at 25 rows by default, so a bare call
        returns a page rather than the whole collection.

        Args:
            status: Restrict to groups in this status. Omitted when ``None``.
            from_uuid: Seek cursor - the ``_metainfo.last_uuid`` of the previous page.
            limit: Page size. Omitted when ``None`` (server default).
        """
        request = self._get("dedup-groups", DedupGroupListResponse)
        if status is not None:
            request.param("status", status)
        if from_uuid is not None:
            request.param("from", str(from_uuid))
        if limit is not None:
            request.param("limit", str(limit))
        return request

    def delete_dedup_group(self, group_uuid: _uuid_mod.UUID | str) -> LoomRequest[None]:
        """Delete a dedup group. Answers 204 with no body."""
        return self._delete(f"dedup-groups/{self._uuid(group_uuid)}")

    def list_asset_dedup_groups(
        self, asset_uuid: _uuid_mod.UUID | str
    ) -> LoomRequest[DedupGroupListResponse]:
        """List the dedup groups an asset belongs to."""
        return self._get(f"assets/{self._uuid(asset_uuid)}/dedup-groups", DedupGroupListResponse)
