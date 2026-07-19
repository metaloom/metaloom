package io.metaloom.loom.pipeline.graph;

import java.util.List;

/**
 * Something about a pipeline's affinity grouping that the author probably did not
 * intend.
 *
 * <p>These are <strong>warnings, not errors</strong>, and the distinction is
 * deliberate. A group that cannot be placed today may be placeable in a minute when
 * the GPU worker reconnects, so refusing the save would make editing a pipeline
 * depend on which machines happen to be up. The engine's own answer to an
 * unplaceable segment is to park it, not to fail the run — rejecting at save would
 * contradict that.</p>
 *
 * <p>What is worth saying loudly is that the grouping the author wrote is not the
 * grouping that will run.</p>
 */
public class AffinityWarning {

	/** What kind of problem this is. */
	public enum Kind {
		/**
		 * The group had to be broken up because merging it would have deadlocked.
		 * The pipeline runs, but chattier than written.
		 */
		GROUP_SPLIT,
		/**
		 * No single worker is currently permitted to run every kind in the segment, so
		 * it will park until one appears.
		 */
		UNPLACEABLE
	}

	private final Kind kind;
	private final String affinity;
	private final List<String> nodeIds;
	private final String message;

	public AffinityWarning(Kind kind, String affinity, List<String> nodeIds, String message) {
		this.kind = kind;
		this.affinity = affinity;
		this.nodeIds = List.copyOf(nodeIds);
		this.message = message;
	}

	public Kind getKind() {
		return kind;
	}

	public String getAffinity() {
		return affinity;
	}

	public List<String> getNodeIds() {
		return nodeIds;
	}

	public String getMessage() {
		return message;
	}

	@Override
	public String toString() {
		return kind + ": " + message;
	}

}
