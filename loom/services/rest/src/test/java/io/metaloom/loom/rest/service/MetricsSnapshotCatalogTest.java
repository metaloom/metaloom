package io.metaloom.loom.rest.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import io.metaloom.loom.common.metrics.LoomMetrics;
import io.metaloom.loom.monitoring.MicrometerLoomMetrics;
import io.metaloom.loom.pipeline.engine.NodeDispatcher;
import io.metaloom.loom.pipeline.engine.NodeKindCircuitBreaker;
import io.metaloom.loom.pipeline.engine.PipelineRunEngine;
import io.metaloom.loom.pipeline.graph.PipelineGraph;
import io.metaloom.loom.pipeline.graph.PipelineGraphParser;
import io.metaloom.loom.pipeline.model.MediaRef;
import io.metaloom.loom.pipeline.model.NodeTask;
import io.metaloom.loom.pipeline.model.NodeTaskResult;
import io.metaloom.loom.rest.model.metrics.MetricRecord;
import io.metaloom.loom.rest.service.impl.MetricsSnapshot;
import io.metaloom.loom.rest.service.impl.PipelineEventBroadcaster;
import io.metaloom.loom.rest.service.impl.PipelineRunRegistry;
import io.metaloom.loom.rest.service.impl.ProcessorRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * The REST snapshot, the Prometheus scrape and {@code METRICS.md}, checked against each other.
 *
 * <p>
 * {@link MetricsCatalogScrapeTest} already holds the scrape to the specification. The snapshot
 * endpoint is a second reader of the same registry, and the failure mode it introduces is drift in
 * the <em>names</em>: Micrometer meter ids carry no {@code _total} / {@code _seconds} suffix, so a
 * projection that forgot to add them would serve a dashboard series that no Prometheus query and no
 * row of §3 can name. This asserts the two agree, in both directions:
 * </p>
 *
 * <ul>
 * <li>every {@code loom_*} name §3 calls live is in the snapshot;</li>
 * <li>every {@code loom_*} name §5 calls dead is <strong>not</strong>;</li>
 * <li>and every name in the snapshot appears literally in the scrape text, which is what makes
 * "same registry, same names" a fact rather than a convention.</li>
 * </ul>
 *
 * <p>
 * The registry is exercised exactly as {@link MetricsCatalogScrapeTest} does — through the real
 * instrumentation sites — so the two tests cannot disagree about what "live" means.
 * </p>
 */
public class MetricsSnapshotCatalogTest {

	/** From this module's directory to the repository root. */
	private static final Path SPEC = Path.of("..", "..", "..", "spec", "features", "ops", "METRICS.md");

	/** A table cell's `code span`, which is how every metric name in the file is written. */
	private static final Pattern CODE_SPAN = Pattern.compile("`([^`]+)`");

	private static String specText;
	private static String scrape;
	private static List<MetricRecord> snapshot;

	@BeforeAll
	static void exerciseTheWholeCatalog() throws IOException {
		specText = Files.readString(SPEC, StandardCharsets.UTF_8);

		PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
		LoomMetrics metrics = new MicrometerLoomMetrics(registry);

		// null daos + null broadcaster: the in-memory selection registry, as its own unit tests use it.
		new ProcessorRegistry(null, null, metrics);
		new PipelineRunRegistry(metrics);
		new PipelineEventBroadcaster(metrics);
		runAPipeline(metrics);
		recordTheRemainingCounters(metrics);

		scrape = registry.scrape();
		snapshot = MetricsSnapshot.of(registry);
	}

