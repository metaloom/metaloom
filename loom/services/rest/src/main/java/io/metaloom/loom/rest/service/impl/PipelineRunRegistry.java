package io.metaloom.loom.rest.service.impl;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.loom.common.metrics.LoomMetrics;
import io.metaloom.loom.common.metrics.NoopLoomMetrics;
import io.metaloom.loom.pipeline.engine.PipelineRunEngine;

/**
 * Tracks the engines of runs that are currently executing, so inbound processor
 * messages can be routed back to the run they belong to.
 *
 * <p><strong>Phase 1: in memory.</strong> A Loom restart therefore loses every
 * in-flight run - the engine state it would need to resume does not exist anywhere
 * else yet. Making run state durable is Phase 2 and is what turns this registry
 * into a cache rather than the source of truth.</p>
 */
@Singleton
public class PipelineRunRegistry {

	private static final Logger log = LoggerFactory.getLogger(PipelineRunRegistry.class);

	private final Map<UUID, PipelineRunEngine> engines = new ConcurrentHashMap<>();

	/**
	 * Bind the fleet-level depth gauges.
	 *
	 * <p>This registry, not the engine, is where they belong. A run's in-flight count is a property
	 * of one run, and a run is identified by a UUID — labelling a series with it would put unbounded
	 * cardinality on the registry, which is the one rule metrics here never break. Summed across
	 * live runs the same numbers answer the question an operator actually has: is the fleet
	 * saturated, and would more workers help?</p>
	 *
	 * <p>Ceiling as well as depth, because depth alone cannot distinguish the two. 200 outstanding
	 * tasks is a busy fleet against a ceiling of 512 and a queue against a ceiling of 200.</p>
	 */
	@Inject
	public PipelineRunRegistry(LoomMetrics metrics) {
		metrics.bindGauge("loom_node_tasks_inflight", this::totalInFlight);
		metrics.bindGauge("loom_node_tasks_inflight_ceiling", this::totalCeiling);
		metrics.bindGauge("loom_pipeline_runs_active", this::activeRunCount);
	}

	/** Test convenience: a registry without a metrics backend. */
	public PipelineRunRegistry() {
		this(NoopLoomMetrics.INSTANCE);
	}

	/** @return node tasks outstanding across every live run */
	private int totalInFlight() {
		int total = 0;
		for (PipelineRunEngine engine : engines.values()) {
			total += engine.getInFlightCount();
		}
		return total;
	}

	/**
	 * @return the summed in-flight ceilings of every live run. A run configured as unlimited
	 *         contributes nothing, so an all-unlimited fleet reads 0 rather than pretending to a
	 *         capacity it does not enforce.
	 */
	private int totalCeiling() {
		int total = 0;
		for (PipelineRunEngine engine : engines.values()) {
			total += Math.max(0, engine.getMaxInFlight());
		}
		return total;
	}

	/**
	 * Register a run and arrange for it to be dropped once it completes.
	 *
	 * @param runUuid the run
	 * @param engine  its engine
	 */
	public void register(UUID runUuid, PipelineRunEngine engine) {
		engines.put(runUuid, engine);
		// Self-cleaning: without this the map grows for the lifetime of the process.
		engine.onCompletion(summary -> {
			engines.remove(runUuid);
			log.debug("Run {} finished and was unregistered: {}", runUuid, summary);
		});
	}

	/**
	 * @param runUuid the run
	 * @return its engine, or null when the run is unknown or already finished
	 */
	public PipelineRunEngine get(UUID runUuid) {
		return engines.get(runUuid);
	}

	public void unregister(UUID runUuid) {
		engines.remove(runUuid);
	}

	public int activeRunCount() {
		return engines.size();
	}
}
