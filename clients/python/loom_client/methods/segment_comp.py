"""Segment components. Mirrors ``io.metaloom.loom.client.common.method.SegmentCompMethods``."""

from __future__ import annotations

import uuid as _uuid_mod
from typing import TYPE_CHECKING

from ..models.segmentcomp import (
    SegmentCompCreateRequest,
    SegmentCompListResponse,
    SegmentCompResponse,
)

if TYPE_CHECKING:
    from ..request import LoomRequest


class SegmentCompMethods:
    """Time segments under ``/assets/{uuid}/segments``.

    Scene or shot boundaries in a video or audio asset. Creation is batched: one
    request carries many segments and the response lists them all."""

    def create_asset_segment_comps(
        self, asset_uuid: _uuid_mod.UUID | str, request: SegmentCompCreateRequest
    ) -> LoomRequest[SegmentCompListResponse]:
        """Attach time segments to an asset. Takes and returns a list."""
        return self._post(f"assets/{self._uuid(asset_uuid)}/segments", request, SegmentCompListResponse)

    def load_asset_segment_comp(
        self, asset_uuid: _uuid_mod.UUID | str, comp_uuid: _uuid_mod.UUID | str
    ) -> LoomRequest[SegmentCompResponse]:
        """Load a single segment of an asset."""
        return self._get(
            f"assets/{self._uuid(asset_uuid)}/segments/{self._uuid(comp_uuid)}", SegmentCompResponse
        )

    def list_asset_segment_comps(
        self, asset_uuid: _uuid_mod.UUID | str
    ) -> LoomRequest[SegmentCompListResponse]:
        """List an asset's segments."""
        return self._get(f"assets/{self._uuid(asset_uuid)}/segments", SegmentCompListResponse)

    def delete_asset_segment_comp(
        self, asset_uuid: _uuid_mod.UUID | str, comp_uuid: _uuid_mod.UUID | str
    ) -> LoomRequest[None]:
        """Delete a segment. Answers 204 with no body."""
        return self._delete(f"assets/{self._uuid(asset_uuid)}/segments/{self._uuid(comp_uuid)}")
