"""Annotations. Mirrors ``io.metaloom.loom.client.common.method.AnnotationMethods``."""

from __future__ import annotations

import uuid as _uuid_mod
from typing import TYPE_CHECKING

from ..models.annotation import (
    AnnotationCreateRequest,
    AnnotationListResponse,
    AnnotationResponse,
    AnnotationUpdateRequest,
)

if TYPE_CHECKING:
    from ..request import LoomRequest


class AnnotationMethods:
    """CRUD on ``/annotations``.

    An annotation marks a region or a moment in an asset. Comments and tasks can
    both be attached to one; see :class:`CommentMethods` and :class:`TaskMethods`."""

    def load_annotation(self, annotation_uuid: _uuid_mod.UUID | str) -> LoomRequest[AnnotationResponse]:
        """Load a single annotation."""
        return self._get(f"annotations/{self._uuid(annotation_uuid)}", AnnotationResponse)

    def create_annotation(self, request: AnnotationCreateRequest) -> LoomRequest[AnnotationResponse]:
        """Create a annotation."""
        return self._post("annotations", request, AnnotationResponse)

    def update_annotation(
        self, annotation_uuid: _uuid_mod.UUID | str, request: AnnotationUpdateRequest
    ) -> LoomRequest[AnnotationResponse]:
        """Update a annotation. Only the fields you set are changed."""
        return self._post(f"annotations/{self._uuid(annotation_uuid)}", request, AnnotationResponse)

    def list_annotations(self) -> LoomRequest[AnnotationListResponse]:
        """List annotations. Supports ``limit``, ``from_``, ``filter``, ``sort`` and ``iter``."""
        return self._get("annotations", AnnotationListResponse)

    def delete_annotation(self, annotation_uuid: _uuid_mod.UUID | str) -> LoomRequest[None]:
        """Delete a annotation. Answers 204 with no body."""
        return self._delete(f"annotations/{self._uuid(annotation_uuid)}")
