package io.metaloom.loom.pipeline.graph;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Splits a graph into affinity segments.
 *
 * <p>A segment is a maximal set of nodes that share an affinity group <em>and</em>
 * are connected through edges within that group. Connectivity is the part that is
 * easy to get wrong: two nodes with the same group label but no path between them
 * are not one unit of work, and merging them would force unrelated work onto the
 * same worker for no benefit.</p>
 *
 * <h2>The source node is never in a segment</h2>
 *
 * <p>The engine synthesises the source result rather than dispatching it, so
 * including it would produce a segment containing a node no worker will ever run.</p>
 *
 * <h2>Segments must not create cycles</h2>
 *
 * <p>Grouping nodes together also merges their dependencies. If A → B → C with A and
 * C in one group and B in another, then the AC segment depends on B and B depends on
 * the AC segment — a deadlock in which neither can start. Such a group is split
 * rather than accepted, because a graph that cannot run is worse than one that is
 * merely chatty.</p>
 */
public class PipelineSegmenter {

	/**
	 * Compute the segments of a graph.
	 *
	 * @param graph the graph
	 * @return segments in dependency order, one per dispatchable unit
	 */
	public List<PipelineSegment> segment(PipelineGraph graph) {
		Map<String, String> segmentOf = new LinkedHashMap<>();
		List<List<String>> groups = new ArrayList<>();

		for (String nodeId : graph.getTopologicalOrder()) {
			if (segmentOf.containsKey(nodeId) || nodeId.equals(graph.getSourceNodeId())) {
				continue;
			}
			List<String> connected = collectConnected(graph, nodeId);
			groups.add(connected);
			for (String member : connected) {
				segmentOf.put(member, nodeId);
			}
		}

		// Splitting can only ever increase the segment count, and each split strictly
		// reduces the size of the offending group, so this terminates.
		List<List<String>> acyclic = splitUntilAcyclic(graph, groups);
		return toSegments(graph, acyclic);
	}

	/**
	 * Gather every node reachable from the seed through edges that stay inside its
	 * affinity group, in either direction.
	 */
	private List<String> collectConnected(PipelineGraph graph, String seed) {
		String affinity = graph.getNode(seed).getAffinity();
		Set<String> found = new LinkedHashSet<>();
		Deque<String> queue = new ArrayDeque<>();
		queue.add(seed);
		found.add(seed);

		while (!queue.isEmpty()) {
			String current = queue.poll();
			for (String neighbour : neighboursOf(graph, current)) {
				if (found.contains(neighbour) || neighbour.equals(graph.getSourceNodeId())) {
					continue;
				}
				if (!affinity.equals(graph.getNode(neighbour).getAffinity())) {
					continue;
				}
				found.add(neighbour);
				queue.add(neighbour);
			}
		}

		// Topological order within the segment, so a worker can run them as listed.
		List<String> ordered = new ArrayList<>();
		for (String nodeId : graph.getTopologicalOrder()) {
			if (found.contains(nodeId)) {
				ordered.add(nodeId);
			}
		}
		return ordered;
	}

	private List<String> neighboursOf(PipelineGraph graph, String nodeId) {
		List<String> neighbours = new ArrayList<>(graph.getNode(nodeId).getDependencies());
		neighbours.addAll(graph.getChildren(nodeId));
		return neighbours;
	}

	/**
	 * Break up any group whose merged dependencies would deadlock against another.
	 */
	private List<List<String>> splitUntilAcyclic(PipelineGraph graph, List<List<String>> groups) {
		List<List<String>> current = new ArrayList<>(groups);
		boolean changed = true;
		while (changed) {
			changed = false;
			Map<String, Integer> indexOf = indexNodes(current);
			for (int i = 0; i < current.size(); i++) {
				if (current.get(i).size() < 2) {
					continue;
				}
				if (!createsCycle(graph, current, indexOf, i)) {
					continue;
				}
				// Fall back to per-node dispatch for this group. Chatty but runnable,
				// and the alternative is a pipeline that never starts.
				List<String> offending = current.remove(i);
				for (String nodeId : offending) {
					current.add(List.of(nodeId));
				}
				changed = true;
				break;
			}
		}
		return current;
	}

	private Map<String, Integer> indexNodes(List<List<String>> groups) {
		Map<String, Integer> indexOf = new LinkedHashMap<>();
		for (int i = 0; i < groups.size(); i++) {
			for (String nodeId : groups.get(i)) {
				indexOf.put(nodeId, i);
			}
		}
		return indexOf;
	}

	/**
	 * @return true when reaching this group's own index again is possible by following
	 *         dependencies out of it and back
	 */
	private boolean createsCycle(PipelineGraph graph, List<List<String>> groups, Map<String, Integer> indexOf,
		int index) {
		Set<Integer> visited = new LinkedHashSet<>();
		Deque<Integer> queue = new ArrayDeque<>(outgoingGroupDeps(graph, groups.get(index), indexOf, index));
		while (!queue.isEmpty()) {
			int next = queue.poll();
			if (next == index) {
				return true;
			}
			if (!visited.add(next)) {
				continue;
			}
			queue.addAll(outgoingGroupDeps(graph, groups.get(next), indexOf, next));
		}
		return false;
	}

	private Set<Integer> outgoingGroupDeps(PipelineGraph graph, List<String> group, Map<String, Integer> indexOf,
		int ownIndex) {
		Set<Integer> deps = new LinkedHashSet<>();
		for (String nodeId : group) {
			for (String dep : graph.getNode(nodeId).getDependencies()) {
				Integer depIndex = indexOf.get(dep);
				if (depIndex != null && depIndex != ownIndex) {
					deps.add(depIndex);
				}
			}
		}
		return deps;
	}

	private List<PipelineSegment> toSegments(PipelineGraph graph, List<List<String>> groups) {
		// Emit in topological order of their first node, so callers see segments in an
		// order that respects dependencies.
		List<List<String>> ordered = new ArrayList<>(groups);
		Map<String, Integer> position = new LinkedHashMap<>();
		List<String> topo = graph.getTopologicalOrder();
		for (int i = 0; i < topo.size(); i++) {
			position.put(topo.get(i), i);
		}
		ordered.sort((a, b) -> Integer.compare(position.getOrDefault(a.get(0), 0), position.getOrDefault(b.get(0), 0)));

		List<PipelineSegment> segments = new ArrayList<>();
		for (List<String> group : ordered) {
			Set<String> members = new LinkedHashSet<>(group);
			Set<String> kinds = new LinkedHashSet<>();
			Set<String> externalDeps = new LinkedHashSet<>();
			for (String nodeId : group) {
				PipelineGraphNode node = graph.getNode(nodeId);
				kinds.add(node.getKind());
				for (String dep : node.getDependencies()) {
					if (!members.contains(dep)) {
						externalDeps.add(dep);
					}
				}
			}
			segments.add(new PipelineSegment(graph.getNode(group.get(0)).getAffinity(), group,
				new ArrayList<>(kinds), new ArrayList<>(externalDeps)));
		}
		return segments;
	}

}
