package io.metaloom.cortex.api.node;

/**
 * Result of processing by a {@link CortexNode}. Carries a typed output value {@code O}
 * along with state information (success, skipped, failed).
 *
 * @param <O> the output type produced by the node
 */
public class NodeResult<O> {

	private final ResultState state;
	private final O output;
	private long duration = 0;

	public NodeResult(ResultState state, O output) {
		this.state = state;
		this.output = output;
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

	public void setStart(long start) {
		this.duration = System.currentTimeMillis() - start;
	}

	public long getDuration() {
		return duration;
	}

	public static <O> NodeResult<O> success(O output) {
		return new NodeResult<>(ResultState.SUCCESS, output);
	}

	public static <O> NodeResult<O> failed() {
		return new NodeResult<>(ResultState.FAILED, null);
	}

	public static <O> NodeResult<O> skipped() {
		return new NodeResult<>(ResultState.SKIPPED, null);
	}

	@Override
	public String toString() {
		return state.name() + (output != null ? " [" + output + "]" : "");
	}
}