package io.metaloom.cortex.api.node;

import java.util.Collections;
import java.util.Map;

/**
 * Result of processing by a {@link CortexNode}. Carries state information
 * (success, skipped, failed) and a typed output map for passing data to
 * downstream nodes.
 *
 * <p>Output values are stored under {@link NodeOutputKey} keys which provide
 * type-safe access without casts.
 */
public class NodeResult {

	private final ResultState state;
	private final Map<String, Object> outputs;
	private long duration = 0;

	public NodeResult(ResultState state) {
		this(state, Collections.emptyMap());
	}

	public NodeResult(ResultState state, Map<String, Object> outputs) {
		this.state = state;
		this.outputs = outputs != null ? outputs : Collections.emptyMap();
	}

	public ResultState getState() {
		return state;
	}

	/**
	 * Return the output map containing all key-value pairs produced by the node.
	 * This data is forwarded to downstream dependent nodes via the pipeline.
	 */
	public Map<String, Object> getOutputs() {
		return outputs;
	}

	/**
	 * Type-safe accessor for a single output value via a {@link NodeOutputKey}.
	 */
	@SuppressWarnings("unchecked")
	public <T> T get(NodeOutputKey<T> key) {
		return (T) outputs.get(key.key());
	}

	/**
	 * Check whether the output map contains a value for the given key.
	 */
	public boolean has(NodeOutputKey<?> key) {
		return outputs.containsKey(key.key());
	}

	/**
	 * Convenience accessor for a single output value by raw string key.
	 */
	@SuppressWarnings("unchecked")
	public <T> T getOutput(String key) {
		return (T) outputs.get(key);
	}

	public void setStart(long start) {
		this.duration = System.currentTimeMillis() - start;
	}

	public long getDuration() {
		return duration;
	}

	public static NodeResult success() {
		return new NodeResult(ResultState.SUCCESS, Collections.emptyMap());
	}

	public static NodeResult success(Map<String, Object> outputs) {
		return new NodeResult(ResultState.SUCCESS, outputs);
	}

	public static NodeResult failed() {
		return new NodeResult(ResultState.FAILED);
	}

	public static NodeResult skipped() {
		return new NodeResult(ResultState.SKIPPED);
	}

	@Override
	public String toString() {
		return state.name()
			+ (outputs != null && !outputs.isEmpty() ? " outputs=" + outputs.keySet() : "");
	}
}