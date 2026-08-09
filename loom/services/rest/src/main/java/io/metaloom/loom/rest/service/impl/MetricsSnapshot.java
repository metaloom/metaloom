package io.metaloom.loom.rest.service.impl;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import io.metaloom.loom.rest.model.metrics.MetricRecord;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Measurement;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;

/**
 * Projects the shared meter registry onto the {@code loom_*} catalog as JSON records.
 *
 * <h2>Why the names are rewritten</h2>
 *
 * <p>
 * Micrometer meter ids omit the Prometheus suffixes — a counter is registered as
 * {@code loom_pipeline_runs_started} and exported as {@code loom_pipeline_runs_started_total}. The
 * §3 tables of {@code spec/features/ops/METRICS.md} document the <b>exported</b> names, and
 * {@code MetricsCatalogScrapeTest} enforces that against a real scrape. This projection applies the
 * same convention, so the REST snapshot and the Prometheus scrape agree on every name;
 * {@code MetricsSnapshotCatalogTest} holds it to that.
 * </p>
 *
 * <h2>What is included</h2>
 *
 * <p>
 * Only names beginning with {@code loom_} — this is the Loom domain catalog, not a second scrape
 * endpoint. JVM, process and Vert.x families stay on the monitoring port's {@code /metrics}, which
 * is where a Prometheus belongs; a browser dashboard has no use for them and they would dominate the
 * payload.
 * </p>
 */
public final class MetricsSnapshot {

	private MetricsSnapshot() {
	}

	/** Prefix that marks a meter as belonging to the Loom domain catalog. */
	public static final String CATALOG_PREFIX = "loom_";

	/**
	 * @param registry the shared meter registry
	 * @return one record per name+tag series of the Loom catalog, sorted by name then label set
	 */
	public static List<MetricRecord> of(MeterRegistry registry) {
		List<MetricRecord> records = new ArrayList<>();
		for (Meter meter : registry.getMeters()) {
			if (!meter.getId().getName().startsWith(CATALOG_PREFIX)) {
				continue;
			}
			records.add(toRecord(meter));
		}
		records.sort(Comparator.comparing(MetricRecord::getName).thenComparing(r -> r.getTags().toString()));
		return records;
	}

	private static MetricRecord toRecord(Meter meter) {
		MetricRecord record = new MetricRecord()
			.setName(scrapedName(meter))
			.setType(typeOf(meter))
			.setTags(tagsOf(meter));

		if (meter instanceof Timer timer) {
			long count = timer.count();
			double sum = timer.totalTime(TimeUnit.SECONDS);
			return record
				.setCount(count)
				.setSumSeconds(finite(sum))
				.setMaxSeconds(finite(timer.max(TimeUnit.SECONDS)))
				.setMeanSeconds(count == 0 ? 0d : finite(sum / count));
		}
		if (meter instanceof Counter counter) {
			return record.setValue(finite(counter.count()));
		}
		if (meter instanceof Gauge gauge) {
			// A gauge whose supplier has nothing to report reads NaN. Null says "no reading" in JSON;
			// NaN is not valid JSON at all and would take the whole response down with it.
			return record.setValue(finite(gauge.value()));
		}
		// Nothing else is in the catalog today. Report the first measurement rather than dropping the
		// series silently, so a new meter type shows up as a value instead of as a hole.
		for (Measurement measurement : meter.measure()) {
			return record.setValue(finite(measurement.getValue()));
		}
		return record;
	}

	/**
	 * The name Prometheus would export this meter under.
	 *
	 * @param meter the meter
	 * @return the meter id with the {@code _total} / {@code _seconds} suffix the exposition adds
	 */
	public static String scrapedName(Meter meter) {
		String name = meter.getId().getName();
		return switch (meter.getId().getType()) {
			case COUNTER -> name.endsWith("_total") ? name : name + "_total";
			case TIMER, LONG_TASK_TIMER -> name.endsWith("_seconds") ? name : name + "_seconds";
			default -> name;
		};
	}

	private static String typeOf(Meter meter) {
		return switch (meter.getId().getType()) {
			case COUNTER -> "COUNTER";
			case GAUGE -> "GAUGE";
			case TIMER, LONG_TASK_TIMER -> "TIMER";
			case DISTRIBUTION_SUMMARY -> "SUMMARY";
			default -> "OTHER";
		};
	}

	private static Map<String, String> tagsOf(Meter meter) {
		Map<String, String> tags = new LinkedHashMap<>();
		for (Tag tag : meter.getId().getTagsAsIterable()) {
			tags.put(tag.getKey(), tag.getValue());
		}
		return tags;
	}

	private static Double finite(double value) {
		return Double.isFinite(value) ? Double.valueOf(value) : null;
	}
}
