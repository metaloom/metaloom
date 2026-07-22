package io.metaloom.loom.ai.event;

/**
 * Consumer of agent run events. The SSE response writer implements this on the REST side; tests collect the events into a list.
 *
 * <p>Implementations must be safe to call from worker threads — the agentic loop runs on a worker, not on the event loop.</p>
 */
@FunctionalInterface
public interface AgentEventSink {

	void emit(AgentEvent event);

}
