package io.metaloom.loom.test.integration.node;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.metaloom.cortex.api.node.spec.NodeSpecCatalog;
import io.metaloom.loom.nodes.spec.GeneratedNodeDescriptorProvider;
import io.metaloom.loom.nodes.spec.NodeDescriptor;
import io.metaloom.loom.nodes.spec.NodeDescriptors;

/**
 * The contracts Loom ships must be exactly what the annotated node classes say.
 *
 * <p>
 * A node's contract is declared once, on the node itself. Loom cannot read those declarations — the
 * node classes live in {@code cortex/} and {@code loom-shared} must not depend on it — so the harvest
 * runs at build time and its output is committed to
 * {@code loom-shared/node-model/src/main/resources/node-descriptors.json}.
 * </p>
 *
 * <p>
 * That committed file is the only thing standing between an annotation edit and a wrong palette, and
 * this test is what keeps it honest: it re-harvests every annotated node and fails if the result
 * differs from what is committed. A stale resource is a build failure rather than a silently outdated
 * editor.
 * </p>
 *
 * <p>
 * Regenerate after changing any annotation:
 * </p>
 *
 * <pre>
 * mvn -o -pl integration-test test -Dtest=NodeSpecGoldenTest -Dloom.regenerateNodeDescriptors=true
 * </pre>
 *
 * <p>
 * This replaces two things at once. It was the acceptance test for the sweep, comparing each harvest
 * against the hand-written {@code XDescriptorProvider} it replaced; those providers are gone, so the
 * committed resource is the fixture now. And it subsumes {@code NodePortConformanceTest}, which
 * existed only because a node's ports were declared twice — they no longer are.
 * </p>
 */
public class NodeSpecGoldenTest {

	/** Set {@code -Dloom.regenerateNodeDescriptors=true} to rewrite the resource instead of asserting. */
	private static final String REGENERATE = "loom.regenerateNodeDescriptors";

	/**
	 * Node ids that must be present, so an empty or truncated harvest cannot pass.
	 *
	 * <p>
	 * A spot check rather than the full list: the full list <em>is</em> the resource, and asserting it
	 * twice would only mean editing two places. These are the ones whose absence would mean the harvest
	 * silently stopped seeing a whole module — a source, a hash, a dynamic-port node, one with an XOR
	 * group, and one with an inherited-parameter override.
	 * </p>
	 */
	private static final List<String> MUST_BE_PRESENT = List.of(
		"filesystem-source", "gdrive-source", "onedrive-source", "md5", "whisper", "sentiment",
		"filter", "script", "llm", "vlm", "tts", "fingerprint-dedup");

	@Test
	public void testTheCommittedContractsMatchTheAnnotatedNodes() throws Exception {
		List<NodeDescriptor> harvested = NodeDescriptorResourceGenerator.harvestAll();
		String rendered = NodeDescriptorResourceGenerator.render(harvested);

		if (Boolean.getBoolean(REGENERATE)) {
			NodeDescriptorResourceGenerator.write(rendered);
			return;
		}

		String committed = NodeDescriptorResourceGenerator.readCommitted();
		assertTrue(!committed.isBlank(),
			"The generated contract resource is missing. Regenerate with -D" + REGENERATE + "=true");

		if (!rendered.equals(committed)) {
			// Report the node ids that differ rather than a 118 KB text diff.
			Map<String, NodeDescriptor> now = byNodeId(harvested);
			Map<String, NodeDescriptor> then = byNodeId(new GeneratedNodeDescriptorProvider().getDescriptors());
			List<String> problems = new ArrayList<>();
			for (String nodeId : union(now, then)) {
				NodeDescriptor a = now.get(nodeId);
				NodeDescriptor b = then.get(nodeId);
				if (a == null) {
					problems.add(nodeId + ": committed but no longer harvested (annotation removed?)");
				} else if (b == null) {
					problems.add(nodeId + ": harvested but not committed (new node?)");
				} else if (!NodeDescriptors.sameBody(a, b)) {
					problems.add(nodeId + ": contract changed\n    committed: " + NodeDescriptors.canonicalJson(b)
						+ "\n    harvested: " + NodeDescriptors.canonicalJson(a));
				}
			}
			if (problems.isEmpty()) {
				problems.add("only formatting or ordering differs");
			}
			throw new AssertionError("The committed node contracts are stale. Regenerate with -D" + REGENERATE
				+ "=true and review the diff:\n  " + String.join("\n  ", problems));
		}
	}

	@Test
	public void testEveryAnnotatedNodeIsServed() {
		Map<String, NodeDescriptor> served = byNodeId(new GeneratedNodeDescriptorProvider().getDescriptors());

		assertTrue(served.size() >= 30, "Only " + served.size() + " contracts are served; the harvest lost a module");
		for (String nodeId : MUST_BE_PRESENT) {
			assertNotNull(served.get(nodeId), "No contract is served for '" + nodeId + "'");
		}

		// Every annotated class on this class path must be in the resource, or a node someone just
		// wrote is runnable and unauthorable - the exact defect this machinery removes.
		for (String nodeId : NodeSpecCatalog.discover(getClass().getClassLoader()).keySet()) {
			assertNotNull(served.get(nodeId),
				"'" + nodeId + "' is annotated but missing from the generated resource. Regenerate with -D"
					+ REGENERATE + "=true");
		}
	}

	@Test
	public void testEveryServedContractIsStructurallySound() {
		for (NodeDescriptor descriptor : new GeneratedNodeDescriptorProvider().getDescriptors()) {
			String nodeId = descriptor.getNodeId();
			assertNotNull(nodeId, "a contract with no node id reached the resource");
			assertNotNull(descriptor.getName(), nodeId + " has no display name");
			assertNotNull(descriptor.getCategory(), nodeId + " has no category");
			assertEquals(nodeId, descriptor.getKind(), nodeId + ": the deprecated alias disagrees with nodeId");
		}
	}

	private static Map<String, NodeDescriptor> byNodeId(List<NodeDescriptor> descriptors) {
		Map<String, NodeDescriptor> map = new LinkedHashMap<>();
		descriptors.forEach(d -> map.put(d.getNodeId(), d));
		return map;
	}

	private static List<String> union(Map<String, NodeDescriptor> a, Map<String, NodeDescriptor> b) {
		List<String> ids = new ArrayList<>(a.keySet());
		b.keySet().forEach(id -> {
			if (!ids.contains(id)) {
				ids.add(id);
			}
		});
		ids.sort(String::compareTo);
		return ids;
	}
}
