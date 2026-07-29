package io.metaloom.loom.pipeline.graph;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import io.metaloom.loom.pipeline.model.FilterBranch;

/**
 * One node of a {@link PipelineGraph}.
 *
 * <p>Immutable. Edges are held by the graph rather than the node so that the node
 * stays a plain description of "what to run", and the graph owns "in what order".</p>
 */
public class PipelineGraphNode {

	/** Group used when a node declares none: one group for the whole pipeline. */
	public static final String DEFAULT_AFFINITY = "default";

	/** Attempts allowed when a node merely says {@code retryFailed: true}. */
	public static final int DEFAULT_RETRY_ATTEMPTS = 2;

	private final String id;
	private final String kind;
	private final String name;
	private final boolean source;
	private final boolean blocking;
	private final boolean syncToLoom;
	private final String affinity;
	private final Map<String, Object> options;
	private final List<String> dependencies;
	private final Map<String, FilterBranch> conditionalDependencies;
	private List<InputBinding> inputBindings;
	private final Set<String> demandedOutputs;
	private ExecutionMode executionMode = ExecutionMode.SINGLE;
	private String fanOutDriver;

	PipelineGraphNode(String id, String kind, String name, boolean source, boolean blocking, boolean syncToLoom,
		String affinity, Map<String, Object> options, List<String> dependencies,
		Map<String, FilterBranch> conditionalDependencies) {
		this(id, kind, name, source, blocking, syncToLoom, affinity, options, dependencies, conditionalDependencies,
			List.of(), Set.of());
	}

