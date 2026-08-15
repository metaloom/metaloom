package io.metaloom.loom.pipeline.engine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.loom.api.uuid.LoomUUID;
import io.metaloom.loom.common.metrics.LoomMetrics;
import io.metaloom.loom.common.metrics.NoopLoomMetrics;
import io.metaloom.loom.pipeline.graph.ExecutionMode;
import io.metaloom.loom.pipeline.graph.InputBinding;
import io.metaloom.loom.pipeline.graph.PipelineGraph;
import io.metaloom.loom.pipeline.graph.PipelineGraphNode;
import io.metaloom.loom.pipeline.graph.PipelineSegment;
import io.metaloom.loom.pipeline.model.DataElement;
import io.metaloom.loom.pipeline.model.SegmentNode;
import io.metaloom.loom.pipeline.model.SegmentTask;
import io.metaloom.loom.pipeline.model.FilterBranch;
import io.metaloom.loom.pipeline.model.MediaRef;
import io.metaloom.loom.pipeline.model.NodeState;
import io.metaloom.loom.pipeline.model.NodeTask;
import io.metaloom.loom.pipeline.model.NodeTaskResult;
import io.metaloom.loom.pipeline.model.Origin;
import io.metaloom.loom.pipeline.model.PortPayload;

/**
 * Evaluates a {@link PipelineGraph} over a stream of media items, dispatching one
 * node at a time to a {@link NodeDispatcher}.
 *
 * <p>This is the Loom-side replacement for Cortex's {@code ReactivePipelineExecutor}.
 * The evaluation semantics are deliberately identical to the ones that engine
 * already implements and that existing tests pin down - only the location of the
 * decision changes.</p>
 *
 * <h2>Lifecycle</h2>
 *
 * <pre>
 * engine.start();
 * engine.onItemDiscovered(mediaRef);   // repeatedly, as the source enumerates
 * engine.onSourceComplete(total);
 * engine.onNodeTaskResult(result);     // repeatedly, as workers reply
 * </pre>
 *
 * <p>The run completes when the source has finished <em>and</em> every discovered
 * item has a terminal result for every node.</p>
 *
 * <h2>Semantics</h2>
 *
 * <ul>
 * <li>A node runs once all of its dependencies hold a terminal result.</li>
 * <li>A dependency in {@code FAILED} skips the dependent node <em>if that node is
 * blocking</em>. Note the direction: blocking is a property of the dependent node.</li>
 * <li>A dependency in {@code SKIPPED} does <strong>not</strong> cascade.</li>
 * <li>Filter routing consults only <em>direct</em> conditional dependencies, so
 * filter skipping is not transitive.</li>
 * <li>In a dry run every node is skipped and nothing is dispatched.</li>
 * <li>The source node's result is synthesised here rather than dispatched - its
 * output is derivable from the discovered item, so round-tripping it to a worker
 * would cost a network hop per item for nothing.</li>
 * </ul>
 *
 * <h2>Threading</h2>
 *
 * <p>All mutating entry points are synchronised on this instance. Callbacks fire on
 * the calling thread, so a listener must not block. Phase 1 keeps all state in
 * memory; a Loom restart loses in-flight runs.</p>
 */
public class PipelineRunEngine {

	private static final Logger log = LoggerFactory.getLogger(PipelineRunEngine.class);

	/**
	 * How many node tasks one run may have outstanding at once.
	 *
	 * <p>Without a ceiling a fast source and slow nodes produce unbounded outstanding
	 * work: a 100 000 item scan would dispatch every ready node immediately, and one
	 * large run would consume the entire worker fleet.</p>
	 */
	public static final int DEFAULT_MAX_IN_FLIGHT = 256;

	/** First retry waits this long; each further attempt doubles it. */
	public static final long DEFAULT_RETRY_BASE_DELAY_MS = 1_000;

	/** Ceiling on the backoff, so a high attempt count cannot park a node for hours. */
	public static final long MAX_RETRY_DELAY_MS = 60_000;

	/**
	 * The output port every source kind declares. The engine synthesises the source node's result
	 * rather than dispatching it, so it has to know the port name that downstream media inputs
	 * bind to.
	 */
	public static final String SOURCE_MEDIA_PORT = "media";

	private final PipelineGraph graph;
	private final NodeDispatcher dispatcher;
	private final UUID runUuid;
	private final RunStateStore store;
	private AssetSink assetSink = AssetSink.NOOP;
	private RetryScheduler retryScheduler = RetryScheduler.IMMEDIATE;
	private NodeKindCircuitBreaker circuitBreaker;
	/**
	 * Where the run reports what it is doing. Defaults to the no-op so every existing construction
	 * path - and every test - keeps working without a metrics backend.
	 */
	private LoomMetrics metrics = NoopLoomMetrics.INSTANCE;
	private int maxInFlight = DEFAULT_MAX_IN_FLIGHT;
	private int inFlightCount;
	private final List<Runnable> capacityWaiters = new ArrayList<>();
	private final java.util.Set<String> parkedKinds = new java.util.LinkedHashSet<>();
	/** Outstanding tasks per node kind, for the per-kind ceiling. */
	private final Map<String, Integer> inFlightByKind = new LinkedHashMap<>();
	private final Map<String, Integer> maxInFlightByKind = new LinkedHashMap<>();
	/** Suppresses re-scheduling while an un-park is already sweeping. */
	private boolean unparking;
	private long retryBaseDelayMs = DEFAULT_RETRY_BASE_DELAY_MS;

	private final Map<String, ItemState> items = new LinkedHashMap<>();
	private final List<Consumer<RunSummary>> completionListeners = new ArrayList<>();
	private final List<NodeSettleListener> settleListeners = new ArrayList<>();

	private boolean started;
	private boolean sourceComplete;
	private boolean runComplete;
	private boolean cancelled;
	private boolean paused;
	/**
	 * Whether workers should attach small renderings of what each node emitted.
	 *
	 * <p>
	 * A run-scoped operator choice, set alongside the circuit breaker and the retry scheduler rather
	 * than parsed from the definition - the same pipeline is run both with and without it, and
	 * nothing about the graph changes either way.
	 * </p>
	 */
	private boolean capturePreviews;
	/**
	 * Node ids whose completed executions are withheld from their dependents.
	 *
	 * <p>
	 * Run state, not definition state. A breakpoint is something an operator does to <em>this</em>
	 * run while watching it, and it must never find its way back into the stored pipeline — see the
	 * warning on {@link #setBreakpoints(java.util.Collection)}.
	 * </p>
	 */
	private final java.util.Set<String> breakpoints = new java.util.LinkedHashSet<>();
	/**
	 * Per-node option overrides that apply to this run only, keyed by node id.
	 *
	 * <p>
	 * ⚠️ <strong>Run state, never definition state</strong> — the same rule the breakpoint set above
	 * follows, and for a stronger reason. {@code PipelineGraphNode.options} and {@code PipelineGraph}
	 * are immutable and shared by every item of the run; editing a setting to see what it does must
	 * not become part of the pipeline everyone else runs. Persisting a tried-out setting is a separate,
	 * explicit act — the editor's "Save to pipeline", which writes a new pipeline version through the
	 * ordinary update endpoint.
	 * </p>
	 */
	private final Map<String, Map<String, Object>> optionOverrides = new LinkedHashMap<>();
	private final List<BreakpointListener> breakpointListeners = new ArrayList<>();
	private long startedAt;
	private long itemSequence;

	public PipelineRunEngine(PipelineGraph graph, NodeDispatcher dispatcher, UUID runUuid, RunStateStore store) {
		this.graph = graph;
		this.dispatcher = dispatcher;
		this.runUuid = runUuid;
		this.store = store == null ? RunStateStore.NOOP : store;
	}

	public PipelineRunEngine(PipelineGraph graph, NodeDispatcher dispatcher, UUID runUuid) {
		this(graph, dispatcher, runUuid, RunStateStore.NOOP);
	}

	public PipelineRunEngine(PipelineGraph graph, NodeDispatcher dispatcher) {
		this(graph, dispatcher, null, RunStateStore.NOOP);
	}

	/**
	 * Notified each time a node reaches a terminal state.
	 *
	 * <p>Deliberately minimal. At 100 000 items over a 10 node graph this fires a
	 * million times, so it carries identifiers rather than objects and listeners are
	 * expected to aggregate rather than forward.</p>
	 */
	@FunctionalInterface
	public interface NodeSettleListener {

		/**
		 * @param itemId    the item
		 * @param mediaPath the file, so a failure notification names something a person
		 *                  can act on rather than an opaque id
		 * @param nodeId    the node
		 * @param state     its terminal state
		 * @param message   failure detail or skip reason, may be null
		 */
		void onNodeSettled(String itemId, String mediaPath, String nodeId, NodeState state, String message);
	}

	/**
	 * Register a per-node progress callback.
	 *
	 * <p>⚠️ Fires on the engine thread while the monitor is held, so a listener must
	 * not block or call back into the engine.</p>
	 *
	 * @param listener the listener
	 */
	public synchronized void onNodeSettled(NodeSettleListener listener) {
		settleListeners.add(listener);
	}

	/**
	 * Notified when a breakpoint withholds an execution, and when one is let through.
	 *
	 * <p>
	 * Kept separate from {@link NodeSettleListener} because the two have opposite volumes and so
	 * want opposite handling. A settle happens a million times over a large run and is aggregated
	 * onto a timer; a hold happens because a person asked for it, is individually actionable, and
	 * goes out immediately — the same reasoning that makes {@code NODE_FAILED} an exception to the
	 * aggregation.
	 * </p>
	 */
	@FunctionalInterface
	public interface BreakpointListener {

		/**
		 * @param itemId     the item whose execution was held or released
		 * @param mediaPath  the file, so a hold names something a person can recognise rather than
		 *                   an opaque id — the same reasoning as {@link NodeSettleListener}
		 * @param nodeId     the breakpointed node
		 * @param elementSeq which execution, for a node that fanned out
		 * @param held       true when withheld, false when released
		 */
		void onBreakpoint(String itemId, String mediaPath, String nodeId, int elementSeq, boolean held);
	}

	/**
	 * Register a breakpoint callback.
	 *
	 * <p>⚠️ Same contract as {@link #onNodeSettled}: fires on the engine thread with the monitor
	 * held, so it must not block or re-enter the engine.</p>
	 */
	public synchronized void onBreakpoint(BreakpointListener listener) {
		breakpointListeners.add(listener);
	}

	/**
	 * Register a callback fired exactly once, when the run reaches a terminal state.
	 *
	 * @param listener receives the run counters
	 */
	public synchronized void onCompletion(Consumer<RunSummary> listener) {
		completionListeners.add(listener);
	}

	/**
	 * Begin the run.
	 *
	 * <p>A disabled pipeline completes immediately with no work, matching the existing
	 * executor's behaviour.</p>
	 */
	public synchronized void start() {
		if (started) {
			throw new IllegalStateException("Run already started");
		}
		started = true;
		startedAt = System.currentTimeMillis();

		if (!graph.isEnabled()) {
			log.info("Pipeline '{}' is disabled - completing run without work", graph.getName());
			sourceComplete = true;
			checkComplete();
		}
	}

