"""Libraries. Mirrors ``io.metaloom.loom.client.common.method.LibraryMethods``."""

from __future__ import annotations

import uuid as _uuid_mod
from typing import TYPE_CHECKING

from ..models.library import (
    LibraryCreateRequest,
    LibraryListResponse,
    LibraryResponse,
    LibraryUpdateRequest,
)

if TYPE_CHECKING:
    from ..request import LoomRequest


class LibraryMethods:
    """CRUD on ``/libraries``.

    A library is the top-level container assets are ingested into."""

    def load_library(self, library_uuid: _uuid_mod.UUID | str) -> LoomRequest[LibraryResponse]:
        """Load a single library."""
        return self._get(f"libraries/{self._uuid(library_uuid)}", LibraryResponse)

    def create_library(self, request: LibraryCreateRequest) -> LoomRequest[LibraryResponse]:
        """Create a library."""
        return self._post("libraries", request, LibraryResponse)

    def update_library(
        self, library_uuid: _uuid_mod.UUID | str, request: LibraryUpdateRequest
    ) -> LoomRequest[LibraryResponse]:
        """Update a library. Only the fields you set are changed."""
        return self._post(f"libraries/{self._uuid(library_uuid)}", request, LibraryResponse)

    def list_libraries(self) -> LoomRequest[LibraryListResponse]:
        """List libraries. Supports ``limit``, ``from_``, ``filter``, ``sort`` and ``iter``."""
        return self._get("libraries", LibraryListResponse)

    def delete_library(self, library_uuid: _uuid_mod.UUID | str) -> LoomRequest[None]:
        """Delete a library. Answers 204 with no body."""
        return self._delete(f"libraries/{self._uuid(library_uuid)}")
