"""Groups. Mirrors ``io.metaloom.loom.client.common.method.GroupMethods``."""

from __future__ import annotations

import uuid as _uuid_mod
from typing import TYPE_CHECKING

from ..models.group import (
    GroupCreateRequest,
    GroupListResponse,
    GroupResponse,
    GroupUpdateRequest,
)

if TYPE_CHECKING:
    from ..request import LoomRequest


class GroupMethods:
    """CRUD on ``/groups``.

    Groups are how permissions reach users: a user joins a group, the group holds a
    role, the role carries the permissions. Granting a permission directly to a user
    is possible but limited to one, so the group route is the one to use.

    Groups are one of the few resources that accept PATCH and PUT alongside the
    POST-to-update convention.
    """

    def load_group(self, group_uuid: _uuid_mod.UUID | str) -> LoomRequest[GroupResponse]:
        """Load a single group."""
        return self._get(f"groups/{self._uuid(group_uuid)}", GroupResponse)

    def create_group(self, request: GroupCreateRequest) -> LoomRequest[GroupResponse]:
        """Create a group."""
        return self._post("groups", request, GroupResponse)

    def update_group(
        self, group_uuid: _uuid_mod.UUID | str, request: GroupUpdateRequest
    ) -> LoomRequest[GroupResponse]:
        """Update a group. Only the fields you set are changed."""
        return self._post(f"groups/{self._uuid(group_uuid)}", request, GroupResponse)

    def patch_group(
        self, group_uuid: _uuid_mod.UUID | str, request: GroupUpdateRequest
    ) -> LoomRequest[GroupResponse]:
        """Partially update a group. Equivalent to :meth:`update_group`."""
        return self._patch(f"groups/{self._uuid(group_uuid)}", request, GroupResponse)

    def replace_group(
        self, group_uuid: _uuid_mod.UUID | str, request: GroupUpdateRequest
    ) -> LoomRequest[GroupResponse]:
        """Fully replace a group.

        PUT requires every replaceable property to be present in the body and answers
        400 naming the ones you left out. Load the group, change what you need and send
        the whole thing back.
        """
        return self._put(f"groups/{self._uuid(group_uuid)}", request, GroupResponse)

    def list_groups(self) -> LoomRequest[GroupListResponse]:
        """List groups. Supports ``limit``, ``from_``, ``filter``, ``sort`` and ``iter``."""
        return self._get("groups", GroupListResponse)

    def delete_group(self, group_uuid: _uuid_mod.UUID | str) -> LoomRequest[None]:
        """Delete a group. Answers 204 with no body."""
        return self._delete(f"groups/{self._uuid(group_uuid)}")
