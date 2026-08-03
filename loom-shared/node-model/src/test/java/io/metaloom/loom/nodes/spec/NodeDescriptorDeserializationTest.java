package io.metaloom.loom.nodes.spec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.ServiceLoader;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * A {@link NodeDescriptor} must survive a round trip through JSON.
 *
 * <p>
 * These types spent their whole life being <em>serialized</em> — written to the REST response and to
 * the checked-in snapshot, never read back. Node self-registration reverses the direction: a worker
 * announces a descriptor and Loom has to parse it. Anything the writer emits that the reader cannot
 * accept is a defect that only shows up on the day a custom node first connects, which is the worst
 * possible day to find it.
 * </p>
 */
public class NodeDescriptorDeserializationTest {

	private final ObjectMapper mapper = new ObjectMapper();

	@Test
	public void shouldRoundTripEveryBuiltInDescriptor() throws Exception {
		int checked = 0;
		for (NodeDescriptorProvider provider : ServiceLoader.load(NodeDescriptorProvider.class)) {
			for (NodeDescriptor descriptor : provider.getDescriptors()) {
				String json = mapper.writeValueAsString(descriptor);
				NodeDescriptor parsed = mapper.readValue(json, NodeDescriptor.class);

				assertEquals(descriptor.getNodeId(), parsed.getNodeId(), "nodeId of " + descriptor.getNodeId());
				assertEquals(descriptor.getInputPorts().size(), parsed.getInputPorts().size(),
					"input port count of " + descriptor.getNodeId());
				assertEquals(descriptor.getOutputPorts().size(), parsed.getOutputPorts().size(),
					"output port count of " + descriptor.getNodeId());
				assertEquals(descriptor.getParameters().size(), parsed.getParameters().size(),
					"parameter count of " + descriptor.getNodeId());
				// The body hash is the real assertion: it compares every contract field at once, so a
				// field that silently fails to parse cannot hide behind the four counts above.
				assertEquals(NodeDescriptors.bodyHash(descriptor), NodeDescriptors.bodyHash(parsed),
					"body hash of " + descriptor.getNodeId());
				checked++;
			}
		}
		assertTrue(checked > 20, "Expected the built-in descriptors to be on the test classpath, found " + checked);
	}

	@Test
	public void shouldAcceptLegacyKindOnlyPayload() throws Exception {
		// What every already-deployed consumer and the checked-in snapshot emit today.
		String legacy = """
			{"kind":"acme-nsfw","name":"NSFW","category":"ANALYSIS",
			 "inputPorts":[{"id":"media","contentType":"media/image","cardinality":"ONE","required":true}],
			 "outputPorts":[],"inputGroups":[],"outputGroups":[],"parameters":[],"events":[]}""";

		NodeDescriptor parsed = mapper.readValue(legacy, NodeDescriptor.class);

		assertEquals("acme-nsfw", parsed.getNodeId(), "the legacy 'kind' must land on nodeId");
	}

	@Test
	public void shouldEmitBothNamesForOneRelease() throws Exception {
		String json = mapper.writeValueAsString(new NodeDescriptor().setNodeId("acme-nsfw").setName("NSFW"));

		assertTrue(json.contains("\"nodeId\":\"acme-nsfw\""), "expected nodeId in " + json);
		assertTrue(json.contains("\"kind\":\"acme-nsfw\""), "expected the deprecated alias in " + json);
	}

	@Test
	public void shouldNotEmitTheDerivedManyFlag() throws Exception {
		// PortSpec.isMany() has no setter. Emitting it would make the very first readValue fail.
		String json = mapper.writeValueAsString(PortSpec.many("faces", ContentTypeRegistry.DETECTION_FACE));

		assertFalse(json.contains("\"many\""), "the derived flag must not reach the wire: " + json);

		PortSpec parsed = mapper.readValue(json, PortSpec.class);
		assertTrue(parsed.isMany(), "cardinality still derives it after the round trip");
	}

	@Test
	public void shouldOmitVersionWhenUnset() throws Exception {
		String json = mapper.writeValueAsString(new NodeDescriptor().setNodeId("x").setName("X"));

		assertFalse(json.contains("\"version\""), "an unversioned contract must not emit a null version: " + json);
	}

	@Test
	public void shouldRoundTripVersion() throws Exception {
		NodeDescriptor descriptor = new NodeDescriptor().setNodeId("x").setName("X").setVersion("1.0.0-SNAPSHOT");

		NodeDescriptor parsed = mapper.readValue(mapper.writeValueAsString(descriptor), NodeDescriptor.class);

		assertEquals("1.0.0-SNAPSHOT", parsed.getVersion());
	}

	@Test
	public void shouldExcludeVersionAndAliasFromTheBodyHash() {
		NodeDescriptor a = new NodeDescriptor().setNodeId("x").setName("X").setVersion("1.0.0");
		NodeDescriptor b = new NodeDescriptor().setNodeId("x").setName("X").setVersion("2.4.0-SNAPSHOT");

		// Two workers on different versions of the same unchanged contract must not read as a conflict.
		assertEquals(NodeDescriptors.bodyHash(a), NodeDescriptors.bodyHash(b));
		assertTrue(NodeDescriptors.sameBody(a, b));
	}

	@Test
	public void shouldDetectAChangedBody() {
		NodeDescriptor a = new NodeDescriptor().setNodeId("x").setName("X")
			.setOutputPorts(List.of(PortSpec.one("result", ContentTypeRegistry.STRUCT_JSON)));
		NodeDescriptor b = new NodeDescriptor().setNodeId("x").setName("X")
			.setOutputPorts(List.of(PortSpec.one("result", ContentTypeRegistry.STRUCT_EMBEDDING)));

		assertFalse(NodeDescriptors.sameBody(a, b), "a changed content type is a changed contract");
	}

	@Test
	public void shouldProduceAStableCanonicalFormRegardlessOfPropertyOrder() throws Exception {
		NodeDescriptor descriptor = new NodeDescriptor().setNodeId("x").setName("X")
			.setInputPorts(List.of(PortSpec.one("a", ContentTypeRegistry.MEDIA_ANY)));

		// Reparsing rebuilds the object graph in Jackson's own order; the canonical form must not care.
		NodeDescriptor reparsed = mapper.readValue(mapper.writeValueAsString(descriptor), NodeDescriptor.class);

		assertEquals(NodeDescriptors.canonicalJson(descriptor), NodeDescriptors.canonicalJson(reparsed));
		assertFalse(NodeDescriptors.canonicalJson(descriptor).contains("\"version\""));
		assertFalse(NodeDescriptors.canonicalJson(descriptor).contains("\"kind\""));
	}

	@Test
	public void shouldCopyWithoutSharingState() {
		NodeDescriptor original = new NodeDescriptor().setNodeId("x").setName("X")
			.setInputPorts(new java.util.ArrayList<>(List.of(PortSpec.one("a", ContentTypeRegistry.MEDIA_ANY))));

		NodeDescriptor copy = NodeDescriptors.copy(original);
		copy.getInputPorts().get(0).setId("mutated");

		assertNotNull(copy);
		assertEquals("a", original.getInputPorts().get(0).getId(), "the copy must not alias the original's ports");
	}

	@Test
	public void shouldReturnNullCopyForNull() {
		assertNull(NodeDescriptors.copy(null));
	}
}
