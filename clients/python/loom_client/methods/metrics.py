"""Metric catalog. Mirrors ``io.metaloom.loom.client.common.method.MetricsMethods``."""

from __future__ import annotations

from typing import TYPE_CHECKING, Optional

from ..models.metrics import MetricsResponse

if TYPE_CHECKING:
    from ..request import LoomRequest


class MetricsMethods:
    """The ``loom_*`` metric catalog, read over the app REST API."""

    def load_metrics(self, prefix: Optional[str] = None) -> LoomRequest[MetricsResponse]:
        """Load a snapshot of the ``loom_*`` metric catalog.

        This is not the Prometheus scrape endpoint: that one lives on the monitoring
        port (8989), is unauthenticated by design and is not meant to be reachable from
        an application. This route serves the same registry and the same series names
        over the authenticated API, gated by ``READ_METRIC``.

        There is no history. Every call is one instant, stamped with ``timestamp``; a
        rate is obtained by sampling twice and differencing the counters.

        :param prefix: optional series-name prefix inside the ``loom_`` namespace.
            A prefix outside it is rejected with 400 rather than answered with an
            empty list.
        """
        request = self._get("metrics", MetricsResponse)
        if prefix is not None:
            # A real query parameter: the path is percent-encoded, so an inlined "?prefix="
            # would reach the server as %3Fprefix= and the route would not match.
            request.param("prefix", prefix)
        return request
