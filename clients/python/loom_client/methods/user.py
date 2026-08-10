"""User methods. Mirrors ``io.metaloom.loom.client.common.method.UserMethods``."""

from __future__ import annotations

import uuid as _uuid_mod
from typing import TYPE_CHECKING

from ..models.user import (
    UserAvatarResponse,
    UserCreateRequest,
    UserListResponse,
    UserResponse,
    UserUpdateRequest,
)

if TYPE_CHECKING:
    from ..request import BinaryResponse, LoomRequest


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

    # --- The account picture ------------------------------------------------------
    #
    # Two sets of the same four routes. The ``/users/:uuid`` form is administrative and
    # needs READ_USER / UPDATE_USER; the ``my_`` form aims at whoever is signed in and
    # needs neither, which is why it exists at all -- UPDATE_USER is the permission to
    # edit anybody's account, and no ordinary user holds it.

    def load_user_avatar(self, user_uuid: _uuid_mod.UUID | str) -> LoomRequest[UserAvatarResponse]:
        """Load the metadata of a user's avatar picture. 404 when they have none."""
        return self._get(f"users/{self._uuid(user_uuid)}/avatar", UserAvatarResponse)

    def upload_user_avatar(
        self,
        user_uuid: _uuid_mod.UUID | str,
        file: str | bytes,
        mime_type: str | None = None,
        pool_uuid: _uuid_mod.UUID | str | None = None,
        *,
        filename: str | None = None,
    ) -> LoomRequest[UserAvatarResponse]:
        """Upload a user's avatar picture, replacing any previous one.

        An account has at most one picture -- a partial unique index enforces it -- so
        this replaces rather than appending to a gallery. That is where a user differs
        from a person, who accumulates images because face detection keeps finding them.

        Args:
            user_uuid: The account the picture belongs to.
            file: Path to the file, or its bytes. Bytes require ``filename``.
            mime_type: Content type; defaults to ``application/octet-stream``.
            pool_uuid: Storage pool for the bytes. Without one the picture lands in the
                deployment's default storage, since an account has no parent asset to
                inherit a pool from.
            filename: Overrides the name sent to the server.
        """
        return self._upload_multipart(
            f"users/{self._uuid(user_uuid)}/avatar",
            file,
            UserAvatarResponse,
            filename=filename,
            mime_type=mime_type,
            fields=(("poolUuid", str(pool_uuid) if pool_uuid else None),),
        )

    def download_user_avatar(self, user_uuid: _uuid_mod.UUID | str) -> LoomRequest[BinaryResponse]:
        """Download a user's avatar picture.

        The body streams, so close it -- see
        :meth:`~loom_client.methods.asset_binary.AssetBinaryMethods.download_asset_binary`.
        """
        return self._download(f"users/{self._uuid(user_uuid)}/avatar/data")

    def delete_user_avatar(self, user_uuid: _uuid_mod.UUID | str) -> LoomRequest[None]:
        """Delete a user's avatar picture. Answers 204 with no body.

        Leaves the account without a picture; it never deletes the account.
        """
        return self._delete(f"users/{self._uuid(user_uuid)}/avatar")

    def load_my_avatar(self) -> LoomRequest[UserAvatarResponse]:
        """Load your own avatar picture's metadata. 404 when you have none."""
        return self._get("me/avatar", UserAvatarResponse)

    def upload_my_avatar(
        self,
        file: str | bytes,
        mime_type: str | None = None,
        pool_uuid: _uuid_mod.UUID | str | None = None,
        *,
        filename: str | None = None,
    ) -> LoomRequest[UserAvatarResponse]:
        """Upload your own avatar picture, replacing any previous one.

        Requires no permission beyond being signed in. The URL that comes back is the
        ``/users/:uuid`` form, not ``/me``: it ends up in an ``<img src>`` that other
        people's browsers load too, where a self-relative URL would show each of them
        their own face.
        """
        return self._upload_multipart(
            "me/avatar",
            file,
            UserAvatarResponse,
            filename=filename,
            mime_type=mime_type,
            fields=(("poolUuid", str(pool_uuid) if pool_uuid else None),),
        )

    def download_my_avatar(self) -> LoomRequest[BinaryResponse]:
        """Download your own avatar picture. The body streams, so close it."""
        return self._download("me/avatar/data")

    def delete_my_avatar(self) -> LoomRequest[None]:
        """Delete your own avatar picture. Answers 204 with no body."""
        return self._delete("me/avatar")