	/** One run end to end, so the engine's own meters fire from their real call sites. */
	private static void runAPipeline(LoomMetrics metrics) {
		JsonObject definition = new JsonObject()
			.put("nodes", new JsonArray()
				.add(new JsonObject().put("id", "src").put("type", "filesystem-source").put("source", true))
				.add(new JsonObject().put("id", "hash").put("type", "sha512")
					.put("options", new JsonObject().put("retryFailed", true))))
			.put("edges", new JsonArray()
				.add(new JsonObject().put("source", "src").put("sourcePort", "media").put("target", "hash")
					.put("targetPort", "media")));
		PipelineGraph graph = new PipelineGraphParser().parse("metrics", definition, true, false, 0);

		java.util.List<NodeTask> dispatched = new java.util.ArrayList<>();
		NodeDispatcher dispatcher = task -> {
			dispatched.add(task);
			return "worker-1";
		};

		NodeKindCircuitBreaker breaker = new NodeKindCircuitBreaker(metrics);

		PipelineRunEngine engine = new PipelineRunEngine(graph, dispatcher, UUID.randomUUID());
		engine.setMetrics(metrics);
		engine.setCircuitBreaker(breaker);
		engine.start();

		String itemId = engine.onItemDiscovered(MediaRef.of("/media/a.mp4"));
		engine.onSourceComplete(1);
		engine.onNodeTaskResult(itemId,
			NodeTaskResult.failed(dispatched.get(dispatched.size() - 1).getTaskUuid(), "hash", 1, "transient"));
		engine.onNodeTaskResult(itemId,
			NodeTaskResult.completed(dispatched.get(dispatched.size() - 1).getTaskUuid(), "hash", 1, Map.of()));

		PipelineRunEngine losing = new PipelineRunEngine(graph, dispatcher, UUID.randomUUID());
		losing.setMetrics(metrics);
		losing.start();
		String lostItem = losing.onItemDiscovered(MediaRef.of("/media/b.mp4"));
		losing.onSourceComplete(1);
		losing.onNodeTaskLost(lostItem, "hash", "lease expired");
		losing.onNodeTaskLost(lostItem, "hash", "lease expired again");

		for (int i = 0; i < NodeKindCircuitBreaker.DEFAULT_MIN_SAMPLES; i++) {
			breaker.record("sha512", false);
		}
	}

	/** The counters whose real call sites need a database, a WebSocket or a worker. */
	private static void recordTheRemainingCounters(LoomMetrics metrics) {
		metrics.recordRunStarted();
		metrics.recordRunCompleted("success", 1_234);
		metrics.recordRunRejected("no_processor");
		metrics.recordRunRecovered(1);
		metrics.recordNodeTaskDispatched("sha512");
		metrics.recordNodeTaskDispatchFailed("no_processor");
		metrics.recordNodeResultReceived("sha512", "success");
		metrics.recordSourceItemsReceived(3);
		metrics.recordAssetNodeResultWritten("sha512", "success");
		metrics.recordLeasesReclaimed(1);
		metrics.recordOrphansDeadlettered(1);
		metrics.recordTaskReturned("worker-1");
		metrics.recordPipelineEventBroadcast();
		metrics.recordPipelineEventDropped();
		metrics.recordProcessorRegistered();
		metrics.recordProcessorDisconnected();
		metrics.recordProcessorHeartbeat();
		metrics.recordAuthFailure("ws");
	}

	private Set<String> snapshotNames() {
		return snapshot.stream().map(MetricRecord::getName).collect(Collectors.toCollection(LinkedHashSet::new));
	}

	@Test
	void testEveryMeterDocumentedAsLiveIsInTheSnapshot() {
		Set<String> documented = loomNamesIn(section("## 3. Loom Metrics"));

		// A guard against the guard: if the table stops parsing, an empty expectation would make this
		// pass for the wrong reason.
		assertThat(documented).hasSizeGreaterThan(15);

		Set<String> served = snapshotNames();
		assertThat(documented.stream().filter(name -> !served.contains(name)).toList())
			.as("METRICS.md §3 lists these as live, but GET /api/v1/metrics does not serve them. Either "
				+ "the projection drops them or the suffix convention drifted from the scrape.")
			.isEmpty();
	}

	@Test
	void testNothingDocumentedAsDeadIsInTheSnapshot() {
		Set<String> declaredDead = loomNamesIn(section("## 5. Declared but never recorded"));
		Set<String> served = snapshotNames();

		assertThat(declaredDead.stream().filter(served::contains).toList())
			.as("These are served now but METRICS.md still files them under §5, the gap list.")
			.isEmpty();
	}

