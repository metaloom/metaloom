"""User methods. Mirrors ``io.metaloom.loom.client.common.method.UserMethods``."""

from __future__ import annotations

import uuid as _uuid_mod
from typing import TYPE_CHECKING

from ..models.user import UserCreateRequest, UserListResponse, UserResponse, UserUpdateRequest

if TYPE_CHECKING:
    from ..request import LoomRequest


class UserMethods:
    """CRUD on ``/users``.

    Users are one of the few resources that accept PATCH and PUT in addition to the
    POST-to-update convention used everywhere in this API.
    """

    def load_user(self, user_uuid: _uuid_mod.UUID | str) -> LoomRequest[UserResponse]:
        """Load a single user."""
        return self._get(f"users/{self._uuid(user_uuid)}", UserResponse)

    def create_user(self, request: UserCreateRequest) -> LoomRequest[UserResponse]:
        """Create a user."""
        return self._post("users", request, UserResponse)

    def update_user(
        self, user_uuid: _uuid_mod.UUID | str, request: UserUpdateRequest
    ) -> LoomRequest[UserResponse]:
        """Update a user. Only the fields you set are changed.

        ``POST /users/:uuid`` is the update verb — that convention holds across the
        whole API, not just here.
        """
        return self._post(f"users/{self._uuid(user_uuid)}", request, UserResponse)

    def patch_user(
        self, user_uuid: _uuid_mod.UUID | str, request: UserUpdateRequest
    ) -> LoomRequest[UserResponse]:
        """Partially update a user. Equivalent to :meth:`update_user`."""
        return self._patch(f"users/{self._uuid(user_uuid)}", request, UserResponse)

    def replace_user(
        self, user_uuid: _uuid_mod.UUID | str, request: UserUpdateRequest
    ) -> LoomRequest[UserResponse]:
        """Fully replace a user.

        PUT requires every replaceable property to be present in the body; the server
        answers 400 naming the ones you left out. Load the user, change what you need
        and send the whole thing back.
        """
        return self._put(f"users/{self._uuid(user_uuid)}", request, UserResponse)

    def list_users(self) -> LoomRequest[UserListResponse]:
        """List users. Supports ``limit``, ``from_``, ``filter``, ``sort`` and ``iter``."""
        return self._get("users", UserListResponse)

    def delete_user(self, user_uuid: _uuid_mod.UUID | str) -> LoomRequest[None]:
        """Delete a user. Answers 204 with no body."""
        return self._delete(f"users/{self._uuid(user_uuid)}")
