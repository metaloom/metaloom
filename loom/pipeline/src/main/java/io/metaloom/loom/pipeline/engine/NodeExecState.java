package io.metaloom.loom.pipeline.engine;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import io.metaloom.loom.pipeline.graph.ExecutionMode;
import io.metaloom.loom.pipeline.model.NodeState;
import io.metaloom.loom.pipeline.model.NodeTaskResult;

/**
 * One node's execution state for one item — possibly several executions, one per element.
 *
 * <p>
 * This replaces the single {@code NodeTaskResult} an item used to hold per node. Everything that
 * used to be keyed by node id alone (in-flight task, attempt count, retry marker) is now keyed by
 * {@code (node, elementSeq)}, because a node downstream of a fan-out runs once per element and each
 * of those runs can be dispatched, retried and fail independently.
 * </p>
 *
 * <p>
 * <strong>{@link #isSettled()} is the gather.</strong> A node that consumes a sequence waits for its
 * dependencies to settle, and for a per-element dependency "settled" means <em>every</em> element
 * has finished. The barrier that recombines a fanned-out branch is therefore the dependency check
 * the engine already performed — no join node, no configuration, nothing for a pipeline author to
 * place.
 * </p>
 */
public class NodeExecState {

	/**
	 * How many times one execution may be handed back unexecuted before returns start
	 * costing an attempt. See {@link #refundAttempt(int)}.
	 */
	static final int MAX_REFUNDED_RETURNS = 3;

	private final ExecutionMode mode;

	/**
	 * How many elements this node runs for. Null until the fan-out driver settles and the count can
	 * be read off its result; always 1 for a {@link ExecutionMode#SINGLE} node.
	 */
	private Integer elementCount;

	private final Map<Integer, NodeTaskResult> elementResults = new LinkedHashMap<>();
	private final Map<Integer, UUID> inFlight = new LinkedHashMap<>();
	private final Map<Integer, Integer> attempts = new LinkedHashMap<>();
	/** How often each execution was handed back unexecuted, capping the attempt refund. */
	private final Map<Integer, Integer> returns = new LinkedHashMap<>();
	private final Set<Integer> awaitingRetry = new LinkedHashSet<>();
	/**
	 * Executions that ran, settled, and are being withheld from their dependents by a breakpoint.
	 *
	 * <p>
	 * A held execution is <strong>settled</strong> — it produced its result and that result is
	 * persisted and readable, which is the entire point of stopping here. What it is not is
	 * <em>available</em>: {@code PipelineRunEngine.dependenciesSettled} refuses to let anything
	 * downstream start while this set is non-empty.
	 * </p>
	 *
	 * <p>
	 * Insertion-ordered, because a step releases the oldest hold first: releasing an arbitrary one
	 * would make repeated stepping wander across items instead of walking a lineage forward.
	 * </p>
	 */
	private final Set<Integer> held = new LinkedHashSet<>();

	NodeExecState(ExecutionMode mode) {
		this.mode = mode == null ? ExecutionMode.SINGLE : mode;
		// A node that runs once per item knows its count immediately; only a fanned-out node has to
		// wait to be told.
		this.elementCount = this.mode == ExecutionMode.PER_ELEMENT ? null : 1;
	}

	public ExecutionMode getMode() {
		return mode;
	}

	public Integer getElementCount() {
		return elementCount;
	}

	/**
	 * Fix how many elements this node will run for, once the driving fan-out has settled.
	 *
	 * <p>
	 * Idempotent: a driver settles once, but recovery may replay the same information.
	 * </p>
	 */
	void setElementCount(int count) {
		if (this.elementCount == null) {
			this.elementCount = Math.max(0, count);
		}
	}

	/** @return results by element sequence index */
	public Map<Integer, NodeTaskResult> getElementResults() {
		return elementResults;
	}

	/**
	 * @return the result of a node that runs once per item, or null
	 */
	public NodeTaskResult single() {
		return elementResults.get(0);
	}

	/**
	 * Whether every element of this node has reached a terminal state.
	 *
	 * <p>
	 * A node whose element count is not yet known is never settled — its driver has not finished, so
	 * there is nothing to be settled about.
	 * </p>
	 */
	public boolean isSettled() {
		return elementCount != null && elementResults.size() >= elementCount;
	}

	boolean isSettled(int seq) {
		return elementResults.containsKey(seq);
	}

