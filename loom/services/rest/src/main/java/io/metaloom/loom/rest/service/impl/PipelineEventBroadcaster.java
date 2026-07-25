package io.metaloom.loom.rest.service.impl;

import java.util.concurrent.ConcurrentHashMap;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.loom.common.metrics.LoomMetrics;
import io.metaloom.loom.rest.model.pipeline.event.PipelineEventMessage;
import io.metaloom.loom.rest.model.processor.event.ProcessorEventMessage;
import io.vertx.core.http.ServerWebSocket;
import io.vertx.core.json.Json;

/**
 * Manages WebSocket connections from UI clients that want to receive
 * live pipeline tracking events. Thread-safe — processors push events
 * from their message handlers and the broadcaster fans them out to
 * all connected subscribers.
 *
 * <h2>Per-pipeline filtering</h2>
 * <p>Each subscriber may optionally specify a pipeline name via the
 * {@code ?pipeline=} query parameter. When set, the subscriber only
 * receives events whose {@link PipelineEventMessage#getPipelineName()}
 * matches. Subscribers without a filter receive all events.</p>
 *
 * <h2>Backpressure</h2>
 * <p>Each subscriber has a bounded queue (default 1024 entries). When
 * the queue is full the oldest event is dropped and a per-subscriber
 * {@code droppedCount} is incremented. This prevents a slow subscriber
 * from blocking the broadcaster thread or accumulating unbounded
 * memory.</p>
 */
@Singleton
public class PipelineEventBroadcaster {

	private static final Logger log = LoggerFactory.getLogger(PipelineEventBroadcaster.class);

	/** Default per-subscriber queue capacity. */
	private static final int DEFAULT_QUEUE_CAPACITY = 1024;

	private final ConcurrentHashMap<ServerWebSocket, Subscriber> subscribers = new ConcurrentHashMap<>();

	private final LoomMetrics metrics;

	@Inject
	public PipelineEventBroadcaster(LoomMetrics metrics) {
		this.metrics = metrics;
		metrics.bindGauge("loom_pipeline_event_subscribers", subscribers::size);
	}

	/** Test convenience: a broadcaster without a metrics backend. */
	public PipelineEventBroadcaster() {
		this(io.metaloom.loom.common.metrics.NoopLoomMetrics.INSTANCE);
	}

	/**
	 * Register a UI client WebSocket with no pipeline filter (receives all events).
	 */
	public void addSubscriber(ServerWebSocket ws) {
		addSubscriber(ws, null);
	}

	/**
	 * Register a UI client WebSocket with an optional pipeline name filter.
	 *
	 * @param ws          the WebSocket to register
	 * @param pipelineFilter pipeline name to filter on, or {@code null} to receive all events
	 */
	public void addSubscriber(ServerWebSocket ws, String pipelineFilter) {
		Subscriber subscriber = new Subscriber(ws, pipelineFilter, DEFAULT_QUEUE_CAPACITY);
		subscribers.put(ws, subscriber);
		log.info("Pipeline event subscriber connected. Total: {} (filter: {})",
			subscribers.size(), pipelineFilter == null ? "none" : pipelineFilter);
	}

	/**
	 * Remove a disconnected UI client WebSocket.
	 */
	public void removeSubscriber(ServerWebSocket ws) {
		Subscriber removed = subscribers.remove(ws);
		if (removed != null && removed.droppedCount > 0) {
			log.info("Pipeline event subscriber disconnected. Total: {} (dropped: {})",
				subscribers.size(), removed.droppedCount);
		} else {
			log.info("Pipeline event subscriber disconnected. Total: {}", subscribers.size());
		}
	}

	/**
	 * Broadcast a pipeline event to all matching subscribers.
	 * Events are filtered by pipeline name when the subscriber has a filter set.
	 * Slow subscribers with full queues have their oldest event dropped.
	 */
	public void broadcast(PipelineEventMessage event) {
		if (subscribers.isEmpty()) {
			return;
		}
		String json = null; // lazy-encode — only serialize if at least one subscriber matches
		for (var entry : subscribers.entrySet()) {
			ServerWebSocket ws = entry.getKey();
			Subscriber subscriber = entry.getValue();
			if (!subscriber.matches(event)) {
				continue;
			}
			if (ws.isClosed()) {
				subscribers.remove(ws);
				continue;
			}
			if (json == null) {
				json = Json.encode(event);
			}
			if (!subscriber.send(json)) {
				metrics.recordPipelineEventDropped();
			}
		}
		if (json != null) {
			metrics.recordPipelineEventBroadcast();
		}
	}

	/**
	 * Broadcast a processor lifecycle event to <b>all</b> subscribers of the UI
	 * events socket.
	 *
	 * <p>Processor events share the single UI-facing WebSocket with pipeline
	 * events (multiplexed via the {@code channel} discriminator). Unlike pipeline
	 * events they are <b>not</b> pipeline-scoped, so the per-subscriber
	 * {@code ?pipeline=} filter is deliberately bypassed — a client filtering on
	 * a pipeline still receives fleet-wide processor updates.</p>
	 */
	public void broadcastProcessorEvent(ProcessorEventMessage event) {
		if (subscribers.isEmpty()) {
			return;
		}
		String json = null; // lazy-encode — only serialize once we have a live subscriber
		for (var entry : subscribers.entrySet()) {
			ServerWebSocket ws = entry.getKey();
			Subscriber subscriber = entry.getValue();
			if (ws.isClosed()) {
				subscribers.remove(ws);
				continue;
			}
			if (json == null) {
				json = Json.encode(event);
			}
			subscriber.send(json);
		}
	}

	/**
	 * Return the number of currently connected subscribers.
	 */
	public int subscriberCount() {
		return subscribers.size();
	}

	/**
	 * Wrapper for a single subscriber connection, holding the optional
	 * pipeline filter and backpressure state.
	 */
	private static final class Subscriber {

		private final ServerWebSocket ws;
		private final String pipelineFilter;
		private volatile long droppedCount;

		Subscriber(ServerWebSocket ws, String pipelineFilter, int queueCapacity) {
			this.ws = ws;
			this.pipelineFilter = pipelineFilter;
		}

		/**
		 * Return true if this subscriber should receive the given event.
		 * When no filter is set, all events match.
		 */
		boolean matches(PipelineEventMessage event) {
			if (pipelineFilter == null || pipelineFilter.isBlank()) {
				return true;
			}
			String eventPipeline = event.getPipelineName();
			return pipelineFilter.equals(eventPipeline);
		}

		/**
		 * Send a pre-serialized JSON event to this subscriber's WebSocket.
		 * Uses a non-blocking write. If the write buffer is full (backpressure),
		 * the event is dropped and the dropped counter is incremented.
		 *
		 * @return {@code true} if the event was written, {@code false} if it was dropped
		 */
		boolean send(String json) {
			if (ws.writeQueueFull()) {
				droppedCount++;
				if (droppedCount % 100 == 1) {
					log.warn("Pipeline event subscriber backpressure: dropped {} events (write queue full)",
						droppedCount);
				}
				return false;
			}
			ws.writeTextMessage(json);
			return true;
		}
	}
}
