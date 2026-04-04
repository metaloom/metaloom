package io.metaloom.cortex.pipeline.core.node;

import java.util.Collections;
import java.util.Map;
import java.util.Set;

import io.metaloom.cortex.pipeline.api.NodeMode;
import io.metaloom.cortex.pipeline.api.cache.NodeCacheProvider;
import io.metaloom.cortex.pipeline.api.node.PipelineNode;

/**
 * Base implementation of {@link PipelineNode} with sensible defaults.
 */
public abstract class AbstractPipelineNode implements PipelineNode {

	private final String id;
	private final String name;
	private final NodeMode mode;
	private final boolean blocking;
	private final Set<String> dependencies;
	private final int concurrency;
	private NodeCacheProvider cacheProvider;

	protected AbstractPipelineNode(String id, String name, NodeMode mode, boolean blocking,
			Set<String> dependencies, int concurrency) {
		this.id = id;
		this.name = name;
		this.mode = mode;
		this.blocking = blocking;
		this.dependencies = dependencies != null ? Collections.unmodifiableSet(dependencies) : Collections.emptySet();
		this.concurrency = concurrency;
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
		return dependencies;
	}

	@Override
	public int concurrency() {
		return concurrency;
	}

	@Override
	public NodeCacheProvider cacheProvider() {
		return cacheProvider;
	}

	public void setCacheProvider(NodeCacheProvider cacheProvider) {
		this.cacheProvider = cacheProvider;
	}

	@Override
	public String toString() {
		return id + " [" + mode + (blocking ? ", blocking" : "") + ", concurrency=" + concurrency + "]";
	}
}
