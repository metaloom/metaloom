package io.metaloom.loom.pipeline.graph;

import java.util.List;

/**
 * A maximal run of connected nodes that share an affinity group and can therefore
 * be executed together on one worker.
 *
 * <p>A segment is dispatched as a unit. Its intermediate results never leave the
 * worker, which is the whole point: under per-node dispatch a five-node video
 * pipeline re-reads and re-decodes the file five times and pays five round trips.
 * As one segment it decodes once.</p>
 *
 * <p>Nodes are listed in topological order, so a worker can execute them in the
 * order given without consulting the graph.</p>
 */
public class PipelineSegment {

	private final String affinity;
	private final List<String> nodeIds;
	private final List<String> nodeKinds;
	private final List<String> dependencies;

	PipelineSegment(String affinity, List<String> nodeIds, List<String> nodeKinds, List<String> dependencies) {
		this.affinity = affinity;
		this.nodeIds = List.copyOf(nodeIds);
		this.nodeKinds = List.copyOf(nodeKinds);
		this.dependencies = List.copyOf(dependencies);
	}

	public String getAffinity() {
		return affinity;
	}

	/** @return the nodes in this segment, in execution order */
	public List<String> getNodeIds() {
		return nodeIds;
	}

	/**
	 * @return the distinct node kinds in this segment; a worker must be permitted to
	 *         run <em>all</em> of them to be given the segment
	 */
	public List<String> getNodeKinds() {
		return nodeKinds;
	}

	/**
	 * @return node ids outside this segment that must settle before it can start
	 */
	public List<String> getDependencies() {
		return dependencies;
	}

	/** @return true when this segment holds a single node, i.e. per-node dispatch */
	public boolean isSingleNode() {
		return nodeIds.size() == 1;
	}

	public String getId() {
		return affinity + "[" + String.join(",", nodeIds) + "]";
	}

	@Override
	public String toString() {
		return "PipelineSegment" + getId();
	}

}
