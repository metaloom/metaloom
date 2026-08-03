"""Asset binaries. Mirrors ``io.metaloom.loom.client.common.method.AssetBinaryMethods``."""

from __future__ import annotations

import uuid as _uuid_mod
from typing import TYPE_CHECKING

from ..models.asset import AssetResponse
from ..models.asset_binary import (
    AssetBinaryCreateRequest,
    AssetBinaryListResponse,
    AssetBinaryResponse,
    AssetBinaryUpdateRequest,
)

if TYPE_CHECKING:
    from ..request import LoomRequest
    from ..response import BinaryResponse


class AssetBinaryMethods:
    """Binary records under ``/binaries``, and the bytes themselves.

    A binary is the stored content an asset points at; several assets can share one,
    which is how deduplication works. ``/binaries`` manages the records,
    ``/assets/{uuid}/binary`` the one attached to a given asset, and
    ``/assets/{uuid}/binary/data`` the actual bytes.

    Uploads are buffered in memory, so very large files need
    ``loom_client.multipart.MAX_UPLOAD_BYTES`` raised. Downloads stream, and the
    returned object must be closed -- use it as a context manager.
    """

    # -- binary records ---------------------------------------------------

    def load_binary(self, binary_uuid: _uuid_mod.UUID | str) -> LoomRequest[AssetBinaryResponse]:
        """Load a single binary record."""
        return self._get(f"binaries/{self._uuid(binary_uuid)}", AssetBinaryResponse)

    def create_binary(self, request: AssetBinaryCreateRequest) -> LoomRequest[AssetBinaryResponse]:
        """Create a binary record."""
        return self._post("binaries", request, AssetBinaryResponse)

    def update_binary(
        self, binary_uuid: _uuid_mod.UUID | str, request: AssetBinaryUpdateRequest
    ) -> LoomRequest[AssetBinaryResponse]:
        """Update a binary record. Only the fields you set are changed."""
        return self._post(f"binaries/{self._uuid(binary_uuid)}", request, AssetBinaryResponse)

    def list_binaries(self) -> LoomRequest[AssetBinaryListResponse]:
        """List binary records. Supports ``limit``, ``from_``, ``filter``, ``sort`` and ``iter``."""
        return self._get("binaries", AssetBinaryListResponse)

    def delete_binary(self, binary_uuid: _uuid_mod.UUID | str) -> LoomRequest[None]:
        """Delete a binary record. Answers 204 with no body."""
        return self._delete(f"binaries/{self._uuid(binary_uuid)}")

    # -- the binary attached to an asset ----------------------------------

    def load_asset_binary(self, asset_uuid: _uuid_mod.UUID | str) -> LoomRequest[AssetBinaryResponse]:
        """Load the binary record attached to an asset."""
        return self._get(f"assets/{self._uuid(asset_uuid)}/binary", AssetBinaryResponse)

    def create_asset_binary(
        self, asset_uuid: _uuid_mod.UUID | str, request: AssetBinaryCreateRequest
    ) -> LoomRequest[AssetBinaryResponse]:
        """Attach a binary record to an asset."""
        return self._post(f"assets/{self._uuid(asset_uuid)}/binary", request, AssetBinaryResponse)

    def delete_asset_binary(self, asset_uuid: _uuid_mod.UUID | str) -> LoomRequest[None]:
        """Detach the binary record from an asset. Answers 204 with no body."""
        return self._delete(f"assets/{self._uuid(asset_uuid)}/binary")

    def list_asset_binaries(self, asset_uuid: _uuid_mod.UUID | str) -> LoomRequest[AssetBinaryListResponse]:
        """List every binary record associated with an asset."""
        return self._get(f"assets/{self._uuid(asset_uuid)}/binaries", AssetBinaryListResponse)

    # -- bytes -------------------------------------------------------------

    def upload_asset(
        self,
        file: str | bytes,
        library_uuid: _uuid_mod.UUID | str | None = None,
        pool_uuid: _uuid_mod.UUID | str | None = None,
        mime_type: str | None = None,
        *,
        filename: str | None = None,
    ) -> LoomRequest[AssetResponse]:
        """Create an asset by uploading a file.

        Args:
            file: Path to the file, or its bytes. Bytes require ``filename``.
            library_uuid: Library to ingest into.
            pool_uuid: Storage pool to place the binary in.
            mime_type: Content type; defaults to ``application/octet-stream``.
            filename: Overrides the name sent to the server.
        """
        return self._upload_multipart(
            "assets/upload",
            file,
            AssetResponse,
            filename=filename,
            mime_type=mime_type,
            fields=(
                ("libraryUuid", str(library_uuid) if library_uuid else None),
                ("poolUuid", str(pool_uuid) if pool_uuid else None),
            ),
        )

    def upload_asset_binary(
        self,
        asset_uuid: _uuid_mod.UUID | str,
        file: str | bytes,
        library_uuid: _uuid_mod.UUID | str | None = None,
        mime_type: str | None = None,
        *,
        filename: str | None = None,
    ) -> LoomRequest[AssetBinaryResponse]:
        """Upload the bytes for an asset that already exists.

        Args:
            asset_uuid: The asset to attach the bytes to.
            file: Path to the file, or its bytes. Bytes require ``filename``.
            library_uuid: Library the binary belongs to.
            mime_type: Content type; defaults to ``application/octet-stream``.
            filename: Overrides the name sent to the server.
        """
        return self._upload_multipart(
            f"assets/{self._uuid(asset_uuid)}/binary/data",
            file,
            AssetBinaryResponse,
            filename=filename,
            mime_type=mime_type,
            fields=(("libraryUuid", str(library_uuid) if library_uuid else None),),
        )

    def download_asset_binary(self, asset_uuid: _uuid_mod.UUID | str) -> LoomRequest[BinaryResponse]:
        """Download an asset's bytes.

        The body streams, so close it::

            with client.download_asset_binary(uuid).body() as binary:
                binary.save("/tmp/out.jpg")
        """
        return self._download(f"assets/{self._uuid(asset_uuid)}/binary/data")
