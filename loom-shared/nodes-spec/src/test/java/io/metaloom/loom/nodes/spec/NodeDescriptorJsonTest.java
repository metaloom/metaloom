package io.metaloom.loom.nodes.spec;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import org.junit.jupiter.api.Test;

class NodeDescriptorJsonTest {

	private final ObjectMapper mapper = new ObjectMapper()
		.configure(SerializationFeature.INDENT_OUTPUT, true);

	@Test
	void testSerializationRoundTrip() throws Exception {
		NodeDescriptor original = new NodeDescriptor()
			.setKind("test-node")
			.setName("Test Node")
			.setDescription("A test node")
			.setIcon("science")
			.setCategory(NodeCategory.ANALYSIS)
			.setDefaultConcurrency(4)
			.setDefaultMode(NodeMode.PARALLEL)
			.setDefaultBlocking(true);

		original.getInputs().add(new NodeInput("media", ContentTypes.MEDIA_ANY, true));
		original.getOutputs().add(new NodeOutput("hash", ContentTypes.DATA_HASH));
		original.getParameters().add(
			new NodeParameter().setKey("threshold")
				.setType(ParameterType.NUMBER)
				.setDefaultValue(0.5)
				.setLabel("Threshold")
				.setDescription("Detection threshold")
				.setMin(0.0)
				.setMax(1.0)
				.setStep(0.1));

		String json = mapper.writeValueAsString(original);
		NodeDescriptor deserialized = mapper.readValue(json, NodeDescriptor.class);

		assertEquals(original.getKind(), deserialized.getKind());
		assertEquals(original.getName(), deserialized.getName());
		assertEquals(original.getDescription(), deserialized.getDescription());
		assertEquals(original.getIcon(), deserialized.getIcon());
		assertEquals(original.getCategory(), deserialized.getCategory());
		assertEquals(original.getDefaultConcurrency(), deserialized.getDefaultConcurrency());
		assertEquals(original.getDefaultMode(), deserialized.getDefaultMode());
		assertEquals(original.isDefaultBlocking(), deserialized.isDefaultBlocking());
		assertEquals(1, deserialized.getInputs().size());
		assertEquals(1, deserialized.getOutputs().size());
		assertEquals(1, deserialized.getParameters().size());

		NodeParameter param = deserialized.getParameters().get(0);
		assertEquals("threshold", param.getKey());
		assertEquals(ParameterType.NUMBER, param.getType());
		assertEquals("Threshold", param.getLabel());
	}

	@Test
	void testMinimalDescriptorJson() throws Exception {
		NodeDescriptor desc = new NodeDescriptor()
			.setKind("minimal")
			.setName("Minimal")
			.setCategory(NodeCategory.OUTPUT);

		String json = mapper.writeValueAsString(desc);
		NodeDescriptor back = mapper.readValue(json, NodeDescriptor.class);

		assertEquals("minimal", back.getKind());
		assertEquals("Minimal", back.getName());
		assertEquals(NodeCategory.OUTPUT, back.getCategory());
		assertTrue(back.getInputs().isEmpty());
		assertTrue(back.getOutputs().isEmpty());
		assertTrue(back.getParameters().isEmpty());
	}

	@Test
	void testContentTypeJsonRoundTrip() throws Exception {
		ContentType ct = new ContentType("data/hash", "Hash", "data/string");
		String json = mapper.writeValueAsString(ct);
		ContentType back = mapper.readValue(json, ContentType.class);

		assertEquals("data/hash", back.getId());
		assertEquals("Hash", back.getLabel());
		assertEquals("data/string", back.getSuperType());
	}
}
