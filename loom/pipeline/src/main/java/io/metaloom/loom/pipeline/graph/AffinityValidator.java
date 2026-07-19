package io.metaloom.loom.pipeline.graph;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Checks that a pipeline's affinity grouping will actually run as written.
 *
 * <p>Two things can go wrong and both are silent without this. A group may have been
 * split because merging it would deadlock, in which case the pipeline runs but with
 * every round trip the author was trying to avoid. Or a group may span kinds that no
 * single worker is allowed to run, in which case its segment parks indefinitely and
 * the run appears to hang for no visible reason.</p>
 *
 * <p>The fleet check is supplied as a predicate rather than read from a registry, so
 * this stays pure graph logic and testable without a running Cortex.</p>
 */
public class AffinityValidator {

	private final PipelineSegmenter segmenter = new PipelineSegmenter();

	/**
	 * @param graph            the parsed graph
	 * @param anyWorkerRunsAll answers "is some single worker permitted to run all of
	 *                         these kinds?"; pass {@code kinds -> true} to skip the
	 *                         fleet check and report only structural problems
	 * @return warnings, empty when the grouping will run as written
	 */
	public List<AffinityWarning> validate(PipelineGraph graph, Predicate<List<String>> anyWorkerRunsAll) {
		List<PipelineSegment> segments = segmenter.segment(graph);
		List<AffinityWarning> warnings = new ArrayList<>();

		warnings.addAll(findSplitGroups(graph, segments));

		for (PipelineSegment segment : segments) {
			if (anyWorkerRunsAll != null && !anyWorkerRunsAll.test(segment.getNodeKinds())) {
				warnings.add(new AffinityWarning(AffinityWarning.Kind.UNPLACEABLE, segment.getAffinity(),
					segment.getNodeIds(),
					"No connected worker is permitted to run all of " + segment.getNodeKinds()
						+ ", required by affinity group '" + segment.getAffinity() + "' (nodes "
						+ segment.getNodeIds() + "). This segment will wait until such a worker connects."));
			}
		}
		return warnings;
	}

	/**
	 * Find groups whose nodes ended up spread across more than one segment.
	 *
	 * <p>Reported per group rather than per segment: the author wrote one group, and
	 * telling them it became three is the useful message.</p>
	 */
	private List<AffinityWarning> findSplitGroups(PipelineGraph graph, List<PipelineSegment> segments) {
		// How many nodes each group has in the graph, ignoring the source, which is
		// never dispatched and so never part of a segment.
		Map<String, Set<String>> declared = new LinkedHashMap<>();
		for (String nodeId : graph.getTopologicalOrder()) {
			if (nodeId.equals(graph.getSourceNodeId())) {
				continue;
			}
			declared.computeIfAbsent(graph.getNode(nodeId).getAffinity(), k -> new LinkedHashSet<>()).add(nodeId);
		}

		Map<String, Integer> segmentsPerGroup = new LinkedHashMap<>();
		for (PipelineSegment segment : segments) {
			segmentsPerGroup.merge(segment.getAffinity(), 1, Integer::sum);
		}

		List<AffinityWarning> warnings = new ArrayList<>();
		declared.forEach((affinity, nodeIds) -> {
			int count = segmentsPerGroup.getOrDefault(affinity, 0);
			if (nodeIds.size() > 1 && count > 1) {
				warnings.add(new AffinityWarning(AffinityWarning.Kind.GROUP_SPLIT, affinity, new ArrayList<>(nodeIds),
					"Affinity group '" + affinity + "' covers " + nodeIds
						+ " but runs as " + count + " separate segments. Nodes in a group are only kept "
						+ "together when they are connected and grouping them does not create a dependency "
						+ "cycle; intermediate results will round-trip through Loom instead of staying on "
						+ "the worker."));
			}
		});
		return warnings;
	}

}
