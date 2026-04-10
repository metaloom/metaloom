package io.metaloom.cortex.pipeline.api.event;

import java.util.function.Consumer;

/**
 * Internal event bus for pipeline communication and tracking.
 *
 * <p>Supports two event channels:</p>
 * <ul>
 *   <li><b>Node completion events</b> — full-fidelity events carrying {@link NodeCompletionEvent}
 *       with the complete {@code LoomMedia} and {@code NodeResult}. Used for internal pipeline
 *       coordination (sync collection, caching).</li>
 *   <li><b>Tracking events</b> — lightweight {@link PipelineTrackingEvent} instances designed
 *       for high-volume, low-overhead observability. These carry only scalar data and are suitable
 *       for forwarding over WebSocket to UI clients.</li>
 * </ul>
 */
public interface PipelineEventBus {

	/**
	 * Publish a node completion event.
	 *
	 * @param event the completion event
	 */
	void publish(NodeCompletionEvent event);

	/**
	 * Publish a lightweight tracking event.
	 *
	 * @param event the tracking event
	 */
	void publishTracking(PipelineTrackingEvent event);

	/**
	 * Subscribe to events for a specific node.
	 *
	 * @param nodeId   the node id to listen for
	 * @param listener callback invoked when the specified node completes
	 * @return a handle that can be used to unsubscribe
	 */
	String subscribe(String nodeId, Consumer<NodeCompletionEvent> listener);

	/**
	 * Unsubscribe a previously registered listener.
	 *
	 * @param handle the subscription handle
	 */
	void unsubscribe(String handle);

	/**
	 * Subscribe to all node completion events.
	 *
	 * @param listener callback invoked for every node completion
	 * @return a handle that can be used to unsubscribe
	 */
	String subscribeAll(Consumer<NodeCompletionEvent> listener);

	/**
	 * Subscribe to all tracking events.
	 *
	 * @param listener callback invoked for every tracking event
	 * @return a handle that can be used to unsubscribe
	 */
	String subscribeTracking(Consumer<PipelineTrackingEvent> listener);

	/**
	 * Clear all subscriptions.
	 */
	void clear();
}
