"""Spaces. Mirrors ``io.metaloom.loom.client.common.method.SpaceMethods``."""

from __future__ import annotations

import uuid as _uuid_mod
from typing import TYPE_CHECKING

from ..models.space import (
    SpaceCreateRequest,
    SpaceListResponse,
    SpaceResponse,
    SpaceUpdateRequest,
)

if TYPE_CHECKING:
    from ..request import LoomRequest


class SpaceMethods:
    """CRUD on ``/spaces``.

    A space is a tenant-level partition of the instance."""

    def load_space(self, space_uuid: _uuid_mod.UUID | str) -> LoomRequest[SpaceResponse]:
        """Load a single space."""
        return self._get(f"spaces/{self._uuid(space_uuid)}", SpaceResponse)

    def create_space(self, request: SpaceCreateRequest) -> LoomRequest[SpaceResponse]:
        """Create a space."""
        return self._post("spaces", request, SpaceResponse)

    def update_space(
        self, space_uuid: _uuid_mod.UUID | str, request: SpaceUpdateRequest
    ) -> LoomRequest[SpaceResponse]:
        """Update a space. Only the fields you set are changed."""
        return self._post(f"spaces/{self._uuid(space_uuid)}", request, SpaceResponse)

    def list_spaces(self) -> LoomRequest[SpaceListResponse]:
        """List spaces. Supports ``limit``, ``from_``, ``filter``, ``sort`` and ``iter``."""
        return self._get("spaces", SpaceListResponse)

    def delete_space(self, space_uuid: _uuid_mod.UUID | str) -> LoomRequest[None]:
        """Delete a space. Answers 204 with no body."""
        return self._delete(f"spaces/{self._uuid(space_uuid)}")
