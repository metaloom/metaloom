"""Fingerprint components. Mirrors ``io.metaloom.loom.client.common.method.FingerprintCompMethods``."""

from __future__ import annotations

import uuid as _uuid_mod
from typing import TYPE_CHECKING

from ..models.fingerprintcomp import (
    FingerprintCompCreateRequest,
    FingerprintCompListResponse,
    FingerprintCompResponse,
)

if TYPE_CHECKING:
    from ..request import LoomRequest


class FingerprintCompMethods:
    """Perceptual fingerprints under ``/assets/{uuid}/fingerprints``.

    Used to find near-duplicates that differ in encoding, resolution or crop."""

    def create_asset_fingerprint_comp(
        self, asset_uuid: _uuid_mod.UUID | str, request: FingerprintCompCreateRequest
    ) -> LoomRequest[FingerprintCompResponse]:
        """Attach a perceptual fingerprint to an asset."""
        return self._post(f"assets/{self._uuid(asset_uuid)}/fingerprints", request, FingerprintCompResponse)

    def load_asset_fingerprint_comp(
        self, asset_uuid: _uuid_mod.UUID | str, comp_uuid: _uuid_mod.UUID | str
    ) -> LoomRequest[FingerprintCompResponse]:
        """Load a single fingerprint of an asset."""
        return self._get(
            f"assets/{self._uuid(asset_uuid)}/fingerprints/{self._uuid(comp_uuid)}", FingerprintCompResponse
        )

    def list_asset_fingerprint_comps(
        self, asset_uuid: _uuid_mod.UUID | str
    ) -> LoomRequest[FingerprintCompListResponse]:
        """List an asset's fingerprints."""
        return self._get(f"assets/{self._uuid(asset_uuid)}/fingerprints", FingerprintCompListResponse)

    def delete_asset_fingerprint_comp(
        self, asset_uuid: _uuid_mod.UUID | str, comp_uuid: _uuid_mod.UUID | str
    ) -> LoomRequest[None]:
        """Delete a fingerprint. Answers 204 with no body."""
        return self._delete(f"assets/{self._uuid(asset_uuid)}/fingerprints/{self._uuid(comp_uuid)}")
