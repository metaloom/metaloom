package io.metaloom.loom.rest.endpoint.impl;

import static io.metaloom.loom.rest.RESTConstants.API_V1_PATH;
import static io.vertx.core.http.HttpMethod.GET;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.loom.rest.AbstractEndpoint;
import io.metaloom.loom.rest.EndpointDependencies;
import io.metaloom.loom.rest.model.ModelExamples;
import io.metaloom.loom.rest.service.impl.MetricsEndpointService;

/**
 * The JSON read of the Loom metric catalog, on the app REST port.
 *
 * <p>
 * <b>This is not the Prometheus surface.</b> That stays where
 * {@code spec/features/ops/METRICS.md} §1 puts it: {@code GET /metrics} on the monitoring port
 * (8989), unauthenticated and restricted at the network layer. A browser cannot reach that port, and
 * exposing it so that it could would mean publishing an unauthenticated scrape on the public
 * surface. So the monitoring screen reads the same registry through this authenticated,
 * permission-gated route instead, and both name every series identically — see
 * {@code MetricsSnapshot}.
 * </p>
 *
 * <p>
 * Not a CRUD resource: a series is identified by name and label set, never by a uuid, so there is no
 * {@code /:uuid} route and no create/update/delete.
 * </p>
 */
@Singleton
public class MetricsEndpoint extends AbstractEndpoint {

	private static final Logger log = LoggerFactory.getLogger(MetricsEndpoint.class);

	private final MetricsEndpointService service;

	private final ModelExamples examples;

	@Inject
	public MetricsEndpoint(MetricsEndpointService service, ModelExamples examples, EndpointDependencies deps) {
		super(deps);
		this.service = service;
		this.examples = examples;
	}

	@Override
	public String name() {
		return "metrics";
	}

	@Override
	public String basePath() {
		return API_V1_PATH + "/metrics";
	}

	@Override
	public void register() {
		log.info("Registering {} endpoint", name());

		secure(basePath());

		addRoute(basePath(), GET,
			"Load a snapshot of the loom_* metric catalog",
			null,
			examples.metricsResponseExample(),
			lrc -> service.loadMetrics(lrc));
	}
}
