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
import io.metaloom.cortex.pipeline.api.event.PipelineTrackingEvent;

/**
 * Event bus implementation using concurrent data structures.
 * Events are dispatched synchronously on the publishing thread for low-latency communication
 * between pipeline nodes and tracking subscribers.
 */
public class DefaultPipelineEventBus implements PipelineEventBus {

	private static final Logger log = LoggerFactory.getLogger(DefaultPipelineEventBus.class);

	private final ConcurrentHashMap<String, List<SubscriptionEntry<NodeCompletionEvent>>> nodeSubscriptions = new ConcurrentHashMap<>();
	private final List<SubscriptionEntry<NodeCompletionEvent>> globalSubscriptions = new CopyOnWriteArrayList<>();
	private final List<SubscriptionEntry<PipelineTrackingEvent>> trackingSubscriptions = new CopyOnWriteArrayList<>();
	private final ConcurrentHashMap<String, Runnable> handleCleanup = new ConcurrentHashMap<>();

	@Override
	public void publish(NodeCompletionEvent event) {
		String nodeId = event.getNodeId();
		log.debug("Publishing completion event for node: {}", nodeId);

		// Notify node-specific subscribers
		List<SubscriptionEntry<NodeCompletionEvent>> subs = nodeSubscriptions.get(nodeId);
		if (subs != null) {
			for (SubscriptionEntry<NodeCompletionEvent> entry : subs) {
				try {
					entry.listener.accept(event);
				} catch (Exception e) {
					log.error("Error in event listener for node {}: {}", nodeId, e.getMessage(), e);
				}
			}
		}

		// Notify global subscribers
		for (SubscriptionEntry<NodeCompletionEvent> entry : globalSubscriptions) {
			try {
				entry.listener.accept(event);
			} catch (Exception e) {
				log.error("Error in global event listener: {}", e.getMessage(), e);
			}
		}
	}

	@Override
	public void publishTracking(PipelineTrackingEvent event) {
		log.debug("Publishing tracking event: {}", event);
		for (SubscriptionEntry<PipelineTrackingEvent> entry : trackingSubscriptions) {
			try {
				entry.listener.accept(event);
			} catch (Exception e) {
				log.error("Error in tracking event listener: {}", e.getMessage(), e);
			}
		}
	}

	@Override
	public String subscribe(String nodeId, Consumer<NodeCompletionEvent> listener) {
		String handle = UUID.randomUUID().toString();
		SubscriptionEntry<NodeCompletionEvent> entry = new SubscriptionEntry<>(handle, listener);
		List<SubscriptionEntry<NodeCompletionEvent>> subs = nodeSubscriptions.computeIfAbsent(nodeId,
				k -> new CopyOnWriteArrayList<>());
		subs.add(entry);
		handleCleanup.put(handle, () -> subs.remove(entry));
		return handle;
	}

	@Override
	public String subscribeAll(Consumer<NodeCompletionEvent> listener) {
		String handle = UUID.randomUUID().toString();
		SubscriptionEntry<NodeCompletionEvent> entry = new SubscriptionEntry<>(handle, listener);
		globalSubscriptions.add(entry);
		handleCleanup.put(handle, () -> globalSubscriptions.remove(entry));
		return handle;
	}

	@Override
	public String subscribeTracking(Consumer<PipelineTrackingEvent> listener) {
		String handle = UUID.randomUUID().toString();
		SubscriptionEntry<PipelineTrackingEvent> entry = new SubscriptionEntry<>(handle, listener);
		trackingSubscriptions.add(entry);
		handleCleanup.put(handle, () -> trackingSubscriptions.remove(entry));
		return handle;
	}

	@Override
	public void unsubscribe(String handle) {
		Runnable cleanup = handleCleanup.remove(handle);
		if (cleanup != null) {
			cleanup.run();
		}
	}

	@Override
	public void clear() {
		nodeSubscriptions.clear();
		globalSubscriptions.clear();
		trackingSubscriptions.clear();
		handleCleanup.clear();
	}

	private static class SubscriptionEntry<E> {
		final String handle;
		final Consumer<E> listener;

		SubscriptionEntry(String handle, Consumer<E> listener) {
			this.handle = handle;
			this.listener = listener;
		}
	}
}
