"""Libraries. Mirrors ``io.metaloom.loom.client.common.method.LibraryMethods``."""

from __future__ import annotations

import uuid as _uuid_mod
from typing import TYPE_CHECKING

from ..models.asset import AssetListResponse
from ..models.library import (
    LibraryAssetRequest,
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

    def add_library_asset(
        self, library_uuid: _uuid_mod.UUID | str, request: LibraryAssetRequest | _uuid_mod.UUID | str
    ) -> LoomRequest[LibraryResponse]:
        """Add an asset to the library.

        Writes the organizational membership only -- it neither creates nor moves a binary.
        Answers 201 for a new membership and 200 when the asset was already a member.

        Accepts either a request object or a bare asset uuid."""
        if not isinstance(request, LibraryAssetRequest):
            request = LibraryAssetRequest(asset_uuid=str(request))
        return self._post(f"libraries/{self._uuid(library_uuid)}/assets", request, LibraryResponse)

    def remove_library_asset(
        self, library_uuid: _uuid_mod.UUID | str, asset_uuid: _uuid_mod.UUID | str
    ) -> LoomRequest[None]:
        """Remove an asset from the library. Answers 204 with no body."""
        return self._delete(f"libraries/{self._uuid(library_uuid)}/assets/{self._uuid(asset_uuid)}")

    def list_library_assets(self, library_uuid: _uuid_mod.UUID | str) -> LoomRequest[AssetListResponse]:
        """List the assets in the library. Supports ``limit``, ``from_`` and ``iter``."""
        return self._get(f"libraries/{self._uuid(library_uuid)}/assets", AssetListResponse)

    def list_asset_libraries(self, asset_uuid: _uuid_mod.UUID | str) -> LoomRequest[LibraryListResponse]:
        """List the libraries the asset belongs to."""
        return self._get(f"assets/{self._uuid(asset_uuid)}/libraries", LibraryListResponse)
