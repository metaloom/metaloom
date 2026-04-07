package io.metaloom.cortex.api.node;

import java.util.Collections;
import java.util.Map;

/**
 * Result of processing by a {@link CortexNode}. Carries a typed output value {@code O}
 * along with state information (success, skipped, failed) and an output map
 * for passing data to downstream nodes.
 *
 * @param <O> the output type produced by the node
 */
public class NodeResult<O> {

	private final ResultState state;
	private final O output;
	private final Map<String, Object> outputs;
	private long duration = 0;

	public NodeResult(ResultState state, O output) {
		this(state, output, Collections.emptyMap());
	}

	public NodeResult(ResultState state, O output, Map<String, Object> outputs) {
		this.state = state;
		this.output = output;
		this.outputs = outputs != null ? outputs : Collections.emptyMap();
	}

	public ResultState getState() {
		return state;
	}

	/**
	 * Return the computed output value, or {@code null} if the node was skipped or failed.
	 */
	public O getOutput() {
		return output;
	}

	/**
	 * Return the output map containing all key-value pairs produced by the node.
	 * This data is forwarded to downstream dependent nodes via the pipeline.
	 */
	public Map<String, Object> getOutputs() {
		return outputs;
	}

	/**
	 * Convenience accessor for a single output value from the outputs map.
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

	public static <O> NodeResult<O> success(O output) {
		return new NodeResult<>(ResultState.SUCCESS, output, Collections.emptyMap());
	}

	public static <O> NodeResult<O> success(O output, Map<String, Object> outputs) {
		return new NodeResult<>(ResultState.SUCCESS, output, outputs);
	}

	public static <O> NodeResult<O> failed() {
		return new NodeResult<>(ResultState.FAILED, null);
	}

	public static <O> NodeResult<O> skipped() {
		return new NodeResult<>(ResultState.SKIPPED, null);
	}

	@Override
	public String toString() {
		return state.name() + (output != null ? " [" + output + "]" : "")
			+ (outputs != null && !outputs.isEmpty() ? " outputs=" + outputs.keySet() : "");
	}
}