package io.metaloom.loom.nodes.spec;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Test that writes the full node descriptors and content types JSON
 * to the spec/ folder for documentation and UI development reference.
 */
class WriteNodeDescriptorsJsonTest {

	private final ObjectMapper mapper = new ObjectMapper()
		.configure(SerializationFeature.INDENT_OUTPUT, true);

	@Test
	void writeNodeDescriptorsJson() throws Exception {
		NodeDescriptorRegistry registry = new NodeDescriptorRegistry();
		CortexNodeDescriptors.registerAll(registry);

		ObjectNode root = mapper.createObjectNode();
		ArrayNode descriptorsNode = mapper.valueToTree(registry.getAll());
		root.set("nodeDescriptors", descriptorsNode);

		ArrayNode contentTypesNode = mapper.valueToTree(ContentTypes.all());
		root.set("contentTypes", contentTypesNode);

		// Write relative to project root (maven runs from module dir, go up to metaloom root)
		Path specDir = Path.of("").toAbsolutePath();
		// Navigate up from loom-shared/nodes-spec to the metaloom root
		while (!Files.exists(specDir.resolve("spec")) && specDir.getParent() != null) {
			specDir = specDir.getParent();
		}
		Path outFile = specDir.resolve("spec").resolve("node-descriptors.json");
		Files.createDirectories(outFile.getParent());
		Files.writeString(outFile, mapper.writeValueAsString(root));

		System.out.println("Wrote node descriptors JSON to " + outFile.toAbsolutePath());
		assertTrue(Files.exists(outFile));
		assertTrue(Files.size(outFile) > 0);
	}
}
