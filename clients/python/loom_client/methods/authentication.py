"""Authentication. Mirrors ``io.metaloom.loom.client.common.method.AuthenticationMethods``."""

from __future__ import annotations

from typing import TYPE_CHECKING

from ..models.auth import AuthLoginRequest, AuthLoginResponse

if TYPE_CHECKING:
    from ..request import LoomRequest


class AuthenticationMethods:
    """Exchanging credentials for a JWT.

    ``login`` only returns the token; it does not install it on the client, matching
    the Java client. :meth:`loom_client.client.LoomClient.authenticate` does both.
    """

    def login(self, username: str, password: str) -> LoomRequest[AuthLoginResponse]:
        """Log in and return a token to pass to ``set_token``.

        The route is unsecured, so it works on a client that has no token set.
        """
        request = AuthLoginRequest(username=username, password=password)
        return self._post("login", request, AuthLoginResponse)
