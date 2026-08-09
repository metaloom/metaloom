package io.metaloom.loom.rest.service.impl;

import static io.metaloom.loom.db.model.perm.Permission.READ_METRIC;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.metaloom.loom.api.error.LoomRestErrorCode;
import io.metaloom.loom.api.error.LoomRestException;
import io.metaloom.loom.rest.LoomRoutingContext;
import io.metaloom.loom.rest.builder.LoomModelBuilder;
import io.metaloom.loom.rest.model.metrics.MetricRecord;
import io.metaloom.loom.rest.model.metrics.MetricsResponse;
import io.metaloom.loom.rest.service.AbstractEndpointService;
import io.metaloom.loom.rest.validation.LoomModelValidator;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;

/**
 * Serves {@code GET /api/v1/metrics} — the Loom metric catalog as JSON.
 *
 * <p>
 * The monitoring screen cannot read the Prometheus surface: {@code /metrics} lives on the internal
 * monitoring port (8989), which is unauthenticated by design and therefore must not be reachable
 * from a browser. This route is the app-side read of the same registry — same names, same label
 * sets, an authenticated JSON body instead of the text exposition.
 * </p>
 *
 * <p>
 * <b>No history.</b> Loom has no time-series store, so this is one instant. A caller wanting a rate
 * samples twice and differences the counters against {@code timestamp}; a caller wanting weeks of
 * history wants a Prometheus, which is what the monitoring port is for. Nothing here fabricates a
 * series.
 * </p>
 *
 * <p>
 * Not a CRUD resource, so this extends {@link AbstractEndpointService}: there is no element type,
 * and a metric series is identified by a name and a label set rather than by a uuid.
 * </p>
 */
@Singleton
public class MetricsEndpointService extends AbstractEndpointService {

	private final PrometheusMeterRegistry registry;

	@Inject
	public MetricsEndpointService(PrometheusMeterRegistry registry, LoomModelBuilder modelBuilder, LoomModelValidator validator) {
		super(modelBuilder, validator);
		this.registry = registry;
	}

	/**
	 * {@code GET /api/v1/metrics} — every {@code loom_*} series, or the ones whose name starts with
	 * {@code ?prefix=}.
	 *
	 * <p>
	 * The filter is a name prefix rather than a full name because a dashboard panel wants a family
	 * ({@code loom_node_tasks_}), not one series, and because a name that is not in the catalog is an
	 * empty result rather than an error — a meter only exists once something has recorded it, so
	 * "nothing yet" is a normal answer on a freshly started instance.
	 * </p>
	 */
	public void loadMetrics(LoomRoutingContext lrc) {
		checkPerm(lrc, READ_METRIC, () -> {
			String prefix = prefixParam(lrc);
			List<MetricRecord> records = MetricsSnapshot.of(registry).stream()
				.filter(record -> prefix == null || record.getName().startsWith(prefix))
				.toList();
			lrc.send(new MetricsResponse()
				.setTimestamp(Instant.now().toString())
				.setMetrics(records));
		});
	}

	/**
	 * @param lrc the request
	 * @return the requested name prefix, or null when unfiltered
	 */
	private String prefixParam(LoomRoutingContext lrc) {
		List<String> values = lrc.queryParam("prefix");
		if (values == null || values.isEmpty()) {
			return null;
		}
		String prefix = values.get(0).trim().toLowerCase(Locale.ROOT);
		if (prefix.isEmpty()) {
			return null;
		}
		// The catalog is the loom_ namespace and nothing else. Rejecting rather than silently
		// returning nothing keeps a caller from concluding that jvm_memory_used_bytes reads zero.
		if (!prefix.startsWith(MetricsSnapshot.CATALOG_PREFIX) && !MetricsSnapshot.CATALOG_PREFIX.startsWith(prefix)) {
			throw new LoomRestException(400, LoomRestErrorCode.BAD_QUERY_PARAMS,
				"The metrics catalog only serves the '" + MetricsSnapshot.CATALOG_PREFIX + "' namespace.");
		}
		return prefix;
	}
}
