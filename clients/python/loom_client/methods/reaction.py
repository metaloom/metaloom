"""Reactions. Mirrors ``io.metaloom.loom.client.common.method.ReactionMethods``."""

from __future__ import annotations

import uuid as _uuid_mod
from typing import TYPE_CHECKING

from ..assets import AssetId
from ..models.reaction import (
    ReactionCreateRequest,
    ReactionListResponse,
    ReactionResponse,
    ReactionUpdateRequest,
)

if TYPE_CHECKING:
    from ..request import LoomRequest


class ReactionMethods:
    """Emoji reactions on assets, tasks and comments.

    The same five operations exist three times over, once per parent type, because the
    routes are nested under the parent: ``/assets/{id}/reactions``,
    ``/tasks/{uuid}/reactions`` and ``/comments/{uuid}/reactions``.

    The list methods are named ``list_*_reaction`` rather than ``...reactions``, which
    mirrors the Java client -- the singular there is a naming slip that is kept for
    parity rather than silently corrected.
    """

    # -- assets -------------------------------------------------------------

    def load_asset_reaction(
        self, asset_id: AssetId | _uuid_mod.UUID | str, reaction_uuid: _uuid_mod.UUID | str
    ) -> LoomRequest[ReactionResponse]:
        """Load a single reaction on an asset."""
        return self._get(
            f"{self._asset_sub(asset_id)}/reactions/{self._uuid(reaction_uuid)}", ReactionResponse
        )

    def create_asset_reaction(
        self, asset_id: AssetId | _uuid_mod.UUID | str, request: ReactionCreateRequest
    ) -> LoomRequest[ReactionResponse]:
        """React to an asset."""
        return self._post(f"{self._asset_sub(asset_id)}/reactions", request, ReactionResponse)

    def update_asset_reaction(
        self,
        asset_id: AssetId | _uuid_mod.UUID | str,
        reaction_uuid: _uuid_mod.UUID | str,
        request: ReactionUpdateRequest,
    ) -> LoomRequest[ReactionResponse]:
        """Change a reaction on an asset."""
        return self._post(
            f"{self._asset_sub(asset_id)}/reactions/{self._uuid(reaction_uuid)}",
            request,
            ReactionResponse,
        )

    def list_asset_reaction(
        self, asset_id: AssetId | _uuid_mod.UUID | str
    ) -> LoomRequest[ReactionListResponse]:
        """List the reactions on an asset."""
        return self._get(f"{self._asset_sub(asset_id)}/reactions", ReactionListResponse)

    def delete_asset_reaction(
        self, asset_id: AssetId | _uuid_mod.UUID | str, reaction_uuid: _uuid_mod.UUID | str
    ) -> LoomRequest[None]:
        """Remove a reaction from an asset. Answers 204 with no body."""
        return self._delete(f"{self._asset_sub(asset_id)}/reactions/{self._uuid(reaction_uuid)}")

    # -- tasks --------------------------------------------------------------

    def load_task_reaction(
        self, task_uuid: _uuid_mod.UUID | str, reaction_uuid: _uuid_mod.UUID | str
    ) -> LoomRequest[ReactionResponse]:
        """Load a single reaction on a task."""
        return self._get(
            f"tasks/{self._uuid(task_uuid)}/reactions/{self._uuid(reaction_uuid)}",
            ReactionResponse,
        )

    def create_task_reaction(
        self, task_uuid: _uuid_mod.UUID | str, request: ReactionCreateRequest
    ) -> LoomRequest[ReactionResponse]:
        """React to a task."""
        return self._post(f"tasks/{self._uuid(task_uuid)}/reactions", request, ReactionResponse)

    def update_task_reaction(
        self,
        task_uuid: _uuid_mod.UUID | str,
        reaction_uuid: _uuid_mod.UUID | str,
        request: ReactionUpdateRequest,
    ) -> LoomRequest[ReactionResponse]:
        """Change a reaction on a task."""
        return self._post(
            f"tasks/{self._uuid(task_uuid)}/reactions/{self._uuid(reaction_uuid)}",
            request,
            ReactionResponse,
        )

    def list_task_reaction(self, task_uuid: _uuid_mod.UUID | str) -> LoomRequest[ReactionListResponse]:
        """List the reactions on a task."""
        return self._get(f"tasks/{self._uuid(task_uuid)}/reactions", ReactionListResponse)

    def delete_task_reaction(
        self, task_uuid: _uuid_mod.UUID | str, reaction_uuid: _uuid_mod.UUID | str
    ) -> LoomRequest[None]:
        """Remove a reaction from a task. Answers 204 with no body."""
        return self._delete(f"tasks/{self._uuid(task_uuid)}/reactions/{self._uuid(reaction_uuid)}")

    # -- comments -----------------------------------------------------------

    def load_comment_reaction(
        self, comment_uuid: _uuid_mod.UUID | str, reaction_uuid: _uuid_mod.UUID | str
    ) -> LoomRequest[ReactionResponse]:
        """Load a single reaction on a comment."""
        return self._get(
            f"comments/{self._uuid(comment_uuid)}/reactions/{self._uuid(reaction_uuid)}",
            ReactionResponse,
        )

    def create_comment_reaction(
        self, comment_uuid: _uuid_mod.UUID | str, request: ReactionCreateRequest
    ) -> LoomRequest[ReactionResponse]:
        """React to a comment."""
        return self._post(f"comments/{self._uuid(comment_uuid)}/reactions", request, ReactionResponse)

    def update_comment_reaction(
        self,
        comment_uuid: _uuid_mod.UUID | str,
        reaction_uuid: _uuid_mod.UUID | str,
        request: ReactionUpdateRequest,
    ) -> LoomRequest[ReactionResponse]:
        """Change a reaction on a comment."""
        return self._post(
            f"comments/{self._uuid(comment_uuid)}/reactions/{self._uuid(reaction_uuid)}",
            request,
            ReactionResponse,
        )

    def list_comment_reaction(self, comment_uuid: _uuid_mod.UUID | str) -> LoomRequest[ReactionListResponse]:
        """List the reactions on a comment."""
        return self._get(f"comments/{self._uuid(comment_uuid)}/reactions", ReactionListResponse)

    def delete_comment_reaction(
        self, comment_uuid: _uuid_mod.UUID | str, reaction_uuid: _uuid_mod.UUID | str
    ) -> LoomRequest[None]:
        """Remove a reaction from a comment. Answers 204 with no body."""
        return self._delete(f"comments/{self._uuid(comment_uuid)}/reactions/{self._uuid(reaction_uuid)}")