	/**
	 * The node's overall state, folded from its elements.
	 *
	 * <p>
	 * Any failed element makes the node failed; otherwise any completed element makes it completed;
	 * a node all of whose elements skipped is skipped. This is what the blocking-dependency and
	 * branch checks read, so a partially failed fan-out stops a blocking consumer exactly as a
	 * whole-node failure always did.
	 * </p>
	 */
	public NodeState rollup() {
		boolean anyCompleted = false;
		for (NodeTaskResult result : elementResults.values()) {
			if (result.getState() == NodeState.FAILED) {
				return NodeState.FAILED;
			}
			if (result.getState() == NodeState.COMPLETED) {
				anyCompleted = true;
			}
		}
		return anyCompleted ? NodeState.COMPLETED : NodeState.SKIPPED;
	}

	/**
	 * A representative result for callers that still think in whole nodes — branch evaluation, the
	 * asset sink, progress reporting.
	 *
	 * @return the first completed element's result, else any result, else null
	 */
	public NodeTaskResult representative() {
		NodeTaskResult fallback = null;
		for (NodeTaskResult result : elementResults.values()) {
			if (result.getState() == NodeState.COMPLETED) {
				return result;
			}
			if (fallback == null) {
				fallback = result;
			}
		}
		return fallback;
	}

	void record(int seq, NodeTaskResult result) {
		elementResults.put(seq, result);
		inFlight.remove(seq);
		awaitingRetry.remove(seq);
	}

	void markInFlight(int seq, UUID taskUuid) {
		inFlight.put(seq, taskUuid);
	}

	void clearInFlight(int seq) {
		inFlight.remove(seq);
	}

	boolean isInFlight(int seq) {
		return inFlight.containsKey(seq);
	}

	boolean hasInFlight() {
		return !inFlight.isEmpty();
	}

	/**
	 * @return the element sequence index the task belongs to, or null when unknown or already settled
	 */
	Integer seqForTask(UUID taskUuid) {
		for (Map.Entry<Integer, UUID> entry : inFlight.entrySet()) {
			if (entry.getValue().equals(taskUuid)) {
				return entry.getKey();
			}
		}
		return null;
	}

	int attemptsFor(int seq) {
		return attempts.getOrDefault(seq, 0);
	}

	int recordAttempt(int seq) {
		return attempts.merge(seq, 1, Integer::sum);
	}

	/**
	 * Give an attempt back, for a dispatch that was handed back unexecuted.
	 *
	 * <p>Bounded on purpose. Returning work costs nothing to the caller, so an
	 * unbounded refund lets a misbehaving worker bounce the same execution around the
	 * fleet forever - it would never accumulate attempts and so never dead-letter. Past
	 * the cap a return is accounted exactly like a lost task and the budget applies.</p>
	 *
	 * @return true when the attempt was refunded
	 */
	boolean refundAttempt(int seq) {
		if (returns.getOrDefault(seq, 0) >= MAX_REFUNDED_RETURNS) {
			return false;
		}
		returns.merge(seq, 1, Integer::sum);
		attempts.computeIfPresent(seq, (key, count) -> count <= 1 ? null : count - 1);
		return true;
	}

	/** @return how many times this execution has been handed back unexecuted */
	int returnsFor(int seq) {
		return returns.getOrDefault(seq, 0);
	}

	void markAwaitingRetry(int seq) {
		awaitingRetry.add(seq);
	}

	void clearAwaitingRetry(int seq) {
		awaitingRetry.remove(seq);
	}

	boolean isAwaitingRetry(int seq) {
		return awaitingRetry.contains(seq);
	}

	/**
	 * @return how many executions have settled, for progress reporting
	 */
	public int settledCount() {
		return elementResults.size();
	}

	/**
	 * Withhold a settled execution from its dependents.
	 *
	 * <p>
	 * Called only for an execution that actually ran to completion. A skip or a failure is not held:
	 * a skip has nothing to show, a failed node already stops its blocking dependents by itself, and
	 * holding either would halt the run at a node that cannot answer the question you stopped to ask.
	 * </p>
	 */
	void markHeld(int seq) {
		held.add(seq);
	}

	/**
	 * Let one withheld execution through.
	 *
	 * @return true when this call is what released it, so a caller can count real releases rather
	 *         than repeated clicks on an already-released node
	 */
	boolean release(int seq) {
		return held.remove(seq);
	}

	/** Let every withheld execution of this node through. @return how many were released */
	int releaseAll() {
		int count = held.size();
		held.clear();
		return count;
	}

	/** @return true when any execution of this node is withheld */
	public boolean isHeld() {
		return !held.isEmpty();
	}

	public boolean isHeld(int seq) {
		return held.contains(seq);
	}

	/** @return the withheld element sequences, oldest hold first */
	public Set<Integer> heldSeqs() {
		return new LinkedHashSet<>(held);
	}

	public int heldCount() {
		return held.size();
	}
}
