package io.metaloom.cortex.pipeline.common.event;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.cortex.pipeline.api.event.NodeCompletionEvent;
import io.metaloom.cortex.pipeline.api.event.PipelineEventBus;

/**
 * CompletableFuture-compatible event bus implementation using concurrent data structures.
 * Events are dispatched synchronously on the publishing thread for low-latency communication
 * between pipeline nodes.
 */
public class DefaultPipelineEventBus implements PipelineEventBus {

	private static final Logger log = LoggerFactory.getLogger(DefaultPipelineEventBus.class);

	private final ConcurrentHashMap<String, List<SubscriptionEntry>> nodeSubscriptions = new ConcurrentHashMap<>();
	private final List<SubscriptionEntry> globalSubscriptions = new CopyOnWriteArrayList<>();
	private final ConcurrentHashMap<String, SubscriptionEntry> handleIndex = new ConcurrentHashMap<>();

	@Override
	public void publish(NodeCompletionEvent event) {
		String nodeId = event.getNodeId();
		log.debug("Publishing completion event for node: {}", nodeId);

		// Notify node-specific subscribers
		List<SubscriptionEntry> subs = nodeSubscriptions.get(nodeId);
		if (subs != null) {
			for (SubscriptionEntry entry : subs) {
				try {
					entry.listener.accept(event);
				} catch (Exception e) {
					log.error("Error in event listener for node {}: {}", nodeId, e.getMessage(), e);
				}
			}
		}

		// Notify global subscribers
		for (SubscriptionEntry entry : globalSubscriptions) {
			try {
				entry.listener.accept(event);
			} catch (Exception e) {
				log.error("Error in global event listener: {}", e.getMessage(), e);
			}
		}
	}

	@Override
	public String subscribe(String nodeId, Consumer<NodeCompletionEvent> listener) {
		String handle = UUID.randomUUID().toString();
		SubscriptionEntry entry = new SubscriptionEntry(handle, nodeId, listener);
		nodeSubscriptions.computeIfAbsent(nodeId, k -> new CopyOnWriteArrayList<>()).add(entry);
		handleIndex.put(handle, entry);
		return handle;
	}

	@Override
	public String subscribeAll(Consumer<NodeCompletionEvent> listener) {
		String handle = UUID.randomUUID().toString();
		SubscriptionEntry entry = new SubscriptionEntry(handle, null, listener);
		globalSubscriptions.add(entry);
		handleIndex.put(handle, entry);
		return handle;
	}

	@Override
	public void unsubscribe(String handle) {
		SubscriptionEntry entry = handleIndex.remove(handle);
		if (entry != null) {
			if (entry.nodeId != null) {
				List<SubscriptionEntry> subs = nodeSubscriptions.get(entry.nodeId);
				if (subs != null) {
					subs.remove(entry);
				}
			} else {
				globalSubscriptions.remove(entry);
			}
		}
	}

	@Override
	public void clear() {
		nodeSubscriptions.clear();
		globalSubscriptions.clear();
		handleIndex.clear();
	}

	private static class SubscriptionEntry {
		final String handle;
		final String nodeId;
		final Consumer<NodeCompletionEvent> listener;

		SubscriptionEntry(String handle, String nodeId, Consumer<NodeCompletionEvent> listener) {
			this.handle = handle;
			this.nodeId = nodeId;
			this.listener = listener;
		}
	}
}
