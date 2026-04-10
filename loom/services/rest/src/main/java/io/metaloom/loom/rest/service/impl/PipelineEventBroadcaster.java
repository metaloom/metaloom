package io.metaloom.loom.rest.service.impl;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.loom.rest.model.pipeline.event.PipelineEventMessage;
import io.vertx.core.http.ServerWebSocket;
import io.vertx.core.json.Json;

/**
 * Manages WebSocket connections from UI clients that want to receive
 * live pipeline tracking events. Thread-safe — processors push events
 * from their message handlers and the broadcaster fans them out to
 * all connected subscribers.
 */
@Singleton
public class PipelineEventBroadcaster {

	private static final Logger log = LoggerFactory.getLogger(PipelineEventBroadcaster.class);

	private final Set<ServerWebSocket> subscribers = ConcurrentHashMap.newKeySet();

	@Inject
	public PipelineEventBroadcaster() {
	}

	/**
	 * Register a UI client WebSocket.
	 */
	public void addSubscriber(ServerWebSocket ws) {
		subscribers.add(ws);
		log.info("Pipeline event subscriber connected. Total: {}", subscribers.size());
	}

	/**
	 * Remove a disconnected UI client WebSocket.
	 */
	public void removeSubscriber(ServerWebSocket ws) {
		subscribers.remove(ws);
		log.info("Pipeline event subscriber disconnected. Total: {}", subscribers.size());
	}

	/**
	 * Broadcast a pipeline event to all connected UI clients.
	 * Silently removes clients whose WebSocket has been closed.
	 */
	public void broadcast(PipelineEventMessage event) {
		if (subscribers.isEmpty()) {
			return;
		}
		String json = Json.encode(event);
		for (ServerWebSocket ws : subscribers) {
			if (!ws.isClosed()) {
				ws.writeTextMessage(json);
			} else {
				subscribers.remove(ws);
			}
		}
	}

	/**
	 * Return the number of currently connected subscribers.
	 */
	public int subscriberCount() {
		return subscribers.size();
	}
}