	/**
	 * The names are the scrape's names, character for character. This is the assertion that makes the
	 * REST route and the Prometheus port interchangeable for a dashboard author.
	 */
	@Test
	void testEverySnapshotNameAppearsVerbatimInTheScrape() {
		assertThat(snapshotNames().stream().filter(name -> !scrape.contains(name)).toList())
			.as("the JSON snapshot invented a series name the Prometheus exposition does not use")
			.isEmpty();
	}

	@Test
	void testCountersAndTimersCarryTheSuffixTheExpositionAdds() {
		// The suffixes are added at scrape time, never by the meter id - forgetting them here is the
		// one way this endpoint can silently stop matching §3.
		assertThat(snapshotNames()).contains("loom_pipeline_runs_started_total", "loom_node_task_latency_seconds");
		assertThat(snapshot).filteredOn(record -> "COUNTER".equals(record.getType()))
			.allSatisfy(record -> assertThat(record.getName()).endsWith("_total"));
		assertThat(snapshot).filteredOn(record -> "TIMER".equals(record.getType()))
			.allSatisfy(record -> assertThat(record.getName()).endsWith("_seconds"));
	}

	@Test
	void testTheLabelSetSurvivesTheProjection() {
		// A dashboard splits latency by kind and state; flattening the tags away would leave it with
		// one meaningless aggregate and no way back.
		assertThat(snapshot)
			.filteredOn(record -> "loom_node_task_latency_seconds".equals(record.getName()))
			.isNotEmpty()
			.allSatisfy(record -> assertThat(record.getTags()).containsKeys("kind", "state"));

		assertThat(snapshot)
			.filteredOn(record -> "loom_node_circuit_breaker_state".equals(record.getName()))
			.extracting(record -> record.getTags().get("kind"))
			.contains("sha512");
	}

	@Test
	void testATimerReportsItsMeanRatherThanOnlyATotal() {
		MetricRecord latency = snapshot.stream()
			.filter(record -> "loom_node_task_latency_seconds".equals(record.getName()))
			.findFirst().orElseThrow();
		assertThat(latency.getCount()).isPositive();
		assertThat(latency.getMeanSeconds()).isNotNull().isEqualTo(latency.getSumSeconds() / latency.getCount());
		assertThat(latency.getValue()).as("a timer has no single value").isNull();
	}

	@Test
	void testNothingOutsideTheLoomNamespaceIsServed() {
		// JVM, process and Vert.x families belong on the monitoring port, where a Prometheus reads
		// them. Serving them here would multiply the payload for a browser that cannot use them.
		assertThat(snapshotNames()).allSatisfy(name -> assertThat(name).startsWith("loom_"));
	}

	/**
	 * @param heading the heading line's prefix
	 * @return that section's text, up to the next top-level heading
	 */
	private String section(String heading) {
		int start = specText.indexOf(heading);
		assertThat(start).as("METRICS.md no longer contains a section starting '" + heading + "'").isNotNegative();
		int end = specText.indexOf("\n## ", start + heading.length());
		return end < 0 ? specText.substring(start) : specText.substring(start, end);
	}

	/**
	 * Metric names named in a section's tables — first column only, wildcard families skipped, as in
	 * {@link MetricsCatalogScrapeTest}.
	 */
	private Set<String> loomNamesIn(String sectionText) {
		Set<String> names = new LinkedHashSet<>();
		for (String line : sectionText.split("\n")) {
			if (!line.startsWith("|")) {
				continue;
			}
			String[] cells = line.split("\\|", 3);
			String firstCell = cells.length < 2 ? "" : cells[1];
			Matcher matcher = CODE_SPAN.matcher(firstCell);
			while (matcher.find()) {
				String name = matcher.group(1);
				if (name.startsWith("loom_") && !name.contains("*")) {
					names.add(name);
				}
			}
		}
		return names;
	}
}
