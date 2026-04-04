package io.metaloom.cortex.pipeline.api.event;

import java.util.function.Consumer;

/**
 * Simple internal event bus for pipeline node communication.
 * Nodes publish completion events and downstream nodes subscribe to be notified
 * when their dependencies are satisfied.
 */
public interface PipelineEventBus {

	/**
	 * Publish a node completion event.
	 *
	 * @param event the completion event
	 */
	void publish(NodeCompletionEvent event);

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
	 * Clear all subscriptions.
	 */
	void clear();
}
