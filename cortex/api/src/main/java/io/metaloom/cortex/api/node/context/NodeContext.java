package io.metaloom.cortex.api.node.context;

import java.util.Collections;
import java.util.Map;

import io.metaloom.cortex.api.node.NodeOutputKey;
import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.ResultOrigin;
import io.metaloom.cortex.api.node.context.impl.NodeContextImpl;
import io.metaloom.cortex.api.media.LoomMedia;

/**
 * Context for a single node invocation. Wraps the typed input {@code I} and accumulates
 * metadata about the processing (origin, skip reason, timing, output data).
 *
 * @param <I> the input type
 */
public interface NodeContext<I> {

	static NodeContext<LoomMedia> create(LoomMedia media) {
		return new NodeContextImpl<>(media);
	}

	static NodeContext<LoomMedia> create(LoomMedia media, Map<String, Map<String, Object>> upstreamOutputs) {
		return new NodeContextImpl<>(media, upstreamOutputs);
	}

	/**
	 * Returns the typed input for this node invocation.
	 */
	I input();

	/**
	 * Returns the underlying {@link LoomMedia} (convenience accessor).
	 */
	LoomMedia media();

	/**
	 * Returns the time in milliseconds since the creation of this context.
	 */
	long duration();

	NodeContext<I> skipped(String reason);

	NodeContext<I> origin(ResultOrigin origin);

	ResultOrigin origin();

	NodeContext<I> failure(String cause);

	/**
	 * Accumulate an output key-value pair using a typed {@link NodeOutputKey}.
	 *
	 * @param key   the typed output key
	 * @param value the output value
	 * @return this context for chaining
	 */
	<T> NodeContext<I> output(NodeOutputKey<T> key, T value);

	/**
	 * Accumulate an output key-value pair. These outputs will be carried in the
	 * {@link NodeResult} and made available to downstream dependent nodes.
	 *
	 * @param key   the output key (e.g. "fingerprint", "sha256")
	 * @param value the output value
	 * @return this context for chaining
	 */
	NodeContext<I> output(String key, Object value);

	/**
	 * Returns the accumulated output map.
	 */
	Map<String, Object> outputs();

	/**
	 * Returns the outputs from upstream (dependency) nodes, keyed by node id.
	 * Each value is the output map of that upstream node.
	 */
	default Map<String, Map<String, Object>> upstreamOutputs() {
		return Collections.emptyMap();
	}

	/**
	 * Convenience: get a specific output from an upstream node.
	 *
	 * @param nodeId the upstream node id
	 * @param key    the output key
	 * @return the value, or null if not present
	 */
	@SuppressWarnings("unchecked")
	default <T> T upstreamOutput(String nodeId, String key) {
		Map<String, Object> nodeOutputs = upstreamOutputs().get(nodeId);
		return nodeOutputs != null ? (T) nodeOutputs.get(key) : null;
	}

	NodeResult next();

	NodeResult abort();

	NodeContext<I> print(String string, String string2);

	NodeContext<I> info(String msg);
}
