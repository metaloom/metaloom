package io.metaloom.loom.mcp.tool.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.metaloom.loom.nodes.spec.Cardinality;
import io.metaloom.loom.nodes.spec.NodeCategory;
import io.metaloom.loom.nodes.spec.NodeDescriptor;
import io.metaloom.loom.nodes.spec.NodeDescriptorRegistry;
import io.metaloom.loom.nodes.spec.NodeParameter;
import io.metaloom.loom.nodes.spec.ParameterType;
import io.metaloom.loom.nodes.spec.PortGroup;
import io.metaloom.loom.nodes.spec.PortSpec;
import io.metaloom.loom.rest.model.nodes.NodeAvailability;
import io.metaloom.loom.rest.service.impl.NodeAvailabilityService;
import io.vertx.core.json.JsonObject;

/**
 * The two discovery tools an agent uses before it writes a definition.
 *
 * <p>
 * Both are about keeping a model out of the state where it invents a port id: {@code list_node_descriptors} must stay small enough to actually read,
 * and {@code get_node_descriptor} must report the ports a node <em>really</em> has, which for a dynamic kind depends on its options.
 * </p>
 */
public class NodeDescriptorToolTest {

	private NodeDescriptorRegistry registry;

	private NodeAvailabilityService availability;

	@BeforeEach
	public void setup() {
		registry = new NodeDescriptorRegistry();
		registry.register(new NodeDescriptor()
			.setNodeId("filesystem-source")
			.setName("File Source")
			.setDescription("Walks a directory and emits the media it finds.")
			.setCategory(NodeCategory.SOURCE)
			.setOutputPorts(List.of(PortSpec.one("media", "media/*"))));
		registry.register(new NodeDescriptor()
			.setNodeId("facedetect")
			.setName("Face Detection")
			.setDescription("Detects faces in an image or a video.")
			.setCategory(NodeCategory.ANALYSIS)
			.setInputPorts(List.of(
				new PortSpec("image", "media/image", Cardinality.ONE).setGroup("media_alt"),
				new PortSpec("video", "media/video", Cardinality.ONE).setGroup("media_alt")))
			.setInputGroups(List.of(PortGroup.xor("media_alt", "Image or video")))
			.setOutputPorts(List.of(new PortSpec("detections", "detection/face", Cardinality.MANY)))
			.setParameters(List.of(new NodeParameter()
				.setKey("minScore")
				.setType(ParameterType.NUMBER)
				.setLabel("Minimum score")
				.setDefaultValue(0.5)
				.setMin(0)
				.setMax(1))));
		registry.register(new NodeDescriptor()
			.setNodeId("tag")
			.setName("Tag")
			.setDescription("Writes tags onto the asset.")
			.setCategory(NodeCategory.OUTPUT)
			.setInputPorts(List.of(PortSpec.one("media", "media/*"))));

		availability = mock(NodeAvailabilityService.class);
		when(availability.availability(anyBoolean())).thenReturn(Map.of(
			"facedetect", new NodeAvailability().setAvailable(false)));
	}

	private String list(JsonObject args) {
		return text(new ListNodeDescriptorsTool(registry).execute(args).result());
	}

	private String get(JsonObject args) {
		return text(new GetNodeDescriptorTool(registry, availability).execute(args).result());
	}

	private static String text(JsonObject result) {
		return result.getJsonArray("content").getJsonObject(0).getString("text");
	}

	@Test
	public void testListsEveryKindOnALine() {
		String text = list(new JsonObject());
		assertTrue(text.startsWith("Found 3 node kinds."));
		assertTrue(text.contains("- facedetect [ANALYSIS] Face Detection — Detects faces in an image or a video."));
		assertTrue(text.contains("- filesystem-source [SOURCE]"));
	}

	/**
	 * The listing is a projection, not a dump: ~115 KB of descriptor JSON would leave no room in the context for the graph the agent is there to write.
	 */
	@Test
	public void testPortsAreOmittedUnlessAsked() {
		assertFalse(list(new JsonObject()).contains("detection/face"));
		assertTrue(list(new JsonObject().put("includePorts", true)).contains("detections (detection/face, MANY)"));
	}

	@Test
	public void testCategoryAndQueryFilter() {
		assertEquals(1, countLines(list(new JsonObject().put("category", "SOURCE"))));
		assertEquals(1, countLines(list(new JsonObject().put("query", "FACE"))));
		assertTrue(list(new JsonObject().put("query", "faces in an image")).contains("facedetect"),
			"The description is searched too — that is how a model finds a kind it cannot name");
	}

	@Test
	public void testLimitIsReportedNotSilent() {
		String text = list(new JsonObject().put("limit", 1));
		assertTrue(text.contains("Found 3 node kinds, showing the first 1"),
			"A clipped listing that claims to be complete is how a model concludes a kind does not exist");
		assertEquals(1, countLines(text));
	}

	@Test
	public void testGetReportsPortsGroupsAndOptions() {
		String text = get(new JsonObject().put("kind", "facedetect"));
		assertTrue(text.contains("category: ANALYSIS"));
		assertTrue(text.contains("- image : media/image ONE (group media_alt)"));
		assertTrue(text.contains("- detections : detection/face MANY"));
		assertTrue(text.contains("media_alt : XOR (exactly one member must be wired)"));
		assertTrue(text.contains("minScore : NUMBER (default 0.5)"));
		assertTrue(text.contains("range 0..1"));
	}

	/**
	 * Availability is fleet state, not a property of the definition — so it is reported, and reported as non-blocking.
	 */
	@Test
	public void testGetReportsAvailability() {
		assertTrue(get(new JsonObject().put("kind", "facedetect")).contains("available: no"));
		assertTrue(get(new JsonObject().put("kind", "facedetect")).contains("can still be saved"));
	}

	@Test
	public void testUnknownKindPointsAtTheListing() {
		String text = get(new JsonObject().put("kind", "no-such-kind"));
		assertTrue(text.contains("No node kind 'no-such-kind' exists"));
		assertTrue(text.contains("list_node_descriptors"));
	}

	@Test
	public void testMissingKindIsAnErrorResultNotAFailure() {
		assertTrue(get(new JsonObject()).startsWith("ERROR: The kind parameter is required."));
	}

	@Test
	public void testDescriptors() {
		assertEquals(List.of("READ_PIPELINE"), new ListNodeDescriptorsTool(registry).descriptor().requiredPermissions());
		assertEquals(List.of("READ_PIPELINE"), new GetNodeDescriptorTool(registry, availability).descriptor().requiredPermissions());
		assertTrue(new GetNodeDescriptorTool(registry, availability).descriptor().inputSchema()
			.getJsonArray("required").contains("kind"));
		// The whole category vocabulary must be offerable, or a kind becomes unfilterable.
		assertEquals(NodeCategory.values().length, new ListNodeDescriptorsTool(registry).descriptor().inputSchema()
			.getJsonObject("properties").getJsonObject("category").getJsonArray("enum").size());
	}

	private static int countLines(String text) {
		return (int) text.lines().filter(l -> l.startsWith("- ")).count();
	}

}
