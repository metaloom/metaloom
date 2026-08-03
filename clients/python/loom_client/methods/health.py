"""Health check. Mirrors ``io.metaloom.loom.client.common.method.HealthMethods``."""

from __future__ import annotations

from typing import TYPE_CHECKING

from ..models.health import HealthCheckResponse

if TYPE_CHECKING:
    from ..request import LoomRequest


class HealthMethods:
    """Liveness and readiness."""

    def health(self) -> LoomRequest[HealthCheckResponse]:
        """Report service status, version and database connectivity.

        Unsecured, so it works before logging in.
        """
        return self._get("health", HealthCheckResponse)
