package io.metaloom.loom.doc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.nodes.spec.ContentTypeRegistry;
import io.metaloom.loom.nodes.spec.NodeDescriptorProvider;
import io.metaloom.loom.nodes.spec.NodeDescriptorRegistry;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Guards the website node-descriptor snapshot: the encoded document must parse, use the port-model field names the in-browser editor
 * reads ({@code inputPorts}/{@code outputPorts}/{@code cardinality}), and cover every kind the SPI provides — so a newly added node kind
 * that the snapshot misses fails the build rather than silently drifting out of the palette.
 */
public class NodeDescriptorGeneratorTest {

	private static JsonObject encodeSnapshot() {
		NodeDescriptorRegistry registry = new NodeDescriptorRegistry();
		ServiceLoader.load(NodeDescriptorProvider.class)
			.forEach(provider -> provider.getDescriptors().forEach(registry::register));
		return new JsonObject()
			.put("nodeDescriptors", new JsonArray(Json.encode(registry.getAll())))
			.put("contentTypes", new JsonArray(Json.encode(ContentTypeRegistry.all())));
	}

	@Test
	public void shouldCoverEveryProvidedKind() {
		List<String> providedKinds = new ArrayList<>();
		ServiceLoader.load(NodeDescriptorProvider.class)
			.forEach(provider -> provider.getDescriptors().forEach(d -> providedKinds.add(d.getKind())));
		assertFalse(providedKinds.isEmpty(), "No node descriptor providers were on the classpath");

		JsonArray descriptors = encodeSnapshot().getJsonArray("nodeDescriptors");
		List<String> snapshotKinds = new ArrayList<>();
		for (int i = 0; i < descriptors.size(); i++) {
			snapshotKinds.add(descriptors.getJsonObject(i).getString("kind"));
		}
		assertTrue(snapshotKinds.containsAll(providedKinds),
			"Snapshot is missing kinds: " + providedKinds.stream().filter(k -> !snapshotKinds.contains(k)).toList());
		assertEquals(providedKinds.size(), snapshotKinds.size(), "Snapshot kind count differs from the provided kinds");
	}

	@Test
	public void shouldUsePortModelSchema() {
		JsonObject snapshot = encodeSnapshot();

		JsonArray contentTypes = snapshot.getJsonArray("contentTypes");
		assertFalse(contentTypes.isEmpty(), "No content types were encoded");
		JsonObject firstContentType = contentTypes.getJsonObject(0);
		assertTrue(firstContentType.containsKey("id"), "content type must carry an id");
		assertTrue(firstContentType.containsKey("family"), "content type must carry a family");

		JsonArray descriptors = snapshot.getJsonArray("nodeDescriptors");
		// A source declares an output port; assert the port-model field names the editor reads.
		JsonObject source = descriptors.stream()
			.map(JsonObject.class::cast)
			.filter(d -> "filesystem-source".equals(d.getString("kind")))
			.findFirst()
			.orElseThrow(() -> new AssertionError("filesystem-source descriptor missing from snapshot"));
		JsonArray outputPorts = source.getJsonArray("outputPorts");
		assertTrue(outputPorts != null && !outputPorts.isEmpty(), "filesystem-source must declare output ports");
		JsonObject mediaPort = outputPorts.getJsonObject(0);
		assertEquals("media", mediaPort.getString("id"), "source output port must be named 'media'");
		assertTrue(mediaPort.containsKey("contentType"), "port must carry a contentType");
		assertTrue(mediaPort.containsKey("cardinality"), "port must carry a cardinality");
	}
}
