"""Comments. Mirrors ``io.metaloom.loom.client.common.method.CommentMethods``."""

from __future__ import annotations

import uuid as _uuid_mod
from typing import TYPE_CHECKING

from ..models.comment import (
    CommentCreateRequest,
    CommentListResponse,
    CommentResponse,
    CommentUpdateRequest,
)

if TYPE_CHECKING:
    from ..request import LoomRequest


class CommentMethods:
    """CRUD on ``/comments``, plus the routes nested under annotations and tasks.

    A comment can be created standalone or directly on an annotation or a task; the
    nested routes do both in one call.
    """

    def load_comment(self, comment_uuid: _uuid_mod.UUID | str) -> LoomRequest[CommentResponse]:
        """Load a single comment."""
        return self._get(f"comments/{self._uuid(comment_uuid)}", CommentResponse)

    def create_comment(self, request: CommentCreateRequest) -> LoomRequest[CommentResponse]:
        """Create a comment."""
        return self._post("comments", request, CommentResponse)

    def update_comment(
        self, comment_uuid: _uuid_mod.UUID | str, request: CommentUpdateRequest
    ) -> LoomRequest[CommentResponse]:
        """Update a comment. Only the fields you set are changed."""
        return self._post(f"comments/{self._uuid(comment_uuid)}", request, CommentResponse)

    def list_comments(self) -> LoomRequest[CommentListResponse]:
        """List comments. Supports ``limit``, ``from_``, ``filter``, ``sort`` and ``iter``."""
        return self._get("comments", CommentListResponse)

    def delete_comment(self, comment_uuid: _uuid_mod.UUID | str) -> LoomRequest[None]:
        """Delete a comment. Answers 204 with no body."""
        return self._delete(f"comments/{self._uuid(comment_uuid)}")

    # -- on annotations ------------------------------------------------------

    def create_annotation_comment(
        self, annotation_uuid: _uuid_mod.UUID | str, request: CommentCreateRequest
    ) -> LoomRequest[CommentResponse]:
        """Comment on an annotation."""
        return self._post(f"annotations/{self._uuid(annotation_uuid)}/comments", request, CommentResponse)

    def list_comments_for_annotation(
        self, annotation_uuid: _uuid_mod.UUID | str
    ) -> LoomRequest[CommentListResponse]:
        """List the comments on an annotation."""
        return self._get(f"annotations/{self._uuid(annotation_uuid)}/comments", CommentListResponse)

    # -- on tasks ------------------------------------------------------------

    def create_task_comment(
        self, task_uuid: _uuid_mod.UUID | str, request: CommentCreateRequest
    ) -> LoomRequest[CommentResponse]:
        """Comment on a task."""
        return self._post(f"tasks/{self._uuid(task_uuid)}/comments", request, CommentResponse)

    def list_task_comments(self, task_uuid: _uuid_mod.UUID | str) -> LoomRequest[CommentListResponse]:
        """List the comments on a task."""
        return self._get(f"tasks/{self._uuid(task_uuid)}/comments", CommentListResponse)