	PipelineGraphNode(String id, String kind, String name, boolean source, boolean blocking, boolean syncToLoom,
		String affinity, Map<String, Object> options, List<String> dependencies,
		Map<String, FilterBranch> conditionalDependencies, List<InputBinding> inputBindings,
		Set<String> demandedOutputs) {
		this.id = Objects.requireNonNull(id, "A node id must be set");
		this.kind = Objects.requireNonNull(kind, "A node kind must be set");
		this.name = name == null ? id : name;
		this.source = source;
		this.blocking = blocking;
		this.syncToLoom = syncToLoom;
		this.affinity = affinity == null || affinity.isBlank() ? DEFAULT_AFFINITY : affinity;
		this.options = options == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(options));
		this.dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
		this.conditionalDependencies = conditionalDependencies == null
			? Map.of()
			: Collections.unmodifiableMap(new LinkedHashMap<>(conditionalDependencies));
		this.inputBindings = inputBindings == null ? List.of() : List.copyOf(inputBindings);
		this.demandedOutputs = demandedOutputs == null
			? Set.of()
			: Collections.unmodifiableSet(new LinkedHashSet<>(demandedOutputs));
	}

	public String getId() {
		return id;
	}

	/**
	 * The affinity group this node belongs to.
	 *
	 * <p>Nodes sharing a group and connected in the graph are executed together on one
	 * worker, with intermediate results staying in that worker's memory instead of
	 * round-tripping through Loom. For video this is where the performance case is
	 * won: decode once and analyse many, rather than re-reading the file per node.</p>
	 *
	 * <p><strong>Everything defaults to one group per pipeline.</strong> The tempting
	 * default - each node its own group - silently makes every pipeline maximally
	 * chatty, which is exactly the cost Variant C has to justify. Distribution is
	 * something an author opts into.</p>
	 *
	 * @return the group name, never null
	 */
	public String getAffinity() {
		return affinity;
	}

	/**
	 * @return the node kind, e.g. {@code sha512}; resolved to an implementation by the worker
	 */
	public String getKind() {
		return kind;
	}

	/** @return display name, defaulting to the id */
	public String getName() {
		return name;
	}

	/**
	 * @return true when this node produces the media stream rather than consuming it
	 */
	public boolean isSource() {
		return source;
	}

	/**
	 * Whether a failed dependency prevents this node from running.
	 *
	 * <p>Note the direction: this is a property of the <em>dependent</em> node, not of
	 * the dependency. A non-blocking node runs even when an upstream node failed, and
	 * receives the failed result in its inputs.</p>
	 *
	 * @return true when a failed dependency should skip this node
	 */
	public boolean isBlocking() {
		return blocking;
	}

	/**
	 * @return true when this node's outputs should be persisted back onto the asset
	 */
	public boolean isSyncToLoom() {
		return syncToLoom;
	}

	/** @return per-node options from the definition, never null */
	public Map<String, Object> getOptions() {
		return options;
	}

	/**
	 * How many times this node may be attempted before it is given up on.
	 *
	 * <p>This is where {@code retryFailed} finally does something. Ten descriptors
	 * have advertised the parameter since the node model was written and nothing has
	 * ever read it - a node that declared itself retryable was retried zero times.</p>
	 *
	 * <p>An explicit {@code maxAttempts} option wins over the boolean, so a node can
	 * ask for more than one retry.</p>
	 *
	 * @return the attempt ceiling, always at least 1
	 */
	public int getMaxAttempts() {
		Object explicit = options.get("maxAttempts");
		if (explicit instanceof Number) {
			return Math.max(1, ((Number) explicit).intValue());
		}
		if (explicit instanceof String) {
			try {
				return Math.max(1, Integer.parseInt((String) explicit));
			} catch (NumberFormatException e) {
				// Fall through to the boolean rather than failing the whole run over a
				// malformed option.
			}
		}
		return isRetryFailed() ? DEFAULT_RETRY_ATTEMPTS : 1;
	}

	/** @return true when the definition asked for failures to be retried */
	public boolean isRetryFailed() {
		Object value = options.get("retryFailed");
		if (value instanceof Boolean) {
			return (Boolean) value;
		}
		return value instanceof String && Boolean.parseBoolean((String) value);
	}

	/** @return ids of the nodes that must reach a terminal state before this one runs */
	public List<String> getDependencies() {
		return dependencies;
	}

	/**
	 * Branch constraints on individual dependencies.
	 *
	 * <p>Only <em>direct</em> dependencies are consulted. Filter skipping is therefore
	 * not transitive: a grandchild of a filter runs unless it is itself wired to that
	 * filter's branch.</p>
	 *
	 * @return dependency id to required branch, never null
	 */
	public Map<String, FilterBranch> getConditionalDependencies() {
		return conditionalDependencies;
	}

	/**
	 * @param dependencyId the upstream node
	 * @return the branch this node requires of that dependency, {@link FilterBranch#ANY} by default
	 */
	public FilterBranch branchFor(String dependencyId) {
		return conditionalDependencies.getOrDefault(dependencyId, FilterBranch.ANY);
	}

	/**
	 * Which upstream port feeds each of this node's input ports.
	 *
	 * <p>
	 * {@link #getDependencies()} is derived from these — it is the distinct set of source node ids —
	 * so the engine's scheduling, blocking-skip and branch logic keeps working unchanged while
	 * the data itself is resolved per port.
	 * </p>
	 *
	 * @return the wired bindings, never null
	 */
	public List<InputBinding> getInputBindings() {
		return inputBindings;
	}

	/**
	 * The output ports something downstream is actually wired to.
	 *
	 * <p>
	 * Handed to the worker so a node can skip work nobody asked for.
	 * </p>
	 *
	 * @return demanded output port ids, never null
	 */
	public Set<String> getDemandedOutputs() {
		return demandedOutputs;
	}

	/**
	 * @return whether this node runs once per item or once per element of a fanned-out sequence
	 */
	public ExecutionMode getExecutionMode() {
		return executionMode;
	}

	/**
	 * The node whose {@code MANY} output makes this node per-element.
	 *
	 * <p>
	 * The engine reads the element count off this node's result to learn how many times to dispatch.
	 * </p>
	 *
	 * @return the driving node id, or null when this node is {@link ExecutionMode#SINGLE}
	 */
	public String getFanOutDriver() {
		return fanOutDriver;
	}

	/**
	 * Set by the parser once the whole graph is known — effective multiplicity can only be computed
	 * after every node's bindings are resolved.
	 */
	void setExecution(ExecutionMode executionMode, String fanOutDriver) {
		this.executionMode = executionMode == null ? ExecutionMode.SINGLE : executionMode;
		this.fanOutDriver = fanOutDriver;
	}

	/**
	 * Replace the bindings with copies that know their target port's cardinality.
	 *
	 * <p>
	 * Called by {@link PortGraphAnalyzer} once the descriptors have been resolved, so the engine can
	 * build inputs from the bindings alone.
	 * </p>
	 */
	void setInputBindings(List<InputBinding> inputBindings) {
		this.inputBindings = inputBindings == null ? List.of() : List.copyOf(inputBindings);
	}

	@Override
	public String toString() {
		return "PipelineGraphNode[" + id + " (" + kind + ")" + (source ? ", source" : "") + "]";
	}
}
