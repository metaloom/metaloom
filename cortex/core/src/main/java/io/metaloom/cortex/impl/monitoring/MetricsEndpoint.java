package io.metaloom.cortex.impl.monitoring;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import io.vertx.ext.web.Router;
import io.vertx.micrometer.PrometheusScrapingHandler;

/**
 * Registers the Prometheus scrape endpoint ({@code GET /metrics}) on the Cortex monitoring router.
 * Mirrors {@link HealthEndpoint}. The endpoint is served on the monitoring port (default 8093), not
 * on any application-facing surface.
 */
@Singleton
public class MetricsEndpoint {

	private final PrometheusMeterRegistry registry;

	@Inject
	public MetricsEndpoint(PrometheusMeterRegistry registry) {
		this.registry = registry;
	}

	public void register(Router router) {
		// Scrape directly from our registry so the route is independent of Vert.x's global backend
		// registry lookup. Emits both the custom cortex_* meters and the Vert.x/JVM built-ins.
		router.get("/metrics").handler(PrometheusScrapingHandler.create(registry));
	}
}
