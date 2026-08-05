"""Tags. Mirrors ``io.metaloom.loom.client.common.method.TagMethods``."""

from __future__ import annotations

import uuid as _uuid_mod
from typing import TYPE_CHECKING

from ..assets import AssetId
from ..models.tag import (
    AssetTagBulkRequest,
    AssetTagBulkResponse,
    TagCreateRequest,
    TagListResponse,
    TagRatingRequest,
    TagRatingResponse,
    TagResponse,
    TagUpdateRequest,
)

if TYPE_CHECKING:
    from ..request import LoomRequest


class TagMethods:
    """CRUD on ``/tags``, applying tags to assets, and rating them.

    A rating expresses how well a tag fits the thing it is on, which is how
    machine-suggested tags get confirmed or rejected.
    """

    def load_tag(self, tag_uuid: _uuid_mod.UUID | str) -> LoomRequest[TagResponse]:
        """Load a single tag."""
        return self._get(f"tags/{self._uuid(tag_uuid)}", TagResponse)

    def create_tag(self, request: TagCreateRequest) -> LoomRequest[TagResponse]:
        """Create a tag."""
        return self._post("tags", request, TagResponse)

    def update_tag(
        self, tag_uuid: _uuid_mod.UUID | str, request: TagUpdateRequest
    ) -> LoomRequest[TagResponse]:
        """Update a tag. Only the fields you set are changed."""
        return self._post(f"tags/{self._uuid(tag_uuid)}", request, TagResponse)

    def list_tags(self) -> LoomRequest[TagListResponse]:
        """List tags. Supports ``limit``, ``from_``, ``filter``, ``sort`` and ``iter``."""
        return self._get("tags", TagListResponse)

    def delete_tag(self, tag_uuid: _uuid_mod.UUID | str) -> LoomRequest[None]:
        """Delete a tag. Answers 204 with no body."""
        return self._delete(f"tags/{self._uuid(tag_uuid)}")

    # -- tagging assets ------------------------------------------------------

    def tag_asset(
        self, asset_id: AssetId | _uuid_mod.UUID | str, request: TagCreateRequest
    ) -> LoomRequest[TagResponse]:
        """Apply a tag to an asset, creating the tag if it does not exist yet."""
        return self._post(f"{self._asset_sub(asset_id)}/tags", request, TagResponse)

    def bulk_tag_asset(
        self, asset_id: AssetId | _uuid_mod.UUID | str, request: AssetTagBulkRequest
    ) -> LoomRequest[AssetTagBulkResponse]:
        """Apply a whole set of tags to one asset, and detach the ones named in ``withdraw``.

        Prefer this over a loop of :meth:`tag_asset` whenever more than one tag is
        involved: the server applies the set in one transaction, so tagging a library
        costs one request per asset rather than one per tag.

        Needs ``TAG_ASSET``, and ``UNTAG_ASSET`` as well when ``withdraw`` is non-empty.
        """
        return self._put(
            f"{self._asset_sub(asset_id)}/tags", request, AssetTagBulkResponse
        )

    def untag_asset(
        self, asset_id: AssetId | _uuid_mod.UUID | str, tag_uuid: _uuid_mod.UUID | str
    ) -> LoomRequest[None]:
        """Remove a tag from an asset, with every placement of it.

        A tag may sit on one asset several times -- once per face, once per timecode.
        This clears the picture; :meth:`remove_tag_placement` clears one face. The tag
        itself is not deleted.
        """
        return self._delete(f"{self._asset_sub(asset_id)}/tags/{self._uuid(tag_uuid)}")

    def remove_tag_placement(
        self, asset_id: AssetId | _uuid_mod.UUID | str, placement_uuid: _uuid_mod.UUID | str
    ) -> LoomRequest[None]:
        """Remove one placement of a tag, keeping the tag's other placements.

        The placement uuid comes from ``placement_uuid`` on the asset's tag references.
        """
        return self._delete(
            f"{self._asset_sub(asset_id)}/tag-placements/{self._uuid(placement_uuid)}"
        )

    # -- ratings -------------------------------------------------------------

    def rate_tag(
        self, tag_uuid: _uuid_mod.UUID | str, request: TagRatingRequest
    ) -> LoomRequest[TagRatingResponse]:
        """Rate how well a tag fits."""
        return self._post(f"tags/{self._uuid(tag_uuid)}/rating", request, TagRatingResponse)

    def load_tag_rating(self, tag_uuid: _uuid_mod.UUID | str) -> LoomRequest[TagRatingResponse]:
        """Load the current rating of a tag."""
        return self._get(f"tags/{self._uuid(tag_uuid)}/rating", TagRatingResponse)

    def delete_tag_rating(self, tag_uuid: _uuid_mod.UUID | str) -> LoomRequest[None]:
        """Remove a tag's rating. Answers 204 with no body."""
        return self._delete(f"tags/{self._uuid(tag_uuid)}/rating")