	/**
	 * Stop the run: dispatch no further node tasks and settle the engine.
	 *
	 * <p>In-flight tasks cannot be recalled - the dispatcher has no reverse signal - so
	 * they are left to settle naturally; their late results are absorbed without
	 * dispatching anything downstream, because {@link #advance} is short-circuited once
	 * this flag is set and {@link #checkComplete} no-ops once {@code runComplete} is.</p>
	 *
	 * <p>Idempotent, and a no-op on a run that has already reached a terminal state.
	 * Completion listeners are deliberately <em>not</em> fired: the terminal transition
	 * of the {@code pipeline_run} row and the registry cleanup are owned by the caller,
	 * exactly as they are on the dispatch-failure teardown path. Firing them here would
	 * drive the normal completion listener, which derives a SUCCESS/PARTIAL/FAILED status
	 * rather than a cancellation.</p>
	 */
	public synchronized void cancel() {
		if (runComplete) {
			return;
		}
		cancelled = true;
		// Cancelling a paused run is allowed and clears the suspension, so the release
		// below is not blocked by the pause gate and the state stays coherent.
		paused = false;
		// No further items will be accepted or expected, and any source held for capacity
		// must be released or it waits forever on a run that has stopped consuming.
		sourceComplete = true;
		runComplete = true;
		releaseCapacityWaiters();
		store.flush();
		log.info("Run {} cancelled: {}", runUuid, buildSummary());
	}

	/**
	 * Suspend the run: dispatch no further work, but keep everything already known.
	 *
	 * <p>Unlike {@link #cancel()} this is reversible and non-terminal. Three things stop:
	 * new node and segment dispatch (via the {@link #advance} gate), the release of
	 * capacity waiters, and the immediate-run fast path in
	 * {@link #whenCapacityAvailable(Runnable)}.</p>
	 *
	 * <p>That last pair is what makes a pause real rather than cosmetic. Gating dispatch
	 * alone would stop the graph while leaving the <em>source</em> free to keep scanning
	 * and piling up item state. Holding the capacity waiters withholds the acknowledgement
	 * the source is waiting for, which stops the scan itself. Note this only bites once the
	 * batch already in flight drains, so a pause takes effect within one source batch
	 * rather than instantly.</p>
	 *
	 * <p>Work already handed to a worker is not recalled - there is no reverse signal - so
	 * it settles normally through {@link #record}; it simply spawns nothing downstream
	 * until the run is unpaused.</p>
	 *
	 * <p>Idempotent, and a no-op on a run that has already reached a terminal state.</p>
	 *
	 * @return true if the run was actually paused, false if it was already complete
	 */
	public synchronized boolean pause() {
		if (runComplete) {
			return false;
		}
		paused = true;
		log.info("Run {} paused: {}", runUuid, buildSummary());
		return true;
	}

	/**
	 * Resume a paused run and dispatch whatever became ready while it was suspended.
	 *
	 * <p>Named {@code unpause} rather than {@code resume} to keep it distinct from
	 * {@link #resume(boolean)}, which is the unrelated post-restart recovery entry point.</p>
	 *
	 * <p>Idempotent, and a no-op on a run that is not paused.</p>
	 */
	public synchronized void unpause() {
		if (!paused) {
			return;
		}
		paused = false;
		if (runComplete) {
			return;
		}
		// Dispatch anything that became ready while suspended and release any source that
		// was held for capacity, then re-check completion: a run whose remaining work all
		// settled during the pause is genuinely finished and must be allowed to close out.
		pumpDeferred();
		checkComplete();
	}

	/**
	 * Ask workers to attach debugging previews of what each node emits.
	 *
	 * <p>
	 * Off by default. A production run must not pay to encode and store a thumbnail per image per
	 * item, so this is opt-in per run and reaches the worker on each {@code NodeTask}.
	 * </p>
	 *
	 * <p>
	 * Segment dispatch is deliberately unaffected: a segment's intermediate results never leave the
	 * worker at all, so there is nothing there to preview.
	 * </p>
	 */
	public void setCapturePreviews(boolean capturePreviews) {
		this.capturePreviews = capturePreviews;
	}

	/** @return true while the run is suspended by {@link #pause()} */
	public synchronized boolean isPaused() {
		return paused;
	}

	/**
	 * Arm breakpoints on a set of nodes, replacing whatever was armed before.
	 *
	 * <p>
	 * A breakpoint holds a node's completed executions <em>after</em> they have run: the result is
	 * produced, persisted and readable, and only the dependents are stopped. That is the whole
	 * mechanism, and it is why this is nine lines rather than a scheduler — the engine already has a
	 * single place where it asks whether a node's dependencies are available.
	 * </p>
	 *
	 * <p>
	 * Disarming a node releases whatever it was holding, so clearing a breakpoint always lets the run
	 * continue rather than stranding it.
	 * </p>
	 *
	 * <p>
	 * ⚠️ <strong>Run state, never definition state.</strong> Nothing here may be written back into the
	 * pipeline definition; a breakpoint set while debugging must not become part of the pipeline
	 * everyone else runs.
	 * </p>
	 *
	 * @param nodeIds the nodes to hold at; null or empty disarms everything
	 */
	public synchronized void setBreakpoints(java.util.Collection<String> nodeIds) {
		java.util.Set<String> next = nodeIds == null ? java.util.Set.of() : new java.util.LinkedHashSet<>(nodeIds);
		java.util.Set<String> disarmed = new java.util.LinkedHashSet<>(breakpoints);
		disarmed.removeAll(next);
		breakpoints.clear();
		breakpoints.addAll(next);
		// Releasing before advancing, so one sweep sees the whole new picture.
		for (String nodeId : disarmed) {
			releaseNodeInternal(nodeId);
		}
		if (!disarmed.isEmpty()) {
			pumpDeferred();
			checkComplete();
		}
	}

	/** @return the armed node ids, in the order they were given */
	public synchronized java.util.Set<String> getBreakpoints() {
		return new java.util.LinkedHashSet<>(breakpoints);
	}

	/**
	 * The graph this run is executing.
	 *
	 * <p>
	 * Exposed so a caller can check a breakpoint against the nodes that actually exist. A run's graph
	 * is the version it started with, which is not necessarily the pipeline's current definition —
	 * validating against anything else would accept ids this run will never dispatch.
	 * </p>
	 */
	public PipelineGraph getGraph() {
		return graph;
	}

	/**
	 * Let every execution this node is holding through.
	 *
	 * <p>
	 * The breakpoint stays armed: a later item reaching the same node is held again, which is what
	 * makes it a breakpoint rather than a one-shot. Use {@link #setBreakpoints} to disarm.
	 * </p>
	 *
	 * @param nodeId the node to release
	 * @return how many executions were released
	 */
	public synchronized int releaseNode(String nodeId) {
		int released = releaseNodeInternal(nodeId);
		if (released > 0) {
			pumpDeferred();
			checkComplete();
		}
		return released;
	}

	private int releaseNodeInternal(String nodeId) {
		int released = 0;
		for (ItemState state : items.values()) {
			NodeExecState exec = state.exec(nodeId);
			if (exec == null || !exec.isHeld()) {
				continue;
			}
			for (Integer seq : exec.heldSeqs()) {
				notifyBreakpoint(state, nodeId, seq, false);
			}
			released += exec.releaseAll();
		}
		return released;
	}

	/**
	 * Let exactly one withheld execution through — the step.
	 *
	 * <p>
	 * The oldest hold goes first, in item discovery order, so repeated stepping walks one lineage
	 * forward instead of scattering across the run. Whatever that execution unblocks is dispatched
	 * immediately; if the next node is breakpointed too, the run stops again there, which is what
	 * makes stepping feel like stepping.
	 * </p>
	 *
	 * @return true when something was released, false when nothing was being held
	 */
	public synchronized boolean stepOne() {
		for (ItemState state : items.values()) {
			for (Map.Entry<String, NodeExecState> entry : state.getExecs().entrySet()) {
				NodeExecState exec = entry.getValue();
				if (!exec.isHeld()) {
					continue;
				}
				int seq = exec.heldSeqs().iterator().next();
				exec.release(seq);
				notifyBreakpoint(state, entry.getKey(), seq, false);
				pumpDeferred();
				checkComplete();
				return true;
			}
		}
		return false;
	}

	/**
	 * Everything currently withheld, for the debug view.
	 *
	 * @return one entry per held execution, in item discovery order
	 */
	public synchronized List<HeldExecution> heldExecutions() {
		List<HeldExecution> held = new ArrayList<>();
		for (ItemState state : items.values()) {
			for (Map.Entry<String, NodeExecState> entry : state.getExecs().entrySet()) {
				for (Integer seq : entry.getValue().heldSeqs()) {
					held.add(new HeldExecution(state.getItemId(), entry.getKey(), seq));
				}
			}
		}
		return held;
	}

	/** @return how many executions are withheld across the whole run */
	public synchronized int heldCount() {
		return countHeld();
	}

	private int countHeld() {
		int held = 0;
		for (ItemState state : items.values()) {
			held += state.heldCount();
		}
		return held;
	}

	/**
	 * Whether a breakpoint should withhold this execution.
	 *
	 * <p>
	 * Only a completed execution is held. A skip produced nothing to look at, and a failure already
	 * stops its blocking dependents on its own — holding either would stop the run at a node that
	 * cannot answer the question you stopped to ask, and would suppress the skip cascade that
	 * explains why the rest of the graph did not run.
	 * </p>
	 */
	private void applyBreakpoint(ItemState state, NodeTaskResult result) {
		if (breakpoints.isEmpty() || result.getState() != NodeState.COMPLETED) {
			return;
		}
		if (!breakpoints.contains(result.getNodeId())) {
			return;
		}
		NodeExecState exec = state.exec(result.getNodeId());
		if (exec == null) {
			return;
		}
		exec.markHeld(result.getElementSeq());
		notifyBreakpoint(state, result.getNodeId(), result.getElementSeq(), true);
	}

	private void notifyBreakpoint(ItemState state, String nodeId, int elementSeq, boolean held) {
		String mediaPath = state.getMedia() == null ? null : state.getMedia().getPath();
		for (BreakpointListener listener : breakpointListeners) {
			try {
				listener.onBreakpoint(state.getItemId(), mediaPath, nodeId, elementSeq, held);
			} catch (Exception e) {
				// A misbehaving observer must not derail the run it is observing.
				log.error("Breakpoint listener threw", e);
			}
		}
	}

	/** One execution a breakpoint is currently withholding. */
	public record HeldExecution(String itemId, String nodeId, int elementSeq) {
	}

