package io.metaloom.loom.core.endpoint.test;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.client.common.LoomClientException;
import io.metaloom.loom.client.http.LoomHttpClient;
import io.metaloom.loom.core.endpoint.AbstractEndpointTest;
import io.metaloom.loom.db.model.perm.Permission;
import io.metaloom.loom.rest.model.metrics.MetricRecord;
import io.metaloom.loom.rest.model.metrics.MetricsResponse;

/**
 * {@code GET /api/v1/metrics} — the JSON read of the {@code loom_*} catalog.
 *
 * <p>
 * The naming contract against {@code spec/features/ops/METRICS.md} is asserted separately, in
 * {@code MetricsSnapshotCatalogTest} (module {@code loom/services/rest}), which can construct every
 * instrumentation site at once and needs no database. What is checked here is the route: that it is
 * served, gated, filtered and shaped as documented.
 * </p>
 */
public class MetricsEndpointTest extends AbstractEndpointTest {

	private MetricsResponse loadMetrics(LoomHttpClient client) throws LoomClientException {
		return client.loadMetrics().sync().body();
	}

	/**
	 * A booted instance already publishes gauges: {@code ProcessorRegistry}, {@code PipelineRunRegistry}
	 * and {@code PipelineEventBroadcaster} bind theirs in their constructors, and the REST endpoints
	 * they back are constructed during boot. So the catalog is never empty, even before any work runs.
	 */
	@Test
	public void testReadReturnsTheLiveCatalog() throws Exception {
		try (LoomHttpClient client = httpClient()) {
			loginAdmin(client);
			MetricsResponse response = loadMetrics(client);

			assertThat(response.getTimestamp()).as("a snapshot must say when it was taken").isNotBlank();
			assertThat(response.getMetrics()).as("a booted instance publishes its bound gauges").isNotEmpty();

			// One series per enum constant, bound at construction - a state with no workers reads 0
			// rather than vanishing, which is the whole point of binding them up front.
			assertThat(response.getMetrics())
				.filteredOn(record -> "loom_processors_by_state".equals(record.getName()))
				.extracting(record -> record.getTags().get("state"))
				.contains("online", "offline", "starting", "paused", "terminating");
		}
	}

	/**
	 * The names must be the ones a Prometheus scrape would show, suffixes included — otherwise a
	 * dashboard built against this endpoint and one built against {@code /metrics} disagree about
	 * what the same series is called.
	 */
	@Test
	public void testNamesCarryTheScrapedSuffixes() throws Exception {
		try (LoomHttpClient client = httpClient()) {
			loginAdmin(client);
			List<MetricRecord> records = loadMetrics(client).getMetrics();

			assertThat(records).allSatisfy(record -> {
				assertThat(record.getName()).startsWith("loom_");
				switch (record.getType()) {
					case "COUNTER" -> assertThat(record.getName()).endsWith("_total");
					case "TIMER" -> assertThat(record.getName()).endsWith("_seconds");
					default -> {
					}
				}
			});
		}
	}

	/** A timer reports count/sum/max/mean; a counter or gauge reports a single value. */
	@Test
	public void testTimersAndCountersCarryDifferentFields() throws Exception {
		try (LoomHttpClient client = httpClient()) {
			loginAdmin(client);
			for (MetricRecord record : loadMetrics(client).getMetrics()) {
				if ("TIMER".equals(record.getType())) {
					assertThat(record.getCount()).as(record.getName()).isNotNull();
					assertThat(record.getMeanSeconds()).as(record.getName()).isNotNull();
					assertThat(record.getValue()).as(record.getName() + " is a timer, not a single value").isNull();
				} else {
					assertThat(record.getCount()).as(record.getName()).isNull();
				}
			}
		}
	}

	@Test
	public void testPrefixNarrowsTheResult() throws Exception {
		try (LoomHttpClient client = httpClient()) {
			loginAdmin(client);
			List<MetricRecord> all = loadMetrics(client).getMetrics();
			List<MetricRecord> processors = client.loadMetrics("loom_processors").sync().body().getMetrics();

			assertThat(processors).isNotEmpty().hasSizeLessThan(all.size());
			assertThat(processors).allSatisfy(record -> assertThat(record.getName()).startsWith("loom_processors"));
		}
	}

	/**
	 * Asking for a namespace this route does not serve is an error, not an empty list: a caller must
	 * never be able to conclude from a 200 that {@code jvm_memory_used_bytes} reads zero.
	 */
	@Test
	public void testAForeignNamespaceIsRejected() throws Exception {
		try (LoomHttpClient client = httpClient()) {
			loginAdmin(client);
			expect(400, "Bad Request", client.loadMetrics("jvm_"));
		}
	}

	@Test
	public void testAnEmptyPrefixIsTreatedAsUnfiltered() throws Exception {
		try (LoomHttpClient client = httpClient()) {
			loginAdmin(client);
			assertThat(client.loadMetrics("").sync().body().getMetrics())
				.hasSameSizeAs(loadMetrics(client).getMetrics());
		}
	}

	// ── Permissions ───────────────────────────────────────────────────────

	@Test
	public void testAPermissionlessUserIsDenied() throws Exception {
		try (LoomHttpClient client = loginPermissionlessClient()) {
			expect(403, "Forbidden", client.loadMetrics());
		}
	}

	/**
	 * READ_METRIC alone is enough — instance health is not derived from any other resource, so this
	 * route must not require a second grant that would make an operator role also a reader of assets.
	 */
	@Test
	public void testReadMetricAloneGrantsAccess() throws Exception {
		try (LoomHttpClient client = loginClientWith("metrics-reader", Permission.READ_METRIC)) {
			assertThat(loadMetrics(client).getMetrics()).isNotEmpty();
		}
	}

	/**
	 * Holding a neighbouring fleet permission is not enough. READ_CORTEX_INSTANCE says "you may see
	 * which workers are attached"; it deliberately does not say "you may see how the instance is
	 * performing".
	 */
	@Test
	public void testANeighbouringFleetPermissionIsNotEnough() throws Exception {
		try (LoomHttpClient client = loginClientWith("worker-lister", Permission.READ_CORTEX_INSTANCE)) {
			expect(403, "Forbidden", client.loadMetrics());
		}
	}

	@Test
	public void testAnAnonymousCallerIsRejected() throws Exception {
		try (LoomHttpClient client = httpClient()) {
			expect(401, "Unauthorized", client.loadMetrics());
		}
	}
}
