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
 * are related closely enough to be one unit of work. Two nodes qualify when an edge
 * inside the group joins them, or when they consume the same producer — see below.
 * A shared label alone is not enough: merging nodes that have nothing in common would
 * force unrelated work onto the same worker for no benefit.</p>
 *
 * <h2>Siblings of one producer are one unit of work</h2>
 *
 * <p>Requiring an edge <em>between</em> members was too strict once ports became typed.
 * An edge has to join two compatible ports, so independent analysers of the same media
 * cannot chain — {@code sha512} emits {@code hash/sha512} while every analyser consumes
 * {@code media/*}. Under an edges-only rule a group of them fused into N segments rather
 * than one and the saving disappeared silently, which is the worst way for an
 * optimisation to fail.</p>
 *
 * <p>So nodes that share an affinity group <em>and</em> a producer are one segment. What
 * they have in common is the input they all read, which is exactly the cost affinity
 * exists to pay once. The producer itself is not pulled in; only its consumers are.</p>
 *
 * <p>This is safe only because a member sees a fellow member's output when it declares
 * it as a dependency, never merely because they are in the same segment. Without that
 * rule, fusing {@code consistency} with {@code thumbnail} would hand {@code thumbnail}
 * an {@code is_complete} it has no edge to, and an affinity label — a scheduling hint —
 * would change what the pipeline computes.</p>
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
		// After the cycle pass, so it sees the groups that will actually be dispatched. Splitting
		// cannot create a cycle - it only ever removes merged dependencies - so the order is safe.
		return toSegments(graph, splitAmbiguousInputs(graph, acyclic));
	}

	/**
	 * Gather every node related to the seed that stays inside its affinity group: joined
	 * by an edge in either direction, or reading the same producer.
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
				if (!admissible(graph, affinity, found, neighbour)) {
					continue;
				}
				if (isRoutingEdge(graph, current, neighbour)) {
					// A routing edge ends the segment. The worker applies blocking-skip
					// rules locally but knows nothing about branch verdicts, so a filter
					// inside a segment would run the branch node regardless of the
					// verdict - silently changing what the pipeline does. Routing stays
					// with the engine, which is the only thing that implements it.
					continue;
				}
				found.add(neighbour);
				queue.add(neighbour);
			}
			for (String sibling : sharedProducerSiblings(graph, current)) {
				if (!admissible(graph, affinity, found, sibling)) {
					continue;
				}
				found.add(sibling);
				queue.add(sibling);
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

	/**
	 * @return true when the candidate may still join this segment: not already in it, not the
	 *         source (whose result the engine synthesises rather than dispatches), and carrying
	 *         the same affinity label
	 */
	private boolean admissible(PipelineGraph graph, String affinity, Set<String> found, String candidate) {
		return !found.contains(candidate)
			&& !candidate.equals(graph.getSourceNodeId())
			&& affinity.equals(graph.getNode(candidate).getAffinity());
	}

	/**
	 * Every other consumer of a producer this node consumes.
	 *
	 * <p>
	 * The producer is a bridge, not a member: it supplies the shared input but is not pulled into
	 * the segment, which is what keeps the source out of one even though everything reads it.
	 * </p>
	 *
	 * <p>
	 * Edges leaving a {@link io.metaloom.loom.nodes.spec.PortSpec#isSelective() selective} port are
	 * excluded on both sides. Two consumers of one filter can sit on <em>different</em> branches, so
	 * fusing them would put a node that must not run this item into the same unit of work as one
	 * that must — and the worker has no verdict to tell them apart. Consumers of a producer that is
	 * merely downstream of a filter are fine: they inherit the same routing and skip together.
	 * </p>
	 */
	private List<String> sharedProducerSiblings(PipelineGraph graph, String nodeId) {
		List<String> siblings = new ArrayList<>();
		for (String producer : plainProducersOf(graph, nodeId)) {
			for (String consumer : graph.getChildren(producer)) {
				if (!consumer.equals(nodeId) && plainProducersOf(graph, consumer).contains(producer)) {
					siblings.add(consumer);
				}
			}
		}
		return siblings;
	}

	/**
	 * @return the nodes feeding this one through an edge no branch verdict governs
	 */
	private Set<String> plainProducersOf(PipelineGraph graph, String nodeId) {
		Set<String> producers = new LinkedHashSet<>();
		for (InputBinding binding : graph.getNode(nodeId).getInputBindings()) {
			if (!binding.sourceSelective()) {
				producers.add(binding.sourceNodeId());
			}
		}
		return producers;
	}

	/**
	 * @return true when either node depends on the other through a branch the engine has to resolve —
	 *         an older {@code PASS}/{@code REJECT} edge, or an edge leaving a
	 *         {@link io.metaloom.loom.nodes.spec.PortSpec#isSelective() selective} port
	 */
	private boolean isRoutingEdge(PipelineGraph graph, String a, String b) {
		return graph.getNode(a).getConditionalDependencies().containsKey(b)
			|| graph.getNode(b).getConditionalDependencies().containsKey(a)
			|| isSelectiveEdge(graph, a, b)
			|| isSelectiveEdge(graph, b, a);
	}

	/**
	 * Whether {@code consumer} is fed by a selective port of {@code producer}.
	 *
	 * <p>
	 * This deliberately reads {@link InputBinding#sourceSelective()} rather than
	 * {@link InputBinding#routed()}. The declared flag marks the one edge where the branch is actually
	 * decided, which is the only edge a worker cannot reason about. {@code routed()} is inherited and
	 * would be true for the whole subgraph below a filter, so using it here would stop segment
	 * batching everywhere downstream of one — a pure performance loss for no correctness gain, since
	 * a branch that did not fire leaves the filter an unsettled external dependency of every segment
	 * below it anyway.
	 * </p>
	 */
	private boolean isSelectiveEdge(PipelineGraph graph, String consumer, String producer) {
		for (InputBinding binding : graph.getNode(consumer).getInputBindings()) {
			if (binding.sourceNodeId().equals(producer) && binding.sourceSelective()) {
				return true;
			}
		}
		return false;
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

	/**
	 * Break up any group that would need two different values for one input port id.
	 *
	 * <p>
	 * A segment is dispatched with a <em>single</em> map of inputs keyed by port id and shared by
	 * every member, because {@code SegmentNode} carries no bindings. Two members reading a port of
	 * the same name from different producers cannot both be served by it: the engine keeps the
	 * first and the second silently receives its neighbour's data. Falling back to per-node
	 * dispatch costs the round trips affinity was meant to save; feeding a node the wrong input
	 * would be a wrong answer, so this errs towards the round trips.
	 * </p>
	 *
	 * <p>
	 * Only edges from <em>outside</em> the group count. An edge between two members is satisfied on
	 * the worker and never reaches the shared map, which is why an ordinary chain — every node
	 * reading {@code media} from the one before it — is not a collision.
	 * </p>
	 */
	private List<List<String>> splitAmbiguousInputs(PipelineGraph graph, List<List<String>> groups) {
		List<List<String>> result = new ArrayList<>();
		for (List<String> group : groups) {
			if (group.size() < 2 || !hasAmbiguousExternalInput(graph, group)) {
				result.add(group);
				continue;
			}
			for (String nodeId : group) {
				result.add(List.of(nodeId));
			}
		}
		return result;
	}

	/**
	 * @return true when two members of the group read the same input port id from different
	 *         producers outside it
	 */
	private boolean hasAmbiguousExternalInput(PipelineGraph graph, List<String> group) {
		Set<String> members = new LinkedHashSet<>(group);
		Map<String, String> sourceOfPort = new LinkedHashMap<>();
		for (String nodeId : group) {
			for (InputBinding binding : graph.getNode(nodeId).getInputBindings()) {
				if (members.contains(binding.sourceNodeId())) {
					continue;
				}
				String origin = binding.sourceNodeId() + "." + binding.sourcePortId();
				String seen = sourceOfPort.putIfAbsent(binding.targetPortId(), origin);
				if (seen != null && !seen.equals(origin)) {
					return true;
				}
			}
		}
		return false;
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
