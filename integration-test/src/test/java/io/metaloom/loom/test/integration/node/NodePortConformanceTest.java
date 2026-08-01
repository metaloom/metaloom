package io.metaloom.loom.test.integration.node;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.metaloom.cortex.api.node.InputPort;
import io.metaloom.cortex.api.node.OutputPort;
import io.metaloom.loom.nodes.spec.Cardinality;
import io.metaloom.loom.nodes.spec.ContentTypeRegistry;
import io.metaloom.loom.nodes.spec.NodeDescriptor;
import io.metaloom.loom.nodes.spec.NodeDescriptorProvider;
import io.metaloom.loom.nodes.spec.PortSpec;

/**
 * A node's declared ports and its runtime ports must be the same ports.
 *
 * <p>
 * Descriptors live in {@code loom-shared/node-model}; the port constants live on the nodes in
 * {@code cortex/}. Neither module can see the other, and this is the only one that sees both — which
 * is precisely why the two drifted for so long without anyone noticing. Before this test, the
 * {@code llm} descriptor advertised an output named {@code llm_result} while the node emitted
 * {@code llm_result_<promptId>}, which silently made two of {@code sentiment}'s default text sources
 * unmatchable.
 * </p>
 *
 * <p>
 * The check is deliberately blunt: for every node class that declares port constants, every port it
 * declares must exist on its descriptor with the same content type and cardinality, and vice versa.
 * A mismatch is a build failure rather than a runtime surprise.
 * </p>
 *
 * <p>
 * No database or worker is needed — this is reflection over two class paths.
 * </p>
 */
public class NodePortConformanceTest {

	/**
	 * Node class → descriptor kind. Listed explicitly rather than scanned, so adding a node forces a
	 * deliberate line here and a kind can never be silently unchecked.
	 */
	private static final Map<String, String> NODE_KINDS = new LinkedHashMap<>();

	static {
		NODE_KINDS.put("io.metaloom.cortex.node.hash.MD5Node", "md5");
		NODE_KINDS.put("io.metaloom.cortex.node.hash.SHA256Node", "sha256");
		NODE_KINDS.put("io.metaloom.cortex.node.hash.SHA512Node", "sha512");
		NODE_KINDS.put("io.metaloom.cortex.node.hash.ChunkHashNode", "chunk-hash");
		NODE_KINDS.put("io.metaloom.cortex.node.consistency.ConsistencyNode", "consistency");
		NODE_KINDS.put("io.metaloom.cortex.node.fp.FingerprintNode", "fingerprint");
		NODE_KINDS.put("io.metaloom.cortex.node.thumbnail.ThumbnailNode", "thumbnail");
		NODE_KINDS.put("io.metaloom.cortex.node.facedetect.FacedetectNode", "facedetect");
		NODE_KINDS.put("io.metaloom.cortex.node.facedescription.FacedescriptionNode", "facedescription");
		NODE_KINDS.put("io.metaloom.cortex.node.ocr.OCRNode", "ocr");
		NODE_KINDS.put("io.metaloom.cortex.node.tika.TikaNode", "tika");
		NODE_KINDS.put("io.metaloom.cortex.node.quality.QualityNode", "quality");
		NODE_KINDS.put("io.metaloom.cortex.node.whisper.WhisperNode", "whisper");
		NODE_KINDS.put("io.metaloom.cortex.node.sentiment.SentimentNode", "sentiment");
		NODE_KINDS.put("io.metaloom.cortex.node.depthmap.DepthmapNode", "depthmap");
		NODE_KINDS.put("io.metaloom.cortex.node.scenelayout.SceneLayoutNode", "scene-layout");
		NODE_KINDS.put("io.metaloom.cortex.node.color.DominantColorNode", "dominant-color");
		NODE_KINDS.put("io.metaloom.cortex.node.scene.SceneDetectionNode", "scene-detection");
		NODE_KINDS.put("io.metaloom.cortex.node.captioning.CaptioningNode", "captioning");
		NODE_KINDS.put("io.metaloom.cortex.node.tts.TtsNode", "tts");
		NODE_KINDS.put("io.metaloom.cortex.node.imagegen.ImageGenNode", "imagegen");
		NODE_KINDS.put("io.metaloom.cortex.node.watermark.WatermarkNode", "watermark");
		NODE_KINDS.put("io.metaloom.cortex.node.sink.s3.S3SinkNode", "s3-sink");
	}

