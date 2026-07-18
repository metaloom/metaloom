package io.metaloom.loom.rest.service.impl;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

	@Inject
	public PipelineRunRegistry() {
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