	/**
	 * Run a held execution again, optionally with different settings.
	 *
	 * <p>
	 * This is what makes stopping at a breakpoint worth doing: you look at what a node produced,
	 * change the setting you suspect, and see the same input answered differently — without
	 * re-running the pipeline from the top, and without the earlier attempt being thrown away.
	 * </p>
	 *
	 * <p>
	 * <strong>Only a held execution may be re-executed</strong>, and that restriction is what makes
	 * the operation safe rather than merely convenient. A hold means the result was produced but never
	 * made available to anything downstream, so discarding it cannot invalidate work that already
	 * consumed it. Re-executing an execution that had been released would require invalidating its
	 * dependents transitively — a different and much larger feature.
	 * </p>
	 *
	 * <p>
	 * Inputs are not stored and do not need to be: {@link #buildInputs} rebuilds them from the
	 * upstream results the item still holds, so the node genuinely runs again over the same data.
	 * </p>
	 *
	 * @param itemId     the item whose execution to re-run
	 * @param nodeId     the node
	 * @param elementSeq which element of a fanned-out sequence; 0 for a node that runs once per item
	 * @param options    settings to apply for the rest of this run, merged over the pipeline's own;
	 *                   null re-runs with whatever is already in effect, and an empty map drops any
	 *                   override and goes back to the definition
	 * @return the generation this re-execution will be recorded under, counting from 1
	 * @throws IllegalArgumentException when the item or node is unknown to this run
	 * @throws IllegalStateException    when that execution is not currently held
	 */
	public synchronized int reExecute(String itemId, String nodeId, int elementSeq, Map<String, Object> options) {
		requireStarted();
		ItemState state = items.get(itemId);
		if (state == null) {
			throw new IllegalArgumentException("No item '" + itemId + "' in run " + runUuid);
		}
		if (graph.getNode(nodeId) == null) {
			throw new IllegalArgumentException("No node '" + nodeId + "' in this pipeline");
		}
		NodeExecState exec = state.exec(nodeId);
		if (exec == null || !exec.isHeld(elementSeq)) {
			throw new IllegalStateException("Execution " + nodeId + "#" + elementSeq + " of item " + itemId
				+ " is not held at a breakpoint, so it cannot be re-executed.");
		}

		if (options != null) {
			if (options.isEmpty()) {
				optionOverrides.remove(nodeId);
			} else {
				optionOverrides.put(nodeId, new LinkedHashMap<>(options));
			}
		}

		// Tell anyone watching that this execution is no longer holding *before* it is discarded.
		// The UI is showing a held node; leaving that frame unsent would strand the node as held
		// while the engine has already moved on, and the re-hold below would look like a no-op.
		notifyBreakpoint(state, nodeId, elementSeq, false);
		int generation = state.clearResult(nodeId, elementSeq);

		// The execution is now unsettled, so the ordinary sweep dispatches it exactly as it did the
		// first time - which is also why the breakpoint holds it again for free when it comes back.
		advance(state);
		pumpDeferred();
		log.info("Re-executing {}#{} of item {} in run {} as generation {}", nodeId, elementSeq, itemId, runUuid,
			generation);
		return generation;
	}

	/**
	 * @return the run-scoped option overrides in effect, keyed by node id, for the debug view
	 */
	public synchronized Map<String, Map<String, Object>> getOptionOverrides() {
		Map<String, Map<String, Object>> copy = new LinkedHashMap<>();
		optionOverrides.forEach((nodeId, options) -> copy.put(nodeId, Map.copyOf(options)));
		return copy;
	}

	/**
	 * The settings a node actually runs with, which is the pipeline's own with any run-scoped
	 * override laid over the top.
	 *
	 * <p>
	 * Merged rather than replaced, so a caller changing one parameter changes one parameter. Sending
	 * the whole set replaces the whole set, which is what the editor's form does — both readings of
	 * "change this setting" therefore behave the way the caller expects.
	 * </p>
	 */
	private Map<String, Object> effectiveOptions(PipelineGraphNode node) {
		Map<String, Object> override = optionOverrides.get(node.getId());
		if (override == null || override.isEmpty()) {
			return node.getOptions();
		}
		Map<String, Object> merged = new LinkedHashMap<>(node.getOptions() == null ? Map.of() : node.getOptions());
		merged.putAll(override);
		return merged;
	}

	/**
	 * Called for each media item the source enumerates.
	 *
	 * <p>The source node's own result is recorded here, which is what makes the rest
	 * of the graph become ready.</p>
	 *
	 * @param media the discovered item
	 * @return the item id assigned by the engine
	 */
	public synchronized String onItemDiscovered(MediaRef media) {
		requireStarted();
		if (cancelled) {
			// A source still enumerating when the run was cancelled: drop the item rather
			// than tripping the sourceComplete guard below with an IllegalStateException.
			return null;
		}
		if (sourceComplete) {
			throw new IllegalStateException("Source already reported complete for run " + runUuid);
		}
		if (!graph.isEnabled()) {
			return null;
		}

		long itemSeq = ++itemSequence;
		// Identity comes from the store, not from a counter, so a run recovered after a
		// restart can still match an arriving result to its item.
		String itemId = store.itemDiscovered(runUuid, itemSeq, media).toString();
		ItemState state = new ItemState(itemId, media);
		items.put(itemId, state);

		PipelineGraphNode source = graph.getSourceNode();
		// The source node's result is synthesised rather than dispatched - the item exists
		// precisely because the source already found it. Its media port is what every downstream
		// media input binds to, and it carries the item's own id as the origin: this is where every
		// element's lineage begins.
		Origin origin = Origin.single(itemId);
		Map<String, PortPayload> outputs = new LinkedHashMap<>();
		outputs.put(SOURCE_MEDIA_PORT, PortPayload.one(media.contentType(), origin, media.getPath()));

		NodeTaskResult sourceResult = graph.isDryRun()
			? NodeTaskResult.skipped(source.getId(), "dry-run")
			: NodeTaskResult.completed(null, source.getId(), 0, outputs);
		record(state, sourceResult);

		advance(state);
		checkComplete();
		return itemId;
	}

	/**
	 * Rebuild an item from persisted state, without dispatching anything.
	 *
	 * <p>Used by recovery to put the engine back where it was before a restart. The
	 * settled results are adopted as-is: a node that already ran must <em>not</em> run
	 * again, which is the entire point of having persisted them.</p>
	 *
	 * <p>Nothing is dispatched here. Call {@link #resume(boolean)} once every item has been
	 * restored, so the engine sees a complete picture before it starts making
	 * decisions - otherwise it could complete the run on the first restored item,
	 * before the rest have been read back.</p>
	 *
	 * @param itemId  the item's persisted id
	 * @param media   the item
	 * @param settled results already recorded, by node id
	 * @param attempts how many times each node had been dispatched, by node id
	 */
	public synchronized void restoreItem(String itemId, MediaRef media, Map<String, NodeTaskResult> settled,
		Map<String, Integer> attempts) {
		requireStarted();
		ItemState state = new ItemState(itemId, media);
		// Seed every node's execution state from the graph before adopting any result. A restored
		// result creates the state on first touch otherwise, and it would be created as a node that
		// runs once - so a half-finished fan-out would come back as a single-execution node that
		// looks settled after its first element, and the rest of the sequence would never run.
		for (String nodeId : graph.getTopologicalOrder()) {
			PipelineGraphNode node = graph.getNode(nodeId);
			if (node != null) {
				state.exec(nodeId, node.getExecutionMode());
			}
		}
		if (settled != null) {
			for (NodeTaskResult result : settled.values()) {
				state.record(result);
			}
		}
		if (attempts != null) {
			// Carrying the attempt count over is what stops a restart from silently
			// resetting the retry budget and letting a poison item run forever.
			attempts.forEach((nodeId, count) -> {
				for (int i = 0; i < count; i++) {
					state.recordAttempt(nodeId);
				}
			});
		}
		items.put(itemId, state);
	}

	/**
	 * One persisted execution, as recovery reads it back.
	 *
	 * @param nodeId     the node
	 * @param elementSeq which element of a fanned-out sequence; 0 for a node that runs once per item
	 * @param result     the terminal result, or null when the execution never finished and should be
	 *                   dispatched again
	 * @param attempts   how many times it had already been handed to a worker
	 */
	public record RestoredTask(String nodeId, int elementSeq, NodeTaskResult result, int attempts) {
	}

	/**
	 * Rebuild an item from its persisted executions.
	 *
	 * <p>
	 * Takes a <strong>list</strong>, deliberately. A node downstream of a fan-out has one row per
	 * element, so a map keyed by node id would keep only whichever element happened to be read last —
	 * and the item would come back looking narrower than it is, stranding the executions it dropped.
	 * The attempt count is per element for the same reason: a poison element must not have its retry
	 * budget reset by a sibling that succeeded.
	 * </p>
	 */
	public synchronized void restoreItem(String itemId, MediaRef media, List<RestoredTask> tasks) {
		requireStarted();
		ItemState state = new ItemState(itemId, media);
		for (String nodeId : graph.getTopologicalOrder()) {
			PipelineGraphNode node = graph.getNode(nodeId);
			if (node != null) {
				state.exec(nodeId, node.getExecutionMode());
			}
		}
		if (tasks != null) {
			for (RestoredTask task : tasks) {
				if (task.result() != null) {
					state.record(task.nodeId(), task.elementSeq(), task.result());
				}
				for (int i = 0; i < task.attempts(); i++) {
					state.recordAttempt(task.nodeId(), task.elementSeq());
				}
			}
		}
		items.put(itemId, state);
	}

	/**
	 * Resume a restored run: dispatch whatever is now ready.
	 *
	 * <p>A node that was in flight when the process died is simply unsettled here, so
	 * it becomes ready again and is dispatched. Its lease row is stale, which the
	 * reaper tidies up.</p>
	 *
	 * @param sourceWasComplete whether the source had finished enumerating before the
	 *                          restart; a run whose source was still scanning cannot
	 *                          be completed faithfully, because the files it had not
	 *                          reached yet were never recorded
	 */
	public synchronized void resume(boolean sourceWasComplete) {
		requireStarted();
		sourceComplete = sourceWasComplete;
		log.info("Resuming run {} with {} restored item(s), source {}", runUuid, items.size(),
			sourceWasComplete ? "complete" : "INCOMPLETE - remaining media were never recorded");
		for (ItemState state : items.values()) {
			advance(state);
		}
		checkComplete();
	}

	/**
	 * Called once the source has finished enumerating.
	 *
	 * @param totalCount number of items the source produced, for reconciliation
	 */
	public synchronized void onSourceComplete(long totalCount) {
		requireStarted();
		sourceComplete = true;
		store.sourceCompleted(runUuid, totalCount);
		if (totalCount != items.size()) {
			log.warn("Run {} source reported {} items but {} were received", runUuid, totalCount, items.size());
		}
		checkComplete();
	}

	/**
	 * Called when a worker reports the outcome of a dispatched task.
	 *
	 * <p>Unknown or duplicate task ids are logged and ignored rather than throwing:
	 * once retries exist, duplicate delivery is expected.</p>
	 *
	 * @param itemId the item the task belonged to
	 * @param result the outcome
	 */
	public synchronized void onNodeTaskResult(String itemId, NodeTaskResult result) {
		requireStarted();
		ItemState state = items.get(itemId);
		if (state == null) {
			log.warn("Result for unknown item '{}' in run {} - ignoring", itemId, runUuid);
			return;
		}
		if (state.isSettled(result.getNodeId())) {
			log.warn("Duplicate result for node '{}' on item '{}' - ignoring", result.getNodeId(), itemId);
			return;
		}

		// A failure is not automatically final. If the node asked to be retried and has
		// attempts left, hand it back rather than settling it - otherwise `retryFailed`
		// would remain the decoration it has always been.
		if (result.getState() == NodeState.FAILED && shouldRetry(state, result.getNodeId(), result.getElementSeq())) {
			scheduleRetry(state, result.getNodeId(), result.getElementSeq(), describe(result));
			// The failed attempt released its slot; someone else may be waiting for it.
			pumpDeferred();
			return;
		}

		record(state, result);
		advance(state);
		// Capacity may have just freed up for work deferred on other items.
		pumpDeferred();
		checkComplete();
	}