	/**
	 * Kinds whose port set is per-instance, so a static comparison is meaningless: their descriptors
	 * declare no static outputs and a {@code NodePortResolver} derives them from the node's options.
	 */
	private static final Set<String> DYNAMIC_KINDS = Set.of("script", "llm", "vlm");

	private static Map<String, NodeDescriptor> descriptors() {
		Map<String, NodeDescriptor> byKind = new LinkedHashMap<>();
		ServiceLoader.load(NodeDescriptorProvider.class)
			.forEach(provider -> provider.getDescriptors().forEach(d -> byKind.put(d.getKind(), d)));
		return byKind;
	}

	@Test
	public void testEveryNodesPortsMatchItsDescriptor() {
		Map<String, NodeDescriptor> descriptors = descriptors();
		List<String> problems = new ArrayList<>();
		int compared = 0;
		int portsSeen = 0;

		for (Map.Entry<String, String> entry : NODE_KINDS.entrySet()) {
			Class<?> nodeClass;
			try {
				nodeClass = Class.forName(entry.getKey());
			} catch (ClassNotFoundException e) {
				// The module is not on this test's class path. Skip rather than fail: the point is to
				// catch drift, not to police the dependency list.
				continue;
			}
			String kind = entry.getValue();
			NodeDescriptor descriptor = descriptors.get(kind);
			if (descriptor == null) {
				problems.add(nodeClass.getSimpleName() + " declares ports but no descriptor advertises kind '" + kind + "'");
				continue;
			}

			compared++;
			Map<String, PortView> in = inputPorts(nodeClass);
			Map<String, PortView> out = outputPorts(nodeClass);
			portsSeen += in.size() + out.size();
			compare(problems, nodeClass, kind, "input", in, descriptor.getInputPorts());
			if (!DYNAMIC_KINDS.contains(kind)) {
				compare(problems, nodeClass, kind, "output", out, descriptor.getOutputPorts());
			}
		}

		assertEquals(List.of(), problems,
			"A node's runtime ports and its descriptor's ports have drifted apart:\n  " + String.join("\n  ", problems));

		// A conformance test that silently compares nothing is worse than no test: it reads as green.
		// The per-node loop skips a class that is not on this module's path, so assert that most of
		// the mapped nodes were genuinely reached and that ports were actually seen.
		assertTrue(compared >= NODE_KINDS.size() - 3,
			"Only " + compared + " of " + NODE_KINDS.size() + " mapped nodes were on the class path -"
				+ " this test would pass without checking anything. Add the missing node module as a"
				+ " test dependency of integration-test.");
		assertTrue(portsSeen > 40,
			"Only " + portsSeen + " ports were compared; the reflection over port constants has stopped working");
	}

	/**
	 * Every content type a node names must be in the vocabulary. A typo would otherwise make an edge
	 * unconnectable for a reason no message explains.
	 */
	@Test
	public void testEveryRuntimePortUsesAKnownContentType() {
		List<String> problems = new ArrayList<>();
		for (String className : NODE_KINDS.keySet()) {
			Class<?> nodeClass;
			try {
				nodeClass = Class.forName(className);
			} catch (ClassNotFoundException e) {
				continue;
			}
			inputPorts(nodeClass).forEach((id, port) -> {
				if (!ContentTypeRegistry.isKnown(port.contentType())) {
					problems.add(nodeClass.getSimpleName() + " input '" + id + "' uses unknown content type '"
						+ port.contentType() + "'");
				}
			});
			outputPorts(nodeClass).forEach((id, port) -> {
				if (!ContentTypeRegistry.isKnown(port.contentType())) {
					problems.add(nodeClass.getSimpleName() + " output '" + id + "' uses unknown content type '"
						+ port.contentType() + "'");
				}
			});
		}
		assertEquals(List.of(), problems, String.join("\n  ", problems));
	}

