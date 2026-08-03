package io.metaloom.loom.test.integration.node;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import io.metaloom.cortex.api.node.spec.NodeSpecCatalog;
import io.metaloom.cortex.api.node.spec.NodeSpecHarvester;
import io.metaloom.loom.nodes.spec.NodeDescriptor;

/**
 * Harvests every annotated node class into the resource Loom ships as its built-in contract set.
 *
 * <h2>Why this lives in a test module</h2>
 *
 * <p>
 * It is the one place that can see both sides. The node classes are in {@code cortex/}; the resource
 * belongs to {@code loom-shared/node-model}, which must not depend on {@code cortex/} — that
 * dependency runs the other way, and inverting it would pull every node's native libraries into the
 * server. Running the harvest here and committing its output keeps the runtime dependency graph
 * unchanged while removing the hand-written second copy.
 * </p>
 *
 * <p>
 * Regenerate with:
 * </p>
 *
 * <pre>
 * mvn -o -pl integration-test test -Dtest=NodeSpecGoldenTest -Dloom.regenerateNodeDescriptors=true
 * </pre>
 *
 * <p>
 * Without that flag {@code NodeSpecGoldenTest} only <em>compares</em>, so a stale resource fails the
 * build rather than being silently regenerated underneath a reviewer.
 * </p>
 */
public final class NodeDescriptorResourceGenerator {

	/** Where the committed resource lives, relative to the repository root. */
	static final String RESOURCE_PATH = "loom-shared/node-model/src/main/resources/node-descriptors.json";

	private static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

	private NodeDescriptorResourceGenerator() {
	}

	/**
	 * Every annotated node contract on this class path, in node-id order.
	 *
	 * <p>
	 * Sorted rather than left in discovery order: the file is committed and diffed by humans, and a
	 * class path reshuffle must not rewrite it.
	 * </p>
	 */
	static List<NodeDescriptor> harvestAll() {
		Map<String, Class<?>> discovered = NodeSpecCatalog.discover(NodeDescriptorResourceGenerator.class.getClassLoader());
		List<NodeDescriptor> descriptors = new ArrayList<>();
		for (Map.Entry<String, Class<?>> entry : discovered.entrySet()) {
			NodeDescriptor descriptor = NodeSpecHarvester.harvest(entry.getValue());
			// The version is announcement metadata, not contract. A built-in descriptor has none, and
			// baking the build's own jar version in would rewrite the file on every release.
			descriptor.setVersion(null);
			descriptors.add(descriptor);
		}
		descriptors.sort(Comparator.comparing(NodeDescriptor::getNodeId));
		return descriptors;
	}

	static String render(List<NodeDescriptor> descriptors) throws IOException {
		return MAPPER.writeValueAsString(descriptors) + "\n";
	}

	/** The committed resource, read from the source tree rather than the class path. */
	static String readCommitted() throws IOException {
		File file = resourceFile();
		return file.isFile() ? Files.readString(file.toPath(), StandardCharsets.UTF_8) : "";
	}

	static void write(String content) throws IOException {
		File file = resourceFile();
		file.getParentFile().mkdirs();
		Files.writeString(file.toPath(), content, StandardCharsets.UTF_8);
	}

	/**
	 * Resolve the resource against the repository root.
	 *
	 * <p>
	 * Surefire runs with the module directory as the working directory, so walk up until the path
	 * resolves rather than assuming a depth.
	 * </p>
	 */
	private static File resourceFile() {
		File dir = new File("").getAbsoluteFile();
		for (int i = 0; i < 6 && dir != null; i++) {
			File candidate = new File(dir, RESOURCE_PATH);
			if (candidate.isFile() || new File(dir, "loom-shared").isDirectory()) {
				return candidate;
			}
			dir = dir.getParentFile();
		}
		return new File(RESOURCE_PATH);
	}
}
