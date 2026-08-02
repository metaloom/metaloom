package io.metaloom.cortex.pipeline.core.node;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.metaloom.cortex.pipeline.api.NodeMode;
import io.metaloom.cortex.pipeline.api.filter.FilterBranch;
import io.metaloom.cortex.pipeline.api.node.PipelineNode;

/**
 * Base implementation of {@link PipelineNode} with sensible defaults.
 *
 * <p>Nodes are connected into a DAG via {@link #connectTo(PipelineNode)} and
 * {@link #connectTo(PipelineNode, FilterBranch)}. Dependencies are computed
 * automatically from the inverse of the connection graph.</p>
 */
public abstract class AbstractPipelineNode implements PipelineNode {

	private final String id;
	private final String name;
	private final NodeMode mode;
	private final boolean blocking;
	private final int concurrency;
	private boolean source;
	private boolean syncToLoom;
	private long timeoutMs;

	private final List<PipelineNode> children = new ArrayList<>();
	private final Set<String> parentIds = new HashSet<>();
	private final Map<String, FilterBranch> conditionalDependencies = new HashMap<>();

	protected AbstractPipelineNode(String id, String name, NodeMode mode, boolean blocking, int concurrency) {
		this(id, name, mode, blocking, concurrency, false, 0);
	}

	protected AbstractPipelineNode(String id, String name, NodeMode mode, boolean blocking, int concurrency,
			boolean syncToLoom) {
		this(id, name, mode, blocking, concurrency, syncToLoom, 0);
	}

	protected AbstractPipelineNode(String id, String name, NodeMode mode, boolean blocking, int concurrency,
			boolean syncToLoom, long timeoutMs) {
		this.id = id;
		this.name = name;
		this.mode = mode;
		this.blocking = blocking;
		this.concurrency = concurrency;
		this.syncToLoom = syncToLoom;
		this.timeoutMs = timeoutMs;
	}

	@Override
	public boolean isSource() {
		return source;
	}

	public void setSource(boolean source) {
		this.source = source;
	}

	@Override
	public String id() {
		return id;
	}

	@Override
	public String name() {
		return name;
	}

	@Override
	public NodeMode mode() {
		return mode;
	}

	@Override
	public boolean isBlocking() {
		return blocking;
	}

	@Override
	public Set<String> dependencies() {
		return Collections.unmodifiableSet(parentIds);
	}

	/**
	 * Directly add a parent dependency by id. Used by deserialization and loader
	 * when reconstructing the graph from stored data.
	 *
	 * @param parentId the id of the upstream node
	 */
	public void addDependency(String parentId) {
		parentIds.add(parentId);
	}

	@Override
	public Map<String, FilterBranch> conditionalDependencies() {
		return Collections.unmodifiableMap(conditionalDependencies);
	}

	public void setConditionalDependency(String parentId, FilterBranch branch) {
		conditionalDependencies.put(parentId, branch);
	}

	@Override
	public PipelineNode connectTo(PipelineNode downstream) {
		children.add(downstream);
		if (downstream instanceof AbstractPipelineNode apn) {
			apn.parentIds.add(this.id);
		}
		return this;
	}

	@Override
	public PipelineNode connectTo(PipelineNode downstream, FilterBranch branch) {
		children.add(downstream);
		if (downstream instanceof AbstractPipelineNode apn) {
			apn.parentIds.add(this.id);
			apn.conditionalDependencies.put(this.id, branch);
		}
		return this;
	}

	@Override
	public List<PipelineNode> children() {
		return Collections.unmodifiableList(children);
	}

	@Override
	public int concurrency() {
		return concurrency;
	}

	@Override
	public boolean syncToLoom() {
		return syncToLoom;
	}

	public void setSyncToLoom(boolean syncToLoom) {
		this.syncToLoom = syncToLoom;
	}

	@Override
	public long timeoutMs() {
		return timeoutMs;
	}

	public void setTimeoutMs(long timeoutMs) {
		this.timeoutMs = timeoutMs;
	}

	@Override
	public String toString() {
		return id + " [" + mode + (blocking ? ", blocking" : "") + ", concurrency=" + concurrency
				+ (syncToLoom ? ", sync" : "") + (timeoutMs > 0 ? ", timeout=" + timeoutMs + "ms" : "") + "]";
	}
}