	@Test
	public void testTheMappingItselfIsSane() {
		Map<String, NodeDescriptor> descriptors = descriptors();
		assertFalse(descriptors.isEmpty(), "No descriptors were discovered - the ServiceLoader wiring is broken");
		for (String kind : NODE_KINDS.values()) {
			assertNotNull(descriptors.get(kind), "This test maps a node onto kind '" + kind + "', which no provider declares");
		}
	}

	private static void compare(List<String> problems, Class<?> nodeClass, String kind, String side,
		Map<String, PortView> runtime, List<PortSpec> declared) {

		Map<String, PortSpec> byId = new LinkedHashMap<>();
		declared.forEach(port -> byId.put(port.getId(), port));

		for (Map.Entry<String, PortView> entry : runtime.entrySet()) {
			PortSpec spec = byId.get(entry.getKey());
			if (spec == null) {
				problems.add(nodeClass.getSimpleName() + " (" + kind + ") emits " + side + " port '" + entry.getKey()
					+ "' that its descriptor does not declare");
				continue;
			}
			PortView port = entry.getValue();
			if (!spec.getContentType().equals(port.contentType())) {
				problems.add(nodeClass.getSimpleName() + " (" + kind + ") " + side + " '" + entry.getKey()
					+ "' is " + port.contentType() + " at runtime but " + spec.getContentType() + " in its descriptor");
			}
			if (spec.getCardinality() != port.cardinality()) {
				problems.add(nodeClass.getSimpleName() + " (" + kind + ") " + side + " '" + entry.getKey()
					+ "' is " + port.cardinality() + " at runtime but " + spec.getCardinality() + " in its descriptor");
			}
		}

		Set<String> runtimeIds = new LinkedHashSet<>(runtime.keySet());
		for (PortSpec spec : declared) {
			if (!runtimeIds.contains(spec.getId())) {
				problems.add(nodeClass.getSimpleName() + " (" + kind + ") declares " + side + " port '" + spec.getId()
					+ "' in its descriptor but names no such port at runtime");
			}
		}
	}

	/** The shape both port record types share, so one comparison serves both sides. */
	private record PortView(String contentType, Cardinality cardinality) {
	}

	private static Map<String, PortView> inputPorts(Class<?> nodeClass) {
		Map<String, PortView> ports = new LinkedHashMap<>();
		for (Field field : constants(nodeClass)) {
			try {
				Object value = field.get(null);
				if (value instanceof InputPort<?> port) {
					ports.put(port.id(), new PortView(port.contentType(), port.cardinality()));
				}
			} catch (IllegalAccessException e) {
				// A non-readable constant cannot be part of the contract; ignore it.
			}
		}
		return ports;
	}

	private static Map<String, PortView> outputPorts(Class<?> nodeClass) {
		Map<String, PortView> ports = new LinkedHashMap<>();
		for (Field field : constants(nodeClass)) {
			try {
				Object value = field.get(null);
				if (value instanceof OutputPort<?> port) {
					ports.put(port.id(), new PortView(port.contentType(), port.cardinality()));
				}
			} catch (IllegalAccessException e) {
				// See above.
			}
		}
		return ports;
	}

	private static List<Field> constants(Class<?> nodeClass) {
		List<Field> fields = new ArrayList<>();
		for (Field field : nodeClass.getDeclaredFields()) {
			if (Modifier.isStatic(field.getModifiers()) && Modifier.isPublic(field.getModifiers())) {
				fields.add(field);
			}
		}
		return fields;
	}
}
