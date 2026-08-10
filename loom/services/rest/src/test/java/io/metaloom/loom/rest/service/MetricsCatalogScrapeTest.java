package io.metaloom.loom.rest.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.List;
import java.util.Set;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Test;

import io.metaloom.loom.common.metrics.LoomMetrics;
import io.metaloom.loom.common.metrics.LoomMetrics;
import io.metaloom.loom.monitoring.MicrometerLoomMetrics;
import io.metaloom.loom.monitoring.MicrometerLoomMetrics;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;

/**
 * The specification and the scrape, checked against each other.
 *
 * <p>
 * {@code METRICS.md} was allowed to drift into documenting twelve meters that did not exist, and
 * the failure was not caught because nothing ever compared the file to a running registry. This
 * does, in both directions:
 * </p>
 *
 * <ul>
 * <li>every {@code loom_*} name §3 calls live must appear in a real Prometheus scrape;</li>
 * <li>every {@code loom_*} name §5 calls dead must <strong>not</strong> — so implementing one
 * without moving its row fails here, and so does documenting one that was never wired.</li>
 * </ul>
 *
 * <p>
 * The gauges are bound by constructing the production instrumentation sites rather than by binding
 * the names here. A test that bound its own gauges would prove only that the test can spell them.
 * </p>
 *
 * <p>
 * No database and no socket: {@code ProcessorRegistry} accepts a null DAO collection and a null
 * broadcaster, and the engine dispatches through the {@link NodeDispatcher} SPI.
 * </p>
 */
public class MetricsCatalogScrapeTest {

	/** From this module's directory to the repository root. */
	private static final Path SPEC = Path.of("..", "..", "..", "spec", "features", "ops", "METRICS.md");

	/** A table cell's `code span`, which is how every metric name in the file is written. */
	private static final Pattern CODE_SPAN = Pattern.compile("`([^`]+)`");

	private static String scrape;
	private static String specText;

	@BeforeAll
	static void exerciseTheWholeCatalog() throws IOException {
		specText = Files.readString(SPEC, StandardCharsets.UTF_8);

		PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
		LoomMetrics metrics = new MicrometerLoomMetrics(registry);

		MetricsCatalogExercise.exerciseEverything(metrics);

		scrape = registry.scrape();
	}

	@Test
	void testEveryMeterDocumentedAsLiveIsInTheScrape() {
		Set<String> documented = loomNamesIn(section("## 3. Loom Metrics"));

		// A guard against the guard: if the table stops parsing, an empty expectation would make
		// this test pass for the wrong reason.
		assertThat(documented).hasSizeGreaterThan(15);

		List<String> missing = documented.stream().filter(name -> !scrape.contains(name)).toList();
		assertThat(missing)
			.as("METRICS.md §3 lists these as live, but they do not appear in a scrape. Either wire "
				+ "the call site or move the row to §5.")
			.isEmpty();
	}

	@Test
	void testNothingDocumentedAsDeadIsInTheScrape() {
		Set<String> declaredDead = loomNamesIn(section("## 5. Declared but never recorded"));

		List<String> unexpectedlyAlive = declaredDead.stream().filter(name -> scrape.contains(name)).toList();
		assertThat(unexpectedlyAlive)
			.as("These are recorded now but METRICS.md still files them under §5. Move their rows up "
				+ "to §3 - the section is the gap list, and a stale one is worse than none.")
			.isEmpty();
	}

	@Test
	void testTheFourFleetHealthSignalsAreAllPresent() {
		// Spelled out rather than left to the table parse: these are the four the work item names,
		// and a table edit must not be able to quietly drop one of them from the expectation.
		assertThat(scrape)
			.contains("loom_node_task_latency_seconds")
			.contains("loom_node_tasks_inflight")
			.contains("loom_node_circuit_breaker_state")
			.contains("loom_processors_by_state");
	}

	@Test
	void testDepthIsReportedAgainstItsCeiling() {
		// Outstanding work alone cannot distinguish a busy fleet from a saturated one, and it is
		// saturation that decides whether adding workers would help.
		assertThat(scrape).contains("loom_node_tasks_inflight_ceiling");
	}

	@Test
	void testTheCircuitBreakerStateIsLabelledByKind() {
		assertThat(scrape).contains("loom_node_circuit_breaker_state{kind=\"sha512\"}");
		assertThat(scrape).contains("loom_node_circuit_breaker_trips_total{kind=\"sha512\"}");
	}

	@Test
	void testProcessorStatesAreOneSeriesPerEnumConstant() {
		// Bounded and fixed: the series exist from startup, so a state with no workers reads 0
		// rather than vanishing - a gauge that disappears is indistinguishable from a scrape that
		// failed.
		assertThat(scrape)
			.contains("loom_processors_by_state{state=\"online\"}")
			.contains("loom_processors_by_state{state=\"terminating\"}")
			.contains("loom_processors_by_state{state=\"offline\"}")
			.contains("loom_processors_by_state{state=\"paused\"}")
			.contains("loom_processors_by_state{state=\"starting\"}");
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
	 * Metric names named in a section's tables.
	 *
	 * <p>
	 * Only the first column of a table row, so a name mentioned in prose or in a label column is not
	 * mistaken for a documented series. Wildcard rows ({@code vertx_*}, {@code jvm_*}) are skipped:
	 * they are Vert.x and JVM binder families, not this catalog's to produce.
	 * </p>
	 */
	private Set<String> loomNamesIn(String sectionText) {
		Set<String> names = new LinkedHashSet<>();
		for (String line : sectionText.split("\n")) {
			if (!line.startsWith("|")) {
				continue;
			}
			String firstCell = line.split("\\|", 3).length < 2 ? "" : line.split("\\|", 3)[1];
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
