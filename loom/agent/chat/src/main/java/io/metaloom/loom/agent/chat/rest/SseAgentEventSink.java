package io.metaloom.loom.agent.chat.rest;

import io.metaloom.loom.agent.chat.event.AgentEvent;
import io.metaloom.loom.agent.chat.event.AgentEventSink;
import io.vertx.core.Context;
import io.vertx.core.http.HttpServerResponse;

/**
 * Writes agent events onto an HTTP response as Server-Sent Events. Events arrive from a worker thread and are marshalled back onto the request's event loop
 * context. The SSE headers are written lazily on the first event so early failures (e.g. busy chats) can still produce a regular error response.
 */
public class SseAgentEventSink implements AgentEventSink {

	private final Context context;
	private final HttpServerResponse response;

	private boolean headersSent = false;

	public SseAgentEventSink(Context context, HttpServerResponse response) {
		this.context = context;
		this.response = response;
	}

	@Override
	public void emit(AgentEvent event) {
		String frame = "event: " + event.type().wireName() + "\ndata: " + event.data().encode() + "\n\n";
		context.runOnContext(v -> {
			if (response.closed()) {
				return;
			}
			ensureHeaders();
			response.write(frame);
		});
	}

	/**
	 * Whether the SSE headers have been written (i.e. at least one event was emitted).
	 */
	public boolean headersSent() {
		return headersSent;
	}

	/**
	 * End the SSE response. Safe to call from any thread.
	 */
	public void end() {
		context.runOnContext(v -> {
			if (response.closed() || response.ended()) {
				return;
			}
			ensureHeaders();
			response.end();
		});
	}

	private void ensureHeaders() {
		if (headersSent) {
			return;
		}
		headersSent = true;
		response.putHeader("Content-Type", "text/event-stream");
		response.putHeader("Cache-Control", "no-cache");
		// Ask reverse proxies (nginx) not to buffer the stream
		response.putHeader("X-Accel-Buffering", "no");
		response.setChunked(true);
	}

}
