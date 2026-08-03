"""Roles. Mirrors ``io.metaloom.loom.client.common.method.RoleMethods``."""

from __future__ import annotations

import uuid as _uuid_mod
from typing import TYPE_CHECKING

from ..models.role import (
    RoleCreateRequest,
    RoleListResponse,
    RoleResponse,
    RoleUpdateRequest,
)

if TYPE_CHECKING:
    from ..request import LoomRequest


class RoleMethods:
    """CRUD on ``/roles``.

    A role is a named bundle of permissions. Permissions are granted to a user by
    putting them in a group that holds the role."""

    def load_role(self, role_uuid: _uuid_mod.UUID | str) -> LoomRequest[RoleResponse]:
        """Load a single role."""
        return self._get(f"roles/{self._uuid(role_uuid)}", RoleResponse)

    def create_role(self, request: RoleCreateRequest) -> LoomRequest[RoleResponse]:
        """Create a role."""
        return self._post("roles", request, RoleResponse)

    def update_role(
        self, role_uuid: _uuid_mod.UUID | str, request: RoleUpdateRequest
    ) -> LoomRequest[RoleResponse]:
        """Update a role. Only the fields you set are changed."""
        return self._post(f"roles/{self._uuid(role_uuid)}", request, RoleResponse)

    def list_roles(self) -> LoomRequest[RoleListResponse]:
        """List roles. Supports ``limit``, ``from_``, ``filter``, ``sort`` and ``iter``."""
        return self._get("roles", RoleListResponse)

    def delete_role(self, role_uuid: _uuid_mod.UUID | str) -> LoomRequest[None]:
        """Delete a role. Answers 204 with no body."""
        return self._delete(f"roles/{self._uuid(role_uuid)}")
