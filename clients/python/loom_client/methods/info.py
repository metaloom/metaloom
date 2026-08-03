"""Instance info. Mirrors ``io.metaloom.loom.client.common.method.InfoMethods``."""

from __future__ import annotations

from typing import TYPE_CHECKING

from ..models.info import RESTInfoResponse
from ..models.user import UserResponse

if TYPE_CHECKING:
    from ..request import LoomRequest


class InfoMethods:
    """The API root and the current identity."""

    def rest_info(self) -> LoomRequest[RESTInfoResponse]:
        """Load the API root, reporting the server version and DB schema revision.

        Targets ``/api/v1`` itself — the empty path, with no trailing slash, which is
        what the router matches.
        """
        return self._get("", RESTInfoResponse)

    def me(self) -> LoomRequest[UserResponse]:
        """Load the user the current token belongs to."""
        return self._get("me", UserResponse)