	/**
	 * Called when a dispatched task will never report back - typically because its
	 * lease expired and the worker holding it is presumed dead.
	 *
	 * <p>Re-dispatches when attempts remain, and dead-letters otherwise. A lost task
	 * that is neither retried nor settled stalls its item forever, so this method
	 * must always do one of the two.</p>
	 *
	 * @param itemId the item
	 * @param nodeId the node whose task was lost
	 * @param reason why it was reclaimed, for the dead-letter record
	 */
	public synchronized void onNodeTaskLost(String itemId, String nodeId, String reason) {
		onNodeTaskLost(itemId, nodeId, 0, reason);
	}

	/**
	 * Reclaim a lost task for one element.
	 *
	 * <p>
	 * The element matters. A node downstream of a fan-out has several executions in flight at once,
	 * and reclaiming "the node" would release a slot never acquired, retry an element that may
	 * already have settled, and leave the element that was actually lost in flight forever — an item
	 * that never completes rather than one that fails. The reaper reads {@code element_seq} off the
	 * task row, so it can say which one it means.
	 * </p>
	 *
	 * @param elementSeq which element of a fanned-out sequence was lost; 0 for a node that runs once
	 *                   per item
	 */
	public synchronized void onNodeTaskLost(String itemId, String nodeId, int elementSeq, String reason) {
		requireStarted();
		ItemState state = items.get(itemId);
		if (state == null) {
			log.warn("Lost task for unknown item '{}' in run {} - ignoring", itemId, runUuid);
			return;
		}
		if (state.isSettled(nodeId, elementSeq)) {
			// The result won the race against the reaper. Nothing to reclaim.
			log.debug("Node '{}' element {} on item '{}' already settled - ignoring reclaim",
				nodeId, elementSeq, itemId);
			return;
		}
		if (!state.isInFlight(nodeId, elementSeq)) {
			log.debug("Node '{}' element {} on item '{}' is not in flight - ignoring reclaim",
				nodeId, elementSeq, itemId);
			return;
		}

		releaseInFlight(state, nodeId, elementSeq);
		if (shouldRetry(state, nodeId, elementSeq)) {
			scheduleRetry(state, nodeId, elementSeq, reason);
			return;
		}

		metrics.recordNodeTaskDeadlettered(kindOf(nodeId));
		record(state, NodeTaskResult.failed(null, nodeId, elementSeq, 0,
			"Dead-lettered after " + state.attemptsFor(nodeId, elementSeq) + " attempt(s): " + reason, null));
		advance(state);
		// The dead-lettered task released its slot; hand it to whoever is waiting.
		pumpDeferred();
		checkComplete();
	}

	/**
	 * Called when a worker hands a dispatched task back without running it - it is
	 * shutting down and will not finish the work.
	 *
	 * <p>Distinct from {@link #onNodeTaskLost} in two ways that both matter. It happens
	 * <em>immediately</em> rather than after a lease interval, which is the entire point
	 * of a worker announcing its shutdown instead of just vanishing. And it does not
	 * consume the execution's attempt budget: nothing was attempted, so charging for it
	 * would let a rolling restart dead-letter items whose nodes are not retryable - the
	 * default - purely because a deployment happened while they were queued.</p>
	 *
	 * <p>The refund is capped ({@link NodeExecState#MAX_REFUNDED_RETURNS}); past that a
	 * return is accounted as a loss, so an execution that no worker will keep still
	 * settles rather than circulating forever.</p>
	 *
	 * @param itemId     the item
	 * @param nodeId     the node whose task was handed back
	 * @param elementSeq which element of a fanned-out sequence; 0 for a node that runs once
	 * @param reason     why the worker gave it back
	 */
	public synchronized void onNodeTaskReturned(String itemId, String nodeId, int elementSeq, String reason) {
		requireStarted();
		ItemState state = items.get(itemId);
		if (state == null) {
			log.warn("Returned task for unknown item '{}' in run {} - ignoring", itemId, runUuid);
			return;
		}
		if (state.isSettled(nodeId, elementSeq)) {
			// The worker finished it after all and the result won the race. Nothing to place.
			log.debug("Node '{}' element {} on item '{}' already settled - ignoring return",
				nodeId, elementSeq, itemId);
			return;
		}
		if (!state.isInFlight(nodeId, elementSeq)) {
			log.debug("Node '{}' element {} on item '{}' is not in flight - ignoring return",
				nodeId, elementSeq, itemId);
			return;
		}

		// Refunded before the release, so the fall-through below still finds the
		// execution in flight and can run the ordinary loss path unchanged.
		if (!state.refundAttempt(nodeId, elementSeq)) {
			// Returned too many times to keep treating as free. Account it as a loss,
			// which retries or dead-letters against the attempt budget.
			log.warn("Node '{}' element {} on item '{}' has been returned repeatedly; charging the attempt",
				nodeId, elementSeq, itemId);
			onNodeTaskLost(itemId, nodeId, elementSeq, reason);
			return;
		}
		releaseInFlight(state, nodeId, elementSeq);

		log.info("Node '{}' element {} on item '{}' handed back for re-placement: {}",
			nodeId, elementSeq, itemId, reason);
		// Straight back into dispatch. The returning worker has already announced
		// TERMINATING, so placement will not offer it the same task again.
		advance(state);
		pumpDeferred();
		checkComplete();
	}

	/**
	 * @return true when this execution may be attempted again
	 */
	private boolean shouldRetry(ItemState state, String nodeId, int seq) {
		PipelineGraphNode node = graph.getNode(nodeId);
		if (node == null) {
			return false;
		}
		// Per element, not per node: a node downstream of a fan-out runs several times and each
		// run has its own budget. Reading the budget of element 0 would let a failure of element
		// 3 be "retried" against an attempt count that belongs to a different execution - and
		// when element 0 never ran, the retry would be scheduled for a slot that is already
		// settled and the failed element would never be settled at all.
		return state.attemptsFor(nodeId, seq) < node.getMaxAttempts();
	}

	/**
	 * Hand a node back for another attempt after a backoff.
	 *
	 * <p>The retry runs outside this method's synchronised block when the scheduler
	 * defers it, and re-enters through {@link #retryNow}, which re-checks state - by
	 * the time a delayed retry fires the run may have completed or the node may have
	 * settled some other way.</p>
	 */
	private void scheduleRetry(ItemState state, String nodeId, int seq, String reason) {
		releaseInFlight(state, nodeId, seq);
		state.markAwaitingRetry(nodeId, seq);
		metrics.recordNodeTaskRetried(kindOf(nodeId));
		int attempt = state.attemptsFor(nodeId, seq);
		long delay = backoffFor(attempt);
		log.info("Retrying node '{}' element {} on item '{}' (attempt {} of {}) in {}ms after: {}",
			nodeId, seq, state.getItemId(), attempt + 1, graph.getNode(nodeId).getMaxAttempts(), delay, reason);

		String itemId = state.getItemId();
		retryScheduler.schedule(delay, () -> retryNow(itemId, nodeId, seq));
	}

	/**
	 * Re-enter the engine to dispatch a retry.
	 *
	 * @param itemId the item
	 * @param nodeId the node to attempt again
	 * @param seq    which element of it
	 */
	private synchronized void retryNow(String itemId, String nodeId, int seq) {
		ItemState state = items.get(itemId);
		if (state == null) {
			return;
		}
		state.clearAwaitingRetry(nodeId, seq);
		if (state.isSettled(nodeId, seq) || state.isInFlight(nodeId, seq)) {
			return;
		}
		advance(state);
		checkComplete();
	}

	/**
	 * Exponential backoff, capped.
	 *
	 * @param attempt attempts already made
	 * @return how long to wait before the next one
	 */
	long backoffFor(int attempt) {
		if (attempt <= 0) {
			return 0;
		}
		long delay = retryBaseDelayMs << Math.min(attempt - 1, 20);
		return Math.min(delay, MAX_RETRY_DELAY_MS);
	}

	private static String describe(NodeTaskResult result) {
		return result.getMessage() == null ? "node failed" : result.getMessage();
	}

	/**
	 * @param nodeId a node of this run's graph
	 * @return its kind, which is what every metric label uses - a node <em>id</em> is chosen by
	 *         whoever drew the pipeline and would put unbounded cardinality on the registry
	 */
	private String kindOf(String nodeId) {
		PipelineGraphNode node = graph.getNode(nodeId);
		return node == null ? "unknown" : node.getKind();
	}

	/**
	 * Replace the retry scheduler, e.g. with one backed by a Vert.x timer.
	 *
	 * @param scheduler the scheduler; null restores the immediate default
	 */
	public synchronized void setRetryScheduler(RetryScheduler scheduler) {
		this.retryScheduler = scheduler == null ? RetryScheduler.IMMEDIATE : scheduler;
	}

	/**
	 * @param baseDelayMs delay before the first retry; each further attempt doubles it
	 */
	public synchronized void setRetryBaseDelayMs(long baseDelayMs) {
		this.retryBaseDelayMs = Math.max(0, baseDelayMs);
	}

	/** @return true when the run has reached a terminal state */
	public synchronized boolean isComplete() {
		return runComplete;
	}

	/** @return current counters; meaningful once {@link #isComplete()} is true */
	public synchronized RunSummary summary() {
		return buildSummary();
	}

	/**
	 * @param itemId the item
	 * @return its state, or null when unknown
	 */
	public synchronized ItemState getItem(String itemId) {
		return items.get(itemId);
	}

	/** @return all item states, in discovery order */
	public synchronized Map<String, ItemState> getItems() {
		return new LinkedHashMap<>(items);
	}

