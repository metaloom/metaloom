package io.metaloom.loom.pipeline.engine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import io.metaloom.loom.common.metrics.NoopLoomMetrics;

/**
 * A {@link io.metaloom.loom.common.metrics.LoomMetrics} that remembers what it was told.
 *
 * <p>
 * The engine reports through the catalog interface and never sees Micrometer, so this is all a test
 * needs to prove an instrumentation site fires — with the labels it claims, and the number of times
 * it claims. Extends {@link NoopLoomMetrics} so the helpers a given test does not care about stay
 * silent rather than having to be stubbed one by one.
 * </p>
 */
public class RecordingLoomMetrics extends NoopLoomMetrics {

	/** One recorded observation of the dispatch-to-result timer. */
	public record Latency(String kind, String state, long durationMs) {
	}

	private final List<Latency> latencies = new ArrayList<>();
	private final Map<String, Integer> retried = new LinkedHashMap<>();
	private final Map<String, Integer> deadlettered = new LinkedHashMap<>();
	private final Map<String, Integer> trips = new LinkedHashMap<>();
	private final Map<String, Supplier<Number>> gauges = new LinkedHashMap<>();

	@Override
	public void recordNodeTaskLatency(String kind, String state, long durationMs) {
		latencies.add(new Latency(kind, state, durationMs));
	}

	@Override
	public void recordNodeTaskRetried(String kind) {
		retried.merge(kind, 1, Integer::sum);
	}

	@Override
	public void recordNodeTaskDeadlettered(String kind) {
		deadlettered.merge(kind, 1, Integer::sum);
	}

	@Override
	public void recordCircuitBreakerTrip(String kind) {
		trips.merge(kind, 1, Integer::sum);
	}

	@Override
	public void bindGauge(String name, Supplier<Number> supplier) {
		gauges.put(name, supplier);
	}

	@Override
	public void bindGauge(String name, String tagKey, String tagValue, Supplier<Number> supplier) {
		// Keyed exactly as Micrometer keys a meter - by name *and* tags - so a test can catch a
		// binding that would have collapsed a whole family onto one series.
		gauges.put(name + "{" + tagKey + "=" + tagValue + "}", supplier);
	}

	/** @return every dispatch-to-result observation, in the order it was recorded */
	public List<Latency> latencies() {
		return latencies;
	}

	public List<Latency> latenciesForKind(String kind) {
		return latencies.stream().filter(l -> l.kind().equals(kind)).toList();
	}

	public int retried(String kind) {
		return retried.getOrDefault(kind, 0);
	}

	public int deadlettered(String kind) {
		return deadlettered.getOrDefault(kind, 0);
	}

	public int trips(String kind) {
		return trips.getOrDefault(kind, 0);
	}

	/** @return the bound gauge names, tagged ones rendered as {@code name{key=value}} */
	public java.util.Set<String> gaugeNames() {
		return gauges.keySet();
	}

	/**
	 * @param name the bound name, tagged ones as {@code name{key=value}}
	 * @return what the gauge reads now
	 * @throws AssertionError when nothing bound that name
	 */
	public double gauge(String name) {
		Supplier<Number> supplier = gauges.get(name);
		if (supplier == null) {
			throw new AssertionError("No gauge is bound as '" + name + "'. Bound: " + gauges.keySet());
		}
		return supplier.get().doubleValue();
	}
}
