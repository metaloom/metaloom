"""Remixes. Mirrors ``io.metaloom.loom.client.common.method.RemixMethods``."""

from __future__ import annotations

import uuid as _uuid_mod
from typing import TYPE_CHECKING, Sequence

from ..models.remix import (
    RemixCreateRequest,
    RemixListResponse,
    RemixMemberListResponse,
    RemixMemberRequest,
    RemixResponse,
    RemixUpdateRequest,
)

if TYPE_CHECKING:
    from ..request import LoomRequest


class RemixMethods:
    """CRUD on ``/remixes``.

    A remix is a named group of assets that are versions of one another -- an original
    plus the cuts, re-encodes and edits made from it. It is not deduplication (identical
    bytes are one asset, by sha512) and not similarity search; it records a decision
    somebody made."""

    def load_remix(self, remix_uuid: _uuid_mod.UUID | str) -> LoomRequest[RemixResponse]:
        """Load a single remix, with its member count."""
        return self._get(f"remixes/{self._uuid(remix_uuid)}", RemixResponse)

    def create_remix(self, request: RemixCreateRequest) -> LoomRequest[RemixResponse]:
        """Create a remix.

        The request may carry its members, so "combine these into a remix" is one call
        rather than a create followed by an add that can fail on its own."""
        return self._post("remixes", request, RemixResponse)

    def update_remix(
        self, remix_uuid: _uuid_mod.UUID | str, request: RemixUpdateRequest
    ) -> LoomRequest[RemixResponse]:
        """Update a remix. Only the fields you set are changed.

        Membership is changed through :meth:`add_remix_assets` and
        :meth:`remove_remix_asset`, not here."""
        return self._post(f"remixes/{self._uuid(remix_uuid)}", request, RemixResponse)

    def list_remixes(self) -> LoomRequest[RemixListResponse]:
        """List remixes. Supports ``limit``, ``from_``, ``filter``, ``sort`` and ``iter``."""
        return self._get("remixes", RemixListResponse)

    def delete_remix(self, remix_uuid: _uuid_mod.UUID | str) -> LoomRequest[None]:
        """Delete a remix. Answers 204 with no body. The assets in it are not affected."""
        return self._delete(f"remixes/{self._uuid(remix_uuid)}")

    def add_remix_assets(
        self,
        remix_uuid: _uuid_mod.UUID | str,
        request: RemixMemberRequest | Sequence[_uuid_mod.UUID | str],
    ) -> LoomRequest[RemixResponse]:
        """Add one or more assets to the remix.

        Adding an asset that is already a member rewrites its membership rather than
        failing, so a re-submitted selection needs no diffing.

        Accepts either a request object or a plain sequence of asset uuids."""
        if not isinstance(request, RemixMemberRequest):
            request = RemixMemberRequest(asset_uuids=[str(u) for u in request])
        return self._post(f"remixes/{self._uuid(remix_uuid)}/assets", request, RemixResponse)

    def remove_remix_asset(
        self, remix_uuid: _uuid_mod.UUID | str, asset_uuid: _uuid_mod.UUID | str
    ) -> LoomRequest[None]:
        """Remove an asset from the remix. Answers 404 if it is not a member."""
        return self._delete(f"remixes/{self._uuid(remix_uuid)}/assets/{self._uuid(asset_uuid)}")

    def list_remix_assets(self, remix_uuid: _uuid_mod.UUID | str) -> LoomRequest[RemixMemberListResponse]:
        """List the remix's members, in insertion order.

        Each member carries the asset's filename, mime type, hash and size, so a caller
        rendering a list does not need a second request per member. Requires READ_ASSET
        as well as READ_REMIX."""
        return self._get(f"remixes/{self._uuid(remix_uuid)}/assets", RemixMemberListResponse)

    def set_remix_source(
        self, remix_uuid: _uuid_mod.UUID | str, asset_uuid: _uuid_mod.UUID | str
    ) -> LoomRequest[RemixResponse]:
        """Make a member asset the source of the remix.

        The previous source is demoted in the same transaction. Naming an asset that is
        not a member is a 400."""
        request = RemixMemberRequest(source_asset_uuid=str(asset_uuid))
        return self._post(f"remixes/{self._uuid(remix_uuid)}/source", request, RemixResponse)

    def list_asset_remixes(self, asset_uuid: _uuid_mod.UUID | str) -> LoomRequest[RemixListResponse]:
        """List the remixes the asset belongs to."""
        return self._get(f"assets/{self._uuid(asset_uuid)}/remixes", RemixListResponse)
