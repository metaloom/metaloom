"""API tokens. Mirrors ``io.metaloom.loom.client.common.method.TokenMethods``."""

from __future__ import annotations

import uuid as _uuid_mod
from typing import TYPE_CHECKING

from ..models.token import (
    TokenCreateRequest,
    TokenListResponse,
    TokenResponse,
    TokenUpdateRequest,
)

if TYPE_CHECKING:
    from ..request import LoomRequest


class TokenMethods:
    """CRUD on ``/tokens``.

    These manage long-lived API tokens as stored entities -- they are how a daemon or
    a processing node authenticates without a password. To use one, pass it to
    :class:`~loom_client.client.LoomClient` as ``token=`` or via ``set_token``.

    Not to be confused with :meth:`~loom_client.methods.authentication.AuthenticationMethods.login`,
    which issues a short-lived JWT.
    """

    def load_token(self, token_uuid: _uuid_mod.UUID | str) -> LoomRequest[TokenResponse]:
        """Load a single token record."""
        return self._get(f"tokens/{self._uuid(token_uuid)}", TokenResponse)

    def create_token(self, request: TokenCreateRequest) -> LoomRequest[TokenResponse]:
        """Issue an API token.

        The token value is returned here. Store it now -- the server does not hand it
        back again.
        """
        return self._post("tokens", request, TokenResponse)

    def update_token(
        self, token_uuid: _uuid_mod.UUID | str, request: TokenUpdateRequest
    ) -> LoomRequest[TokenResponse]:
        """Update a token record. Only the fields you set are changed."""
        return self._post(f"tokens/{self._uuid(token_uuid)}", request, TokenResponse)

    def list_tokens(self) -> LoomRequest[TokenListResponse]:
        """List token records. Supports ``limit``, ``from_``, ``filter``, ``sort`` and ``iter``."""
        return self._get("tokens", TokenListResponse)

    def delete_token(self, token_uuid: _uuid_mod.UUID | str) -> LoomRequest[None]:
        """Revoke a token. Answers 204 with no body."""
        return self._delete(f"tokens/{self._uuid(token_uuid)}")
