"""Share links. Mirrors ``io.metaloom.loom.client.common.method.ShareMethods``."""

from __future__ import annotations

import uuid as _uuid_mod
from typing import TYPE_CHECKING

from ..models.share import (
    ShareAnnotationListResponse,
    ShareAnnotationRequest,
    ShareAnnotationResponse,
    ShareChallengeResponse,
    ShareCommentListResponse,
    ShareCommentRequest,
    ShareCommentResponse,
    ShareCreateRequest,
    ShareFeedbackResponse,
    ShareListResponse,
    ShareReactionListResponse,
    ShareReactionRequest,
    ShareReactionResponse,
    ShareResponse,
    ShareSessionRequest,
    ShareSessionResponse,
    ShareUpdateRequest,
    SharedAssetListResponse,
    SharedAssetResponse,
)

if TYPE_CHECKING:
    from ..request import LoomRequest


class ShareMethods:
    """Share links, and the customer-facing area behind them.

    Two halves with two different credentials.

    The ``*_share*`` methods address ``/share-links`` and need a logged-in user
    holding the ``*_SHARE`` permissions -- the ordinary bearer token.

    The ``*_shared_*`` methods address ``/shares/{slug}`` the way a customer's
    browser does: no account at all. Everything after :meth:`open_share` needs the
    session token that call returns, set on the client with
    ``client.share_session_token = ...``. It travels as ``X-Loom-Share-Session``
    and is deliberately not a bearer token: a share visitor is not a user, and the
    two credentials must never be interchangeable.
    """

    # --- Owner side: /share-links ---

    def create_share(self, request: ShareCreateRequest) -> LoomRequest[ShareResponse]:
        """Create a share link over an asset or a collection.

        Needs ``CREATE_SHARE`` *and* read access to the target: you may not
        publish what you are not allowed to look at.

        The response is the only place the generated password ever appears --
        only its bcrypt hash is stored.
        """
        return self._post("share-links", request, ShareResponse)

    def load_share(self, share_uuid: _uuid_mod.UUID | str) -> LoomRequest[ShareResponse]:
        """Load a share link."""
        return self._get(f"share-links/{self._uuid(share_uuid)}", ShareResponse)

    def update_share(
        self, share_uuid: _uuid_mod.UUID | str, request: ShareUpdateRequest
    ) -> LoomRequest[ShareResponse]:
        """Change a link's expiry, password or capabilities. Absent fields are left alone."""
        return self._post(f"share-links/{self._uuid(share_uuid)}", request, ShareResponse)

    def list_shares(self) -> LoomRequest[ShareListResponse]:
        """List share links. Supports ``limit``, ``from_``, ``filter``, ``sort`` and ``iter``."""
        return self._get("share-links", ShareListResponse)

    def delete_share(self, share_uuid: _uuid_mod.UUID | str) -> LoomRequest[None]:
        """Revoke a share link.

        The URL stops working immediately and everything said through it is
        removed. Answers 204 with no body.
        """
        return self._delete(f"share-links/{self._uuid(share_uuid)}")

    def list_asset_shares(self, asset_uuid: _uuid_mod.UUID | str) -> LoomRequest[ShareListResponse]:
        """The share links pointing at one asset."""
        return self._get(f"assets/{self._uuid(asset_uuid)}/share-links", ShareListResponse)

    def list_collection_shares(
        self, collection_uuid: _uuid_mod.UUID | str
    ) -> LoomRequest[ShareListResponse]:
        """The share links pointing at one collection."""
        return self._get(f"collections/{self._uuid(collection_uuid)}/share-links", ShareListResponse)

    def load_share_feedback(self, share_uuid: _uuid_mod.UUID | str) -> LoomRequest[ShareFeedbackResponse]:
        """Everything the visitor said through one link: comments, marks and reactions."""
        return self._get(f"share-links/{self._uuid(share_uuid)}/feedback", ShareFeedbackResponse)

    # --- Customer side: /shares/{slug} ---

    def load_share_challenge(self, slug: str) -> LoomRequest[ShareChallengeResponse]:
        """What a link asks for before it will open.

        Answers 404 for an unknown, revoked or lapsed slug -- all three look the
        same on purpose, so the route cannot be used to find out which slugs were
        ever real.
        """
        return self._get(f"shares/{slug}", ShareChallengeResponse)

    def open_share(self, slug: str, request: ShareSessionRequest) -> LoomRequest[ShareSessionResponse]:
        """Open a link: satisfy its password and give a visitor name.

        Set the returned ``session_token`` on the client before calling anything
        below. The name is recorded only on the first visit -- later visitors keep
        the stored one.
        """
        return self._post(f"shares/{slug}/sessions", request, ShareSessionResponse)

    def list_shared_assets(self, slug: str) -> LoomRequest[SharedAssetListResponse]:
        """The material behind a link: a collection's members, or the one shared asset."""
        return self._get(f"shares/{slug}/assets", SharedAssetListResponse)

    def load_shared_asset(
        self, slug: str, asset_uuid: _uuid_mod.UUID | str
    ) -> LoomRequest[SharedAssetResponse]:
        """One shared asset, in the narrow projection a visitor may see."""
        return self._get(f"shares/{slug}/assets/{self._uuid(asset_uuid)}", SharedAssetResponse)

    def list_shared_comments(self, slug: str) -> LoomRequest[ShareCommentListResponse]:
        """The comments left through this link."""
        return self._get(f"shares/{slug}/comments", ShareCommentListResponse)

    def create_shared_comment(
        self, slug: str, request: ShareCommentRequest
    ) -> LoomRequest[ShareCommentResponse]:
        """Leave a comment. The author is taken from the link, not from the request."""
        return self._post(f"shares/{slug}/comments", request, ShareCommentResponse)

    def update_shared_comment(
        self, slug: str, comment_uuid: _uuid_mod.UUID | str, request: ShareCommentRequest
    ) -> LoomRequest[ShareCommentResponse]:
        """Edit a comment left through this link."""
        return self._post(
            f"shares/{slug}/comments/{self._uuid(comment_uuid)}", request, ShareCommentResponse
        )

    def delete_shared_comment(self, slug: str, comment_uuid: _uuid_mod.UUID | str) -> LoomRequest[None]:
        """Remove a comment left through this link. Answers 204 with no body."""
        return self._delete(f"shares/{slug}/comments/{self._uuid(comment_uuid)}")

    def list_shared_annotations(self, slug: str) -> LoomRequest[ShareAnnotationListResponse]:
        """The marks drawn through this link, in media order."""
        return self._get(f"shares/{slug}/annotations", ShareAnnotationListResponse)

    def create_shared_annotation(
        self, slug: str, request: ShareAnnotationRequest
    ) -> LoomRequest[ShareAnnotationResponse]:
        """Mark a moment, a region, or a region over a stretch of time.

        Coordinates are normalised 0..1 against the media's own size, and times
        are seconds as a float.
        """
        return self._post(f"shares/{slug}/annotations", request, ShareAnnotationResponse)

    def update_shared_annotation(
        self, slug: str, annotation_uuid: _uuid_mod.UUID | str, request: ShareAnnotationRequest
    ) -> LoomRequest[ShareAnnotationResponse]:
        """Move or retitle a mark. The kind cannot be changed -- delete it and draw a new one."""
        return self._post(
            f"shares/{slug}/annotations/{self._uuid(annotation_uuid)}", request, ShareAnnotationResponse
        )

    def delete_shared_annotation(
        self, slug: str, annotation_uuid: _uuid_mod.UUID | str
    ) -> LoomRequest[None]:
        """Remove a mark drawn through this link. Answers 204 with no body."""
        return self._delete(f"shares/{slug}/annotations/{self._uuid(annotation_uuid)}")

    def list_shared_reactions(self, slug: str) -> LoomRequest[ShareReactionListResponse]:
        """The reactions left through this link."""
        return self._get(f"shares/{slug}/reactions", ShareReactionListResponse)

    def create_shared_reaction(
        self, slug: str, request: ShareReactionRequest
    ) -> LoomRequest[ShareReactionResponse]:
        """React to an asset, a guest comment or a guest mark.

        Reacting twice the same way is a no-op rather than an error.
        """
        return self._post(f"shares/{slug}/reactions", request, ShareReactionResponse)

    def delete_shared_reaction(self, slug: str, reaction_uuid: _uuid_mod.UUID | str) -> LoomRequest[None]:
        """Take back a reaction. Answers 204 with no body."""
        return self._delete(f"shares/{slug}/reactions/{self._uuid(reaction_uuid)}")