	/**
	 * Dispatch every node of this item that has become ready, recording immediate
	 * results for nodes that are skipped rather than executed.
	 */
	private void advance(ItemState state) {
		if (cancelled || paused) {
			// The run was cancelled or suspended; dispatch nothing further. Late in-flight
			// results still settle through record(), but must not spawn new downstream work.
			//
			// This is the single choke point for all dispatch: dispatch() and
			// dispatchSegment() are only reachable from here, so retries (retryNow ->
			// advance) and circuit un-parking (unparkKind -> pumpDeferred -> advance) are
			// covered by this one gate too.
			return;
		}
		boolean progressed = true;
		// A skip settles a node without a round trip, which can immediately make its
		// children ready - so keep going until nothing new settles.
		while (progressed) {
			progressed = false;
			for (String nodeId : graph.getTopologicalOrder()) {
				PipelineGraphNode node = graph.getNode(nodeId);
				if (node == null) {
					continue;
				}
				NodeExecState exec = state.exec(nodeId, node.getExecutionMode());

				// The gather barrier. For a dependency that ran per element this asks "have ALL of
				// its elements settled?", so a node consuming a fanned-out branch automatically
				// waits for the whole branch before it is considered ready. That is the entire
				// recombination mechanism - there is no join node and nothing to configure.
				if (!dependenciesSettled(state, node)) {
					continue;
				}

				if (node.getExecutionMode() == ExecutionMode.PER_ELEMENT && exec.getElementCount() == null) {
					// The driving fan-out has settled (dependenciesSettled passed) but we have not
					// yet read how many elements it produced.
					if (graph.getNode(node.getFanOutDriver()) == null) {
						continue;
					}
					exec.setElementCount(fanOutSize(state, node));
				}

				Integer count = exec.getElementCount();
				if (count == null) {
					continue;
				}
				if (count == 0) {
					// The sequence was empty, so this node has nothing to run for. It is already
					// settled by the count alone - but leaving it at that means no terminal result,
					// no persisted task row and nothing in the run detail to say why it did not
					// run, which is indistinguishable from a node the engine forgot. Record one
					// bookkeeping skip, while leaving the count at zero so a second-level
					// per-element node reads the width as empty too and skips in turn.
					if (!exec.isSettled(0)) {
						record(state, NodeTaskResult.skipped(nodeId, 0, "Upstream sequence was empty"));
						progressed = true;
					}
					continue;
				}

				for (int seq = 0; seq < count; seq++) {
					if (state.isSettled(nodeId, seq) || state.isInFlight(nodeId, seq)
						|| state.isAwaitingRetry(nodeId, seq)) {
						continue;
					}

					NodeTaskResult skip = evaluateSkip(state, node, seq);
					if (skip != null) {
						record(state, skip);
						progressed = true;
						continue;
					}

					if (atCapacity()) {
						// Leave the execution unsettled and undispatched. It stays ready, and
						// pumpDeferred() picks it up as soon as something finishes.
						continue;
					}

					// Adopting an earlier run's result is checked before every gate below:
					// there is no point queueing for capacity, or waiting out a circuit
					// breaker, to recompute something already known.
					if (seq == 0 && count == 1 && adoptPreviousResult(state, node)) {
						progressed = true;
						continue;
					}

					if (atKindCapacity(node.getKind())) {
						// This kind is at its ceiling. Leave it ready; pumpDeferred()
						// picks it up when one of its siblings finishes.
						continue;
					}

					if (!allowedByCircuit(node.getKind())) {
						// This kind is failing everywhere. Park rather than fail: the problem
						// is usually environmental and usually gets fixed, and failing a
						// hundred thousand items over a missing model file helps nobody.
						continue;
					}

					// A multi-node segment is dispatched as a unit so its intermediate
					// results never leave the worker. Falls through to per-node dispatch
					// when the segment is a single node, or when no worker will take it
					// whole. Segments stay single-mode for now, so only the seq-0 execution
					// of a non-fanned node is eligible.
					if (seq == 0 && node.getExecutionMode() == ExecutionMode.SINGLE) {
						PipelineSegment segment = graph.getSegmentFor(nodeId);
						// A fused segment runs end to end inside one worker and only its last node's
						// outputs come back, so a breakpoint anywhere inside it would have nothing to
						// show and nothing to hold. Falling back to per-node dispatch costs a round
						// trip per node and buys the intermediate results — which is the trade the
						// operator made by setting the breakpoint.
						if (segment != null && !segment.isSingleNode() && !segmentHasBreakpoint(segment)
							&& segmentReady(state, segment)) {
							Boolean settled = dispatchSegment(state, segment);
							if (settled != null) {
								progressed |= settled;
								continue;
							}
							// null means no worker took the segment whole; fall back to nodes.
						}
					}

					// A rejected dispatch settles the execution immediately, which can unblock
					// children in this same pass.
					progressed |= dispatch(state, node, seq);
				}
			}
		}
	}

	/**
	 * Whether every dependency of this node has fully settled.
	 *
	 * <p>
	 * <strong>This is where the implicit gather happens.</strong> {@link ItemState#isSettled(String)}
	 * answers over a node's whole execution set, so for a dependency that fanned out it means every
	 * sibling element has finished. A node with a sequence input therefore blocks here until the
	 * entire branch is done and then runs exactly once, receiving all of it — which is precisely the
	 * "recombine per source asset" behaviour, obtained by redefining an existing check rather than
	 * by adding machinery.
	 * </p>
	 */
	private boolean dependenciesSettled(ItemState state, PipelineGraphNode node) {
		for (String dep : node.getDependencies()) {
			if (!state.isSettled(dep)) {
				return false;
			}
			// A breakpoint is nothing more than this: the dependency ran and settled, but is not
			// yet allowed to count as available. Everything downstream stays exactly where it
			// would have been had the node not finished, while the node's own result is already
			// persisted and readable — which is the whole reason to stop here.
			if (state.isHeld(dep)) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Decide whether this node should be skipped instead of dispatched.
	 *
	 * @return the skip result, or null when the node should run
	 */
	private NodeTaskResult evaluateSkip(ItemState state, PipelineGraphNode node, int seq) {
		if (graph.isDryRun()) {
			return NodeTaskResult.skipped(node.getId(), seq, "dry-run");
		}

		for (String dep : node.getDependencies()) {
			NodeExecState depState = state.exec(dep);
			if (depState == null) {
				continue;
			}

			// For a dependency that fanned out, a per-element consumer is only stopped by *its own*
			// element failing - a sibling element's failure must not take out the whole row. A node
			// that gathers the sequence, on the other hand, sees the branch as one unit and is
			// stopped by any failure in it, which is the whole-node rule it replaces.
			NodeState depOutcome = elementScopedState(node, depState, seq);

			// Blocking is a property of the dependent node, not of the dependency.
			// A non-blocking node runs anyway and sees the failure in its inputs.
			if (depOutcome == NodeState.FAILED && node.isBlocking()) {
				return NodeTaskResult.skipped(node.getId(), seq,
					node.getExecutionMode() == ExecutionMode.PER_ELEMENT
						? "Dependency " + dep + " failed for element " + seq
						: "Dependency " + dep + " failed");
			}

			FilterBranch branch = node.branchFor(dep);
			if (branch != FilterBranch.ANY) {
				NodeTaskResult depResult = node.getExecutionMode() == ExecutionMode.PER_ELEMENT
					&& depState.getElementResults().containsKey(seq)
						? depState.getElementResults().get(seq)
						: depState.representative();
				Boolean passed = depResult == null ? null : depResult.getFilterPassed();
				if (!branch.admits(passed)) {
					return NodeTaskResult.skipped(node.getId(), seq,
						"Filter branch " + branch + " not taken on dependency " + dep);
				}
			}

			// Port routing: the port is the branch. A consumer wired only to ports this dependency did
			// not write for this item is not on the path the item took.
			//
			// Deliberately not applied when the dependency FAILED: a blocking consumer was already
			// skipped above, and a non-blocking one is meant to run and see the failure in its inputs -
			// skipping it here would quietly break blocking:false.
			if (depOutcome != NodeState.FAILED && !routedDependencyDelivered(node, dep, depState, seq)) {
				return NodeTaskResult.skipped(node.getId(), seq,
					"No routed output of dependency " + dep + " carried data"
						+ (node.getExecutionMode() == ExecutionMode.PER_ELEMENT ? " for element " + seq : ""));
			}
		}
		return null;
	}

	/**
	 * Whether any routed edge from {@code dep} carried data to this node for this execution.
	 *
	 * <p>
	 * The test is an <em>or</em> across the dependency's routed bindings, not an <em>and</em>: a node
	 * fed by two ports of the same producer — {@code watermark}'s {@code image} and {@code video}, say
	 * — runs when either delivers. Across <em>different</em> dependencies the loop in
	 * {@link #evaluateSkip} ands them, which matches how {@link FilterBranch} already behaves.
	 * </p>
	 *
	 * @return true when the node should run: some routed binding delivered, or there is no routed
	 *         binding from this dependency at all and the rule simply does not apply
	 */
	private boolean routedDependencyDelivered(PipelineGraphNode node, String dep, NodeExecState depState, int seq) {
		boolean anyRouted = false;
		for (InputBinding binding : node.getInputBindings()) {
			if (!binding.sourceNodeId().equals(dep) || !binding.routed()) {
				continue;
			}
			anyRouted = true;
			Collected collected = collect(depState, binding, seq);
			if (collected != null && !collected.elements().isEmpty()) {
				return true;
			}
		}
		return !anyRouted;
	}

	/**
	 * How a dependency looks from one execution's point of view.
	 *
	 * @return the matching element's state for a per-element consumer, otherwise the dependency's
	 *         rolled-up state
	 */
	private NodeState elementScopedState(PipelineGraphNode node, NodeExecState depState, int seq) {
		if (node.getExecutionMode() == ExecutionMode.PER_ELEMENT) {
			NodeTaskResult own = depState.getElementResults().get(seq);
			if (own != null) {
				return own.getState();
			}
		}
		return depState.rollup();
	}

	/**
	 * A segment can only go out as a unit if <em>every</em> node in it is ready.
	 *
	 * <p>Partial dispatch is not an option: the worker runs the whole list, so a
	 * segment whose middle node is still waiting on something outside would execute
	 * that node before its dependency settled.</p>
	 */
	/**
	 * Whether any node in this segment is breakpointed.
	 *
	 * <p>
	 * Cheap enough to ask on every dispatch: the set is empty on every run nobody is debugging, so
	 * this is a single {@code isEmpty()} on the hot path.
	 * </p>
	 */
	private boolean segmentHasBreakpoint(PipelineSegment segment) {
		if (breakpoints.isEmpty()) {
			return false;
		}
		for (String nodeId : segment.getNodeIds()) {
			if (breakpoints.contains(nodeId)) {
				return true;
			}
		}
		return false;
	}

	private boolean segmentReady(ItemState state, PipelineSegment segment) {
		for (String dep : segment.getDependencies()) {
			if (!state.isSettled(dep)) {
				return false;
			}
		}
		if (graph.isDryRun()) {
			// Nothing is dispatched in a dry run; the per-node path records the skips.
			return false;
		}
		for (String kind : segment.getNodeKinds()) {
			if (atKindCapacity(kind)) {
				return false;
			}
		}
		for (String nodeId : segment.getNodeIds()) {
			if (state.isSettled(nodeId) || state.isInFlight(nodeId) || state.isAwaitingRetry(nodeId)) {
				// Something already touched part of this segment - a retry of one node,
				// say. Dispatching the whole thing again would re-run its siblings.
				return false;
			}
			if (skippedByExternalDependency(state, segment, graph.getNode(nodeId))) {
				// The engine, not the worker, owns decisions that depend on nodes outside
				// the segment. Hand the whole thing to the per-node path so those skips
				// are recorded properly.
				return false;
			}
		}
		return true;
	}

	/**
	 * Would this node be skipped because of something <em>outside</em> its segment?
	 *
	 * <p>Only external dependencies are consulted. A node's dependencies inside the
	 * segment have no result yet - the worker produces them - so asking about them
	 * here would be both meaningless and, since {@code evaluateSkip} assumes settled
	 * dependencies, a null dereference.</p>
	 */
	private boolean skippedByExternalDependency(ItemState state, PipelineSegment segment, PipelineGraphNode node) {
		for (String dep : node.getDependencies()) {
			if (segment.getNodeIds().contains(dep)) {
				continue;
			}
			NodeTaskResult depResult = state.getResults().get(dep);
			if (depResult == null) {
				// Not settled yet, so this is not a skip - segmentReady's own dependency
				// check decides whether the segment can go at all.
				continue;
			}
			if (depResult.getState() == NodeState.FAILED && node.isBlocking()) {
				return true;
			}
			FilterBranch branch = node.branchFor(dep);
			if (branch != FilterBranch.ANY && !branch.admits(depResult.getFilterPassed())) {
				return true;
			}
			// The port-routing mirror of the branch check above. PipelineSegmenter never puts a
			// selective edge inside a segment, so a routed dependency is always external and always
			// settled by the time we get here - which is why the rolled-up result is enough and no
			// per-element walk is needed.
			if (depResult.getState() != NodeState.FAILED && !routedDependencyDelivered(node, dep, depResult)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * The {@link #routedDependencyDelivered(PipelineGraphNode, String, NodeExecState, int)} test
	 * against a single settled result.
	 */
	private boolean routedDependencyDelivered(PipelineGraphNode node, String dep, NodeTaskResult depResult) {
		boolean anyRouted = false;
		for (InputBinding binding : node.getInputBindings()) {
			if (!binding.sourceNodeId().equals(dep) || !binding.routed()) {
				continue;
			}
			anyRouted = true;
			if (depResult.output(binding.sourcePortId()) != null) {
				return true;
			}
		}
		return !anyRouted;
	}

	/**
	 * Dispatch a whole segment.
	 *
	 * @return true when it settled synchronously, false when results will arrive
	 *         later, or null when no worker would take it and the caller should fall
	 *         back to per-node dispatch
	 */
	private Boolean dispatchSegment(ItemState state, PipelineSegment segment) {
		// Time ordered: this becomes a pipeline_run_task row id, and those page by uuid.
		UUID taskUuid = LoomUUID.timeOrdered();
		List<SegmentNode> segNodes = new ArrayList<>();
		for (String nodeId : segment.getNodeIds()) {
			PipelineGraphNode node = graph.getNode(nodeId);
			// effectiveOptions, not node.getOptions(): a setting tried out on one item applies to the
			// rest of the run, and a later item must not quietly revert to the definition just
			// because its nodes happened to fuse into one segment.
			segNodes.add(new SegmentNode(node.getId(), node.getKind(), node.isBlocking(), effectiveOptions(node),
				node.getDependencies()));
		}

		SegmentTask task = new SegmentTask(taskUuid, runUuid, state.getItemId(), segment.getId(),
			segment.getAffinity(), state.getMedia(), segNodes, collectSegmentInputs(state, segment));

		String worker;
		try {
			worker = dispatcher.dispatch(task);
		} catch (Exception e) {
			log.error("Segment dispatch of {} threw", task, e);
			worker = null;
		}
		if (worker == null) {
			// No worker is permitted to run every kind in the segment. Falling back to
			// per-node keeps the run moving, at the cost affinity was meant to avoid.
			log.debug("No worker took segment {} whole; falling back to per-node dispatch", segment.getId());
			return null;
		}

		long dispatchedAt = System.currentTimeMillis();
		for (String nodeId : segment.getNodeIds()) {
			state.markInFlight(nodeId, taskUuid);
			state.recordAttempt(nodeId);
			// Every member's clock starts at the one dispatch, so a member's latency includes the
			// time its predecessors in the segment spent running. That is the honest figure: the
			// member did not become available to anything downstream any earlier.
			state.markDispatched(nodeId, 0, dispatchedAt);
			// Per kind rather than per dispatch: a segment genuinely occupies capacity
			// for every kind it carries, even though it is one outstanding request.
			acquireKind(graph.getNode(nodeId).getKind());
		}
		// One dispatch, one slot: a segment is a single outstanding request no matter
		// how many nodes it carries.
		inFlightCount++;
		for (NodeTask memberTask : toNodeTasks(task, segment)) {
			store.segmentTaskDispatched(itemUuid(state), memberTask, worker, taskUuid);
		}
		return false;
	}

	/**
	 * Outputs a segment needs from outside itself.
	 */
	private Map<String, PortPayload> collectSegmentInputs(ItemState state, PipelineSegment segment) {
		// Collected once for the whole segment, keyed by the receiving port ids of every node in it.
		// Ports are unique per node and a segment's members do not share input port names by
		// construction, so one flat map serves the whole unit.
		Map<String, PortPayload> inputs = new LinkedHashMap<>();
		for (String nodeId : segment.getNodeIds()) {
			PipelineGraphNode node = graph.getNode(nodeId);
			if (node == null) {
				continue;
			}
			for (Map.Entry<String, PortPayload> entry : buildInputs(state, node, 0).entrySet()) {
				// Only what genuinely arrives from outside: an in-segment dependency is satisfied
				// on the worker and never crosses the network, which is the saving.
				if (segment.getNodeIds().contains(sourceNodeOf(node, entry.getKey()))) {
					continue;
				}
				inputs.putIfAbsent(entry.getKey(), entry.getValue());
			}
		}
		return inputs;
	}

	/**
	 * @return which node feeds the given input port of a node, or null when it is unwired
	 */
	private String sourceNodeOf(PipelineGraphNode node, String portId) {
		for (InputBinding binding : node.getInputBindings()) {
			if (binding.targetPortId().equals(portId)) {
				return binding.sourceNodeId();
			}
		}
		return null;
	}

	/**
	 * Represent a segment to the state store, which records one task per node.
	 *
	 * <p>
	 * One task per member, each with its own row uuid. The store's key is the execution, so a
	 * member that shared a uuid with another could not settle onto a row of its own - which is
	 * exactly what happened while the whole segment was attributed to its first node: the rest
	 * collided on the primary key and their outcomes were dropped. The segment's own task uuid is
	 * passed separately, as the dispatch they were all carried by.
	 * </p>
	 *
	 * <p>
	 * The first member keeps the segment's task uuid as its row id, so anything already holding
	 * that id - {@code asset_segment_comp.task_uuid}, an operator following a log line - still
	 * resolves to a row.
	 * </p>
	 */
	private List<NodeTask> toNodeTasks(SegmentTask task, PipelineSegment segment) {
		List<NodeTask> tasks = new ArrayList<>();
		for (String nodeId : segment.getNodeIds()) {
			UUID rowUuid = tasks.isEmpty() ? task.getTaskUuid() : LoomUUID.timeOrdered();
			tasks.add(new NodeTask(rowUuid, runUuid, task.getItemId(), nodeId,
				graph.getNode(nodeId).getKind(), task.getMedia(), Map.of(), task.getInputs()));
		}
		return tasks;
	}

	/**
	 * Assimilate the per-node outcomes of a segment.
	 *
	 * <p>Applied one node at a time through the ordinary result path, so retries,
	 * dead-lettering and downstream unblocking behave exactly as they do for
	 * individually dispatched nodes.</p>
	 *
	 * @param itemId  the item
	 * @param results the per-node outcomes
	 * @param error   a segment-level failure, or null
	 */
	public synchronized void onSegmentTaskResult(String itemId, String segmentId, List<NodeTaskResult> results,
		String error) {
		requireStarted();
		ItemState state = items.get(itemId);
		if (state == null) {
			log.warn("Segment result for unknown item '{}' in run {} - ignoring", itemId, runUuid);
			return;
		}

		if (error != null && (results == null || results.isEmpty())) {
			// Nothing ran. Fail every node of the segment, or they stay in flight
			// forever waiting for results that will not come.
			PipelineSegment segment = findSegment(segmentId);
			if (segment != null) {
				for (String nodeId : segment.getNodeIds()) {
					if (!state.isSettled(nodeId)) {
						releaseInFlight(state, nodeId, 0);
						state.record(NodeTaskResult.failed(null, nodeId, 0, "Segment failed: " + error));
						store.taskSettled(itemUuid(state), state.getResults().get(nodeId));
					}
				}
			}
			advance(state);
			pumpDeferred();
			checkComplete();
			return;
		}

		for (NodeTaskResult result : results) {
			if (state.isSettled(result.getNodeId())) {
				log.warn("Duplicate segment result for node '{}' on item '{}' - ignoring", result.getNodeId(), itemId);
				continue;
			}
			record(state, result);
		}
		advance(state);
		pumpDeferred();
		checkComplete();
	}

	private PipelineSegment findSegment(String segmentId) {
		for (PipelineSegment segment : graph.getSegments()) {
			if (segment.getId().equals(segmentId)) {
				return segment;
			}
		}
		return null;
	}

	/**
	 * @return true when the node was settled synchronously (dispatch was refused),
	 *         false when a result is expected to arrive later
	 */
	private boolean dispatch(ItemState state, PipelineGraphNode node) {
		return dispatch(state, node, 0);
	}

	/**
	 * @param seq which element of a fanned-out sequence this dispatch covers; 0 for a node that
	 *            runs once per item
	 * @return true when the execution was settled synchronously (dispatch was refused),
	 *         false when a result is expected to arrive later
	 */
	private boolean dispatch(ItemState state, PipelineGraphNode node, int seq) {
		// Time ordered: this becomes a pipeline_run_task row id, and those page by uuid.
		UUID taskUuid = LoomUUID.timeOrdered();
		NodeTask task = new NodeTask(taskUuid, runUuid, state.getItemId(), node.getId(), node.getKind(),
			seq, state.getMedia(), effectiveOptions(node), buildInputs(state, node, seq),
			node.getDemandedOutputs(), graph.getResultBatchSize(), capturePreviews,
			state.generationFor(node.getId(), seq));

		state.markInFlight(node.getId(), seq, taskUuid);
		state.recordAttempt(node.getId(), seq);
		acquireKind(node.getKind());
		inFlightCount++;

		String worker;
		try {
			worker = dispatcher.dispatch(task);
		} catch (Exception e) {
			log.error("Dispatch of {} threw", task, e);
			worker = null;
		}
		// Recorded after the attempt so the chosen worker can be stored with it.
		store.taskDispatched(itemUuid(state), task, worker);

		if (worker == null) {
			// No worker could take it. Fail the node rather than leaving the run
			// stalled forever waiting for a result that will never arrive.
			record(state, NodeTaskResult.failed(taskUuid, node.getId(), seq, 0,
				"No worker available for node kind '" + node.getKind() + "'", null));
			return true;
		}
		// Only now, with the task genuinely on a worker, does the dispatch-to-result clock start.
		state.markDispatched(node.getId(), seq, System.currentTimeMillis());
		return false;
	}

	/**
	 * Fill this node's input ports from the wired edges.
	 *
	 * <p>
	 * The engine resolves which upstream {@code (node, port)} feeds each input port, so the task
	 * carries data keyed by <em>the receiving node's own</em> port ids. A node therefore never has
	 * to know what the pipeline author called its neighbours — the failure mode where renaming a
	 * node in the editor silently starved a downstream lookup is gone by construction.
	 * </p>
	 *
	 * <p>
	 * This is also where the gather materialises. A {@code MANY} input concatenates every element
	 * of every edge feeding it, in sequence order; because the node only runs once its dependencies
	 * have fully settled, those elements are exactly the branch's whole output for this origin item.
	 * A {@code ONE} input either takes the single upstream value, or — when the producer itself ran
	 * per element — the element whose sequence index matches this execution, which is the zip.
	 * </p>
	 */
	private Map<String, PortPayload> buildInputs(ItemState state, PipelineGraphNode node, int seq) {
		Map<String, List<DataElement>> gathered = new LinkedHashMap<>();
		Map<String, String> contentTypes = new LinkedHashMap<>();
		Map<String, Boolean> many = new LinkedHashMap<>();

		for (InputBinding binding : node.getInputBindings()) {
			NodeExecState producer = state.exec(binding.sourceNodeId());
			if (producer == null) {
				continue;
			}
			Collected collected = collect(producer, binding, seq);
			if (collected == null) {
				continue;
			}

			String targetPort = binding.targetPortId();
			contentTypes.putIfAbsent(targetPort, collected.contentType());
			many.put(targetPort, binding.targetIsMany());
			gathered.computeIfAbsent(targetPort, k -> new ArrayList<>()).addAll(collected.elements());
		}

		Map<String, PortPayload> inputs = new LinkedHashMap<>();
		for (Map.Entry<String, List<DataElement>> entry : gathered.entrySet()) {
			String portId = entry.getKey();
			boolean isMany = Boolean.TRUE.equals(many.get(portId));
			inputs.put(portId, new PortPayload(contentTypes.get(portId), isMany ? "MANY" : "ONE", entry.getValue()));
		}
		return inputs;
	}

	/**
	 * What one binding contributes to its target port for one execution.
	 *
	 * @param contentType
	 *            the producing port's content type
	 * @param elements
	 *            the elements this binding delivers; never null, may be empty when the producer
	 *            fanned out and no element belongs to this execution
	 */
	private record Collected(String contentType, List<DataElement> elements) {
	}

	/**
	 * Read one binding's contribution out of its producer's results.
	 *
	 * <p>
	 * Extracted so {@link #buildInputs} and {@link #routedDependencyDelivered} answer "did this edge
	 * deliver anything?" with <em>the same</em> code. Deriving that twice is how the two drift, and
	 * the failure mode is silent in both directions: a node dispatched with an empty required input,
	 * or a node skipped while its data was sitting there.
	 * </p>
	 *
	 * @return {@code null} when the producer wrote nothing at all on that port
	 */
	private Collected collect(NodeExecState producer, InputBinding binding, int seq) {
		List<DataElement> elements = new ArrayList<>();
		String contentType = null;
		// Walk the producer's elements in sequence order so a gathered payload is stable and a
		// zip can address it by index.
		for (Integer producerSeq : producer.getElementResults().keySet().stream().sorted().toList()) {
			NodeTaskResult result = producer.getElementResults().get(producerSeq);
			PortPayload payload = result.output(binding.sourcePortId());
			if (payload == null) {
				continue;
			}
			contentType = payload.getContentType();
			elements.addAll(payload.getElements());
		}
		if (contentType == null) {
			return null;
		}

		if (binding.targetIsMany() || elements.size() <= 1) {
			return new Collected(contentType, elements);
		}

		// The producer fanned out and this input takes one element: pick the element that belongs to
		// this execution. Matching on origin.seq rather than list position means a gap left by a
		// failed sibling cannot silently shift the alignment.
		for (DataElement element : elements) {
			if (element.getOrigin().getSeq() == seq) {
				return new Collected(contentType, List.of(element));
			}
		}
		return new Collected(contentType, List.of());
	}

	/**
	 * How many elements the driving sequence produced for this item.
	 *
	 * <p>
	 * Read off the bindings that actually make this node per-element — its single-element inputs —
	 * rather than off the fan-out driver directly. A node two hops below the driver is not wired to
	 * it at all; it consumes the intermediate node, which already knows how wide the sequence is.
	 * Taking the width from the intermediate node also keeps it right when some elements failed:
	 * counting surviving payloads would shrink the sequence and silently re-index everything below.
	 * </p>
	 */
	private int fanOutSize(ItemState state, PipelineGraphNode node) {
		int max = 0;
		for (InputBinding binding : node.getInputBindings()) {
			if (binding.targetIsMany()) {
				// A sequence input gathers rather than iterates, so it says nothing about how
				// many times this node runs.
				continue;
			}
			PipelineGraphNode producer = graph.getNode(binding.sourceNodeId());
			NodeExecState producerState = state.exec(binding.sourceNodeId());
			if (producer == null || producerState == null) {
				continue;
			}
			if (producer.getExecutionMode() == ExecutionMode.PER_ELEMENT) {
				// The producer runs once per element of the same fan-out, so its width is ours.
				Integer count = producerState.getElementCount();
				if (count != null) {
					max = Math.max(max, count);
				}
				continue;
			}
			int count = 0;
			for (NodeTaskResult result : producerState.getElementResults().values()) {
				PortPayload payload = result.output(binding.sourcePortId());
				if (payload != null) {
					count += payload.size();
				}
			}
			max = Math.max(max, count);
		}
		return max;
	}

	/**
	 * Settle a node and tell the store about it.
	 *
	 * <p>Every path that settles a node goes through here - a completed dispatch, a
	 * skip, and a refused dispatch alike. Recording in only some of them is how a
	 * recovered run ends up re-running work it already did.</p>
	 */
	private void record(ItemState state, NodeTaskResult result) {
		recordCircuitOutcome(result);
		syncToLoom(state, result);
		notifySettled(state, result);
		// Per element: a node downstream of a fan-out can have siblings outstanding while this
		// execution settles. Asking "is this node in flight?" would see a sibling's task and
		// release a slot that was never acquired for the execution being settled - a skip
		// recorded next to two running elements would silently give a slot away.
		if (state.isInFlight(result.getNodeId(), result.getElementSeq())) {
			inFlightCount = Math.max(0, inFlightCount - 1);
			releaseKind(result.getNodeId());
			recordLatency(state, result);
		}
		state.record(result);
		// After the record, never before: holding is a property of a settled execution, and the
		// exec state it marks is created by the record itself.
		applyBreakpoint(state, result);
		UUID itemUuid = itemUuid(state);
		store.taskSettled(itemUuid, result);
		if (state.isComplete(graph.size())) {
			store.itemSettled(itemUuid, state.outcome());
		}
	}

	/**
	 * Report how long a settling execution spent between dispatch and result.
	 *
	 * <p>The dispatch counters say work left Loom; only this says whether it came back, and how
	 * slowly. Silent on an execution that was never placed on a worker - its clock was never
	 * started - so a fleet with no capacity shows dispatch failures rather than a suspiciously
	 * fast p99.</p>
	 */
	private void recordLatency(ItemState state, NodeTaskResult result) {
		Long dispatchedAt = state.dispatchedAt(result.getNodeId(), result.getElementSeq());
		if (dispatchedAt == null) {
			return;
		}
		PipelineGraphNode node = graph.getNode(result.getNodeId());
		if (node == null) {
			return;
		}
		String stateLabel = result.getState() == null ? "unknown" : result.getState().name().toLowerCase();
		metrics.recordNodeTaskLatency(node.getKind(), stateLabel,
			Math.max(0, System.currentTimeMillis() - dispatchedAt));
	}

	/**
	 * @param state the item
	 * @return its store-assigned id, or null when the id is not a UUID (which only
	 *         happens with a store that does not persist)
	 */
	private UUID itemUuid(ItemState state) {
		try {
			return UUID.fromString(state.getItemId());
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	/**
	 * Ask the breaker, and make sure something will come back to try again.
	 *
	 * <p>A parked node is neither settled nor in flight, so nothing would naturally
	 * revisit it - a run whose only remaining work is parked would simply stop. The
	 * retry scheduler is reused to poke the engine once the cooldown has elapsed.</p>
	 */
	private boolean allowedByCircuit(String nodeKind) {
		if (circuitBreaker == null) {
			return true;
		}
		if (circuitBreaker.allowDispatch(nodeKind)) {
			return true;
		}
		// Re-entrancy guard. An un-park sweeps the run, which walks straight back
		// through here; without this, a scheduler that runs actions immediately - the
		// default - would schedule, sweep, re-park and re-schedule forever, and the
		// cooldown it is supposed to be honouring would never elapse.
		if (unparking || parkedKinds.contains(nodeKind)) {
			return false;
		}
		long parkedFor = circuitBreaker.parkedForMs(nodeKind);
		parkedKinds.add(nodeKind);
		log.warn("Node kind '{}' is parked by its circuit breaker; retrying in {}ms", nodeKind, parkedFor);
		retryScheduler.schedule(parkedFor, () -> unparkKind(nodeKind));
		return false;
	}

	private synchronized void unparkKind(String nodeKind) {
		parkedKinds.remove(nodeKind);
		unparking = true;
		try {
			pumpDeferred();
			checkComplete();
		} finally {
			unparking = false;
		}
	}

	/**
	 * Tell the breaker how a node turned out.
	 *
	 * <p>Only real executions count. A skip says nothing about whether the kind works,
	 * and counting skips would let a heavily filtered pipeline look like a broken
	 * one.</p>
	 */
	private void recordCircuitOutcome(NodeTaskResult result) {
		if (circuitBreaker == null || result.getState() == NodeState.SKIPPED) {
			return;
		}
		PipelineGraphNode node = graph.getNode(result.getNodeId());
		if (node != null) {
			circuitBreaker.record(node.getKind(), result.getState() == NodeState.COMPLETED);
		}
	}

	/**
	 * Install a shared circuit breaker.
	 *
	 * @param breaker the breaker, shared across runs so a kind broken everywhere is
	 *                seen as such; null disables the feature
	 */
	public synchronized void setCircuitBreaker(NodeKindCircuitBreaker breaker) {
		this.circuitBreaker = breaker;
	}

	/**
	 * Install the metrics catalog this run reports through.
	 *
	 * <p>Set alongside the circuit breaker and the retry scheduler rather than taken as a constructor
	 * argument, so the several thousand lines of evaluation semantics stay constructible - and
	 * testable - with nothing but a graph and a dispatcher.</p>
	 *
	 * @param metrics the catalog; null restores the no-op
	 */
	public synchronized void setMetrics(LoomMetrics metrics) {
		this.metrics = metrics == null ? NoopLoomMetrics.INSTANCE : metrics;
	}

	/**
	 * Hand a node's outputs to the asset sink when the definition asked for it.
	 *
	 * <p>Only completed nodes with outputs qualify: a skip produced nothing, and a
	 * failure's message is not asset data. This is what finally makes {@code
	 * syncToLoom} mean something - it has been parsed into the graph and read by
	 * nothing since the format gained it.</p>
	 */
	private void syncToLoom(ItemState state, NodeTaskResult result) {
		if (result.getState() != NodeState.COMPLETED || result.getOutputs().isEmpty()) {
			return;
		}
		PipelineGraphNode node = graph.getNode(result.getNodeId());
		if (node == null || !node.isSyncToLoom()) {
			return;
		}
		try {
			assetSink.persist(state.getMedia(), result.getNodeId(), result.getOutputs());
		} catch (Exception e) {
			// Losing the write is bad; failing the run that produced the data is worse.
			log.error("Failed to persist outputs of node '{}' for {}", result.getNodeId(), state.getMedia(), e);
		}
	}

	/**
	 * Install a destination for outputs of nodes marked {@code syncToLoom}.
	 *
	 * @param sink the sink; null restores the discarding default
	 */
	public synchronized void setAssetSink(AssetSink sink) {
		this.assetSink = sink == null ? AssetSink.NOOP : sink;
	}

	private void notifySettled(ItemState state, NodeTaskResult result) {
		for (NodeSettleListener listener : settleListeners) {
			try {
				listener.onNodeSettled(state.getItemId(), state.getMedia() == null ? null : state.getMedia().getPath(),
					result.getNodeId(), result.getState(), result.getMessage());
			} catch (Exception e) {
				// A misbehaving observer must not derail the run it is observing.
				log.error("Node settle listener threw", e);
			}
		}
	}

	/**
	 * Settle a node from a result an earlier run produced, if one applies.
	 *
	 * <p>Recorded as {@code COMPLETED} carrying the original outputs, <strong>not</strong>
	 * as a skip. A skip carries nothing, and every downstream node would lose the
	 * value it depends on - the hash would vanish and the pipeline would quietly do
	 * less on the second run than the first.</p>
	 *
	 * @return true when the node was settled from a previous result
	 */
	private boolean adoptPreviousResult(ItemState state, PipelineGraphNode node) {
		if (!graph.isReuseResults() || graph.isDryRun()) {
			return false;
		}
		java.util.Optional<NodeTaskResult> previous;
		try {
			previous = store.previousResult(state.getMedia(), node.getId());
		} catch (Exception e) {
			// Reuse is an optimisation. If the lookup fails, run the node.
			log.error("Could not look up a previous result for node '{}'", node.getId(), e);
			return false;
		}
		if (previous.isEmpty()) {
			return false;
		}

		NodeTaskResult reused = previous.get();
		log.debug("Reusing the previous result of node '{}' for {}", node.getId(), state.getMedia().getPath());
		// Deliberately not routed through recordCircuitOutcome: nothing executed, and
		// counting a reuse as a success would mask a kind that is failing everywhere.
		state.record(NodeTaskResult.completed(null, node.getId(), 0, reused.getOutputs()));
		// Held exactly as a fresh execution would be. A breakpoint that fired on the first run and
		// then silently did not on the second — because the result happened to be reusable — would
		// be the kind of surprise that makes a debugger untrustworthy.
		applyBreakpoint(state, state.getResults().get(node.getId()));
		syncToLoom(state, state.getResults().get(node.getId()));
		notifySettled(state, state.getResults().get(node.getId()));
		if (state.isComplete(graph.size())) {
			store.itemSettled(itemUuid(state), state.outcome());
		}
		store.taskSettled(itemUuid(state), state.getResults().get(node.getId()));
		return true;
	}

	/**
	 * Stop counting a node against the in-flight ceiling.
	 *
	 * <p>Every path that ends a dispatched task without settling it - a retry, a
	 * reclaim - must come through here. Clearing the in-flight marker without
	 * decrementing leaks a slot, and enough leaked slots wedge the run permanently at
	 * capacity with nothing outstanding.</p>
	 */
	private void releaseInFlight(ItemState state, String nodeId, int seq) {
		if (state.isInFlight(nodeId, seq)) {
			inFlightCount = Math.max(0, inFlightCount - 1);
			releaseKind(nodeId);
			state.clearInFlight(nodeId, seq);
		}
	}

	private void acquireKind(String nodeKind) {
		inFlightByKind.merge(nodeKind, 1, Integer::sum);
	}

	/**
	 * Release a node's kind slot.
	 *
	 * <p>Takes a node id rather than a kind so callers cannot pair an acquire on one
	 * kind with a release on another - the mistake that leaks a slot, and enough
	 * leaked slots wedge a kind permanently at its ceiling.</p>
	 */
	private void releaseKind(String nodeId) {
		PipelineGraphNode node = graph.getNode(nodeId);
		if (node == null) {
			return;
		}
		inFlightByKind.computeIfPresent(node.getKind(), (k, count) -> count <= 1 ? null : count - 1);
	}

	/**
	 * @param nodeKind the kind
	 * @return true when this kind already holds as many tasks as it may
	 */
	private boolean atKindCapacity(String nodeKind) {
		Integer ceiling = maxInFlightByKind.get(nodeKind);
		if (ceiling == null || ceiling <= 0) {
			return false;
		}
		return inFlightByKind.getOrDefault(nodeKind, 0) >= ceiling;
	}

	/**
	 * Cap how much of a run one node kind may occupy.
	 *
	 * <p>The per-run ceiling stops one run consuming the fleet; this stops one
	 * <em>kind</em> consuming a run. Without it a graph containing both transcription
	 * and hashing lets the slow kind take every slot, and the cheap work that could
	 * have finished in the meantime simply waits.</p>
	 *
	 * @param nodeKind the kind
	 * @param max      ceiling; 0 or less removes it
	 */
	public synchronized void setMaxInFlightForKind(String nodeKind, int max) {
		if (max <= 0) {
			maxInFlightByKind.remove(nodeKind);
		} else {
			maxInFlightByKind.put(nodeKind, max);
		}
		pumpDeferred();
	}

	/** @return outstanding tasks for this kind */
	public synchronized int getInFlightForKind(String nodeKind) {
		return inFlightByKind.getOrDefault(nodeKind, 0);
	}

	/** @return true when this run already has as much outstanding work as it may */
	private boolean atCapacity() {
		return maxInFlight > 0 && inFlightCount >= maxInFlight;
	}

	/**
	 * Give every item a chance to dispatch work that was held back by the cap.
	 *
	 * <p>A node deferred on item B is not reconsidered when item A finishes unless
	 * something sweeps the whole set - without this, deferred work would only resume
	 * when its own item happened to progress, which it cannot do while it is blocked.</p>
	 */
	private void pumpDeferred() {
		releaseCapacityWaiters();
		if (atCapacity()) {
			return;
		}
		for (ItemState state : items.values()) {
			if (atCapacity()) {
				return;
			}
			if (!state.isComplete(graph.size())) {
				advance(state);
			}
		}
	}

	/**
	 * @return true when the run is holding as many tasks as it is allowed to
	 */
	public synchronized boolean isAtCapacity() {
		return atCapacity();
	}

	/**
	 * Run an action once this run has room for more work.
	 *
	 * <p>This is what turns the in-flight ceiling into real backpressure. Capping
	 * dispatch alone bounds how much is <em>outstanding</em>, not how much has been
	 * <em>discovered</em> - a fast scan still piles up item state for media nobody
	 * will look at for hours. Deferring the source acknowledgement until there is
	 * room stops the scan itself, which is the only thing that actually bounds
	 * memory.</p>
	 *
	 * <p>Runs immediately when there is already room. Waiters are also released when
	 * the run completes, so a source is never left waiting on a run that has stopped
	 * consuming.</p>
	 *
	 * @param action typically "acknowledge the batch"
	 */
	public synchronized void whenCapacityAvailable(Runnable action) {
		// A paused run parks the waiter even when there is room: withholding the source
		// acknowledgement is what actually stops the scan. A completed run always releases,
		// so a source is never stranded.
		//
		// A held run parks it for the same reason and one more: without it, a breakpoint would
		// stop each item's graph but not the scan, so a run over 100 000 files would enumerate all
		// of them and hold a hundred thousand executions while someone reads the first one.
		if ((!atCapacity() && !paused && countHeld() == 0) || runComplete) {
			action.run();
			return;
		}
		capacityWaiters.add(action);
	}

	/**
	 * Release anything waiting for room.
	 *
	 * <p>Waiters are drained before dispatching further work, so a source that has
	 * been held back gets to send its next batch rather than being starved by items
	 * already in the engine.</p>
	 */
	private void releaseCapacityWaiters() {
		if (capacityWaiters.isEmpty() || (atCapacity() && !runComplete)) {
			return;
		}
		if ((paused || countHeld() > 0) && !runComplete) {
			// Suspended, or stopped at a breakpoint: keep the source held. A terminal run still
			// releases below, so cancelling a paused or held run does not strand it.
			return;
		}
		List<Runnable> waiting = new ArrayList<>(capacityWaiters);
		capacityWaiters.clear();
		for (Runnable action : waiting) {
			try {
				action.run();
			} catch (Exception e) {
				log.error("Capacity waiter threw", e);
			}
		}
	}

	/** @return outstanding dispatched tasks */
	public synchronized int getInFlightCount() {
		return inFlightCount;
	}

	/**
	 * @return this run's ceiling on outstanding tasks; 0 or less means unlimited.
	 *         Read against {@link #getInFlightCount()}: outstanding work alone cannot say whether a
	 *         run is saturated or merely busy, and it is saturation that decides whether adding
	 *         workers would help.
	 */
	public synchronized int getMaxInFlight() {
		return maxInFlight;
	}

	/**
	 * Per-node counts of work outstanding and work waiting.
	 *
	 * <p>"Waiting" means ready or blocked but not yet settled or dispatched — held
	 * back by a ceiling, a parked kind, or an unfinished dependency. Reporting it as
	 * zero, as the events previously did, makes a saturated run look idle.</p>
	 *
	 * @return node id to {@code [active, pending]}
	 */
	public synchronized Map<String, int[]> nodeProgressSnapshot() {
		Map<String, int[]> snapshot = new LinkedHashMap<>();
		for (String nodeId : graph.getTopologicalOrder()) {
			snapshot.put(nodeId, new int[] { 0, 0 });
		}
		for (ItemState state : items.values()) {
			for (String nodeId : graph.getTopologicalOrder()) {
				int[] counts = snapshot.get(nodeId);
				if (state.isInFlight(nodeId)) {
					counts[0]++;
				} else if (!state.isSettled(nodeId)) {
					counts[1]++;
				}
			}
		}
		return snapshot;
	}

	/**
	 * @param maxInFlight ceiling on outstanding tasks; 0 or less means unlimited
	 */
	public synchronized void setMaxInFlight(int maxInFlight) {
		this.maxInFlight = maxInFlight;
		pumpDeferred();
	}

	private void checkComplete() {
		if (runComplete || !sourceComplete) {
			return;
		}
		// A held run has outstanding work by definition — an operator is looking at a result and
		// has not said to carry on. Without this, a breakpoint on a node with no dependents (the
		// last node of the graph, most obviously) would leave every item complete, and the run
		// would close out and clear the pause underneath the person debugging it.
		if (countHeld() > 0) {
			return;
		}
		for (ItemState state : items.values()) {
			if (!state.isComplete(graph.size())) {
				return;
			}
		}
		runComplete = true;
		// A paused run that has nothing left outstanding is genuinely finished, and is
		// allowed to close out rather than sitting in PAUSED with no work to resume.
		// Clearing the flag first keeps the state coherent and lets the release below
		// through the pause gate.
		paused = false;
		// Never leave a source blocked on a run that has stopped consuming.
		releaseCapacityWaiters();
		// A batching store must drain here, or the tail of the run - the part that
		// says how it ended - is exactly what gets lost.
		store.flush();
		RunSummary summary = buildSummary();
		log.info("Run {} complete: {}", runUuid, summary);
		for (Consumer<RunSummary> listener : completionListeners) {
			try {
				listener.accept(summary);
			} catch (Exception e) {
				log.error("Run completion listener threw", e);
			}
		}
	}

	private RunSummary buildSummary() {
		long success = 0;
		long failure = 0;
		long skipped = 0;
		for (ItemState state : items.values()) {
			switch (state.outcome()) {
				case SUCCESS:
					success++;
					break;
				case FAILURE:
					failure++;
					break;
				default:
					skipped++;
					break;
			}
		}
		return new RunSummary(items.size(), success, failure, skipped, System.currentTimeMillis() - startedAt);
	}

	private void requireStarted() {
		if (!started) {
			throw new IllegalStateException("Run not started");
		}
	}
}
