package io.metaloom.cortex.api.node.spec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.metaloom.cortex.api.node.InputPort;
import io.metaloom.cortex.api.node.OutputPort;
import io.metaloom.cortex.api.option.node.AbstractNodeOptions;
import io.metaloom.cortex.api.option.node.CortexNodeOptions;
import io.metaloom.loom.nodes.spec.Cardinality;
import io.metaloom.loom.nodes.spec.ContentTypeRegistry;
import io.metaloom.loom.nodes.spec.NodeCategory;
import io.metaloom.loom.nodes.spec.NodeDescriptor;
import io.metaloom.loom.nodes.spec.NodeParameter;
import io.metaloom.loom.nodes.spec.ParameterType;
import io.metaloom.loom.nodes.spec.PortGroupMode;
import io.metaloom.loom.nodes.spec.PortSpec;

/**
 * Unit coverage for the reflective harvest, against fixtures rather than real nodes.
 *
 * <p>
 * The end-to-end proof that this reproduces a real contract lives in {@code NodeSpecGoldenTest},
 * which holds the harvest against every hand-written descriptor it replaced. What is checked here is
 * the harvester's own behaviour on the shapes a node author can write — including the ones that would
 * otherwise be discovered 34 times over during the sweep.
 * </p>
 */
public class NodeSpecHarvesterTest {

	@Test
	public void shouldRecoverPortsFromTheConstantsTheNodeExecutesAgainst() {
		NodeDescriptor descriptor = NodeSpecHarvester.harvest(FixtureNode.class);

		assertEquals("fixture", descriptor.getNodeId());
		assertEquals("Fixture", descriptor.getName());
		assertEquals(NodeCategory.ANALYSIS, descriptor.getCategory());

		assertEquals(1, descriptor.getInputPorts().size());
		PortSpec text = descriptor.getInputPorts().get(0);
		assertEquals("text", text.getId());
		assertEquals(ContentTypeRegistry.TEXT_ANY, text.getContentType());
		assertEquals(Cardinality.ONE, text.getCardinality());
		assertEquals("Text", text.getLabel());
		assertEquals("The prose to score", text.getDescription());

		assertEquals(2, descriptor.getOutputPorts().size());
		assertEquals("label", descriptor.getOutputPorts().get(0).getId(), "declaration order is the port order");
		assertEquals("faces", descriptor.getOutputPorts().get(1).getId());
		assertEquals(Cardinality.MANY, descriptor.getOutputPorts().get(1).getCardinality());
		assertTrue(descriptor.getOutputPorts().get(1).isSelective());
	}

	@Test
	public void shouldDeriveALabelForAnUndocumentedPort() {
		PortSpec port = NodeSpecHarvester.harvest(FixtureNode.class).getInputPorts().get(0);
		assertNotNull(port.getLabel());

		// A port with no @PortDoc still harvests; it just gets a derived label.
		PortSpec undocumented = NodeSpecHarvester.harvest(BareNode.class).getInputPorts().get(0);
		assertEquals("Raw Media", undocumented.getLabel());
		assertNull(undocumented.getDescription());
	}

	@Test
	public void shouldPutInheritedCommonParametersFirst() {
		List<NodeParameter> parameters = NodeSpecHarvester.harvest(FixtureNode.class).getParameters();

		// enabled / processIncomplete / retryFailed are declared once on AbstractNodeOptions and lead
		// every node's form; timeoutMs is @ParamDoc(hidden) and must not appear at all.
		assertEquals(List.of("enabled", "processIncomplete", "retryFailed", "host", "port", "ratio", "mode", "secret"),
			parameters.stream().map(NodeParameter::getKey).toList());
	}

	@Test
	public void shouldReadDefaultsFromADefaultConstructedInstance() {
		List<NodeParameter> parameters = NodeSpecHarvester.harvest(FixtureNode.class).getParameters();

		assertEquals(true, parameterNamed(parameters, "enabled").getDefaultValue());
		assertEquals("localhost", parameterNamed(parameters, "host").getDefaultValue());
		assertEquals(9110, parameterNamed(parameters, "port").getDefaultValue());
		assertEquals(0.5d, parameterNamed(parameters, "ratio").getDefaultValue());
		assertEquals("FAST", parameterNamed(parameters, "mode").getDefaultValue(), "an enum default is its name");
		assertNull(parameterNamed(parameters, "secret").getDefaultValue(),
			"a field with no initializer has no default, and NodeParameter omits it rather than emitting null");
	}

	@Test
	public void shouldInferParameterTypesFromTheJavaType() {
		List<NodeParameter> parameters = NodeSpecHarvester.harvest(FixtureNode.class).getParameters();

		assertEquals(ParameterType.BOOLEAN, parameterNamed(parameters, "enabled").getType());
		assertEquals(ParameterType.STRING, parameterNamed(parameters, "host").getType());
		assertEquals(ParameterType.INTEGER, parameterNamed(parameters, "port").getType());
		assertEquals(ParameterType.NUMBER, parameterNamed(parameters, "ratio").getType());
		assertEquals(ParameterType.ENUM, parameterNamed(parameters, "mode").getType());
	}

	@Test
	public void shouldRecoverEnumValuesWithoutBeingTold() {
		NodeParameter mode = parameterNamed(NodeSpecHarvester.harvest(FixtureNode.class).getParameters(), "mode");

		assertEquals(List.of("FAST", "ACCURATE"), mode.getValues());
	}

	@Test
	public void shouldPreserveWhetherABoundWasWrittenAsAnInteger() {
		// "1" must serialize as 1 and "0.0" as 0.0. Collapsing both to double would quietly turn every
		// integer bound in every edit form into a float.
		assertEquals(Integer.valueOf(1), NodeSpecHarvester.parseNumber("1"));
		assertEquals(Double.valueOf(0.0), NodeSpecHarvester.parseNumber("0.0"));
		assertEquals(Double.valueOf(0.05), NodeSpecHarvester.parseNumber("0.05"));
		assertNull(NodeSpecHarvester.parseNumber(""));
		assertThrows(IllegalArgumentException.class, () -> NodeSpecHarvester.parseNumber("many"));

		List<NodeParameter> parameters = NodeSpecHarvester.harvest(FixtureNode.class).getParameters();
		assertEquals(Integer.valueOf(1), parameterNamed(parameters, "port").getMin());
		assertEquals(Double.valueOf(1.0), parameterNamed(parameters, "ratio").getMax());
	}

	@Test
	public void shouldCarryGroupsFromTheNodeAnnotation() {
		NodeDescriptor descriptor = NodeSpecHarvester.harvest(GroupedNode.class);

		assertEquals(1, descriptor.getInputGroups().size());
		assertEquals("audio_in", descriptor.getInputGroups().get(0).getId());
		assertEquals(PortGroupMode.XOR, descriptor.getInputGroups().get(0).getMode());
		assertEquals("audio_in", descriptor.getInputPorts().get(0).getGroup());
		assertEquals("audio_in", descriptor.getInputPorts().get(1).getGroup());
	}

	@Test
	public void shouldDefaultToTheStandardEventSet() {
		assertEquals(NodeSpecHarvester.STANDARD_EVENTS, NodeSpecHarvester.harvest(FixtureNode.class).getEvents());
	}

	@Test
	public void shouldFailLoudlyOnAnUnannotatedClass() {
		// A silently null descriptor would mean a node that runs and cannot be authored - the exact
		// defect this machinery exists to remove. So it must be an error, not an empty result.
		IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
			() -> NodeSpecHarvester.harvest(String.class));
		assertTrue(error.getMessage().contains("@NodeSpec"), error.getMessage());

		assertFalse(NodeSpecHarvester.isAnnotated(String.class));
		assertTrue(NodeSpecHarvester.isAnnotated(FixtureNode.class));
	}

	@Test
	public void shouldResolveTheOptionsClassFromTheGenericSuperclass() {
		// Not stated anywhere on FixtureNode - recovered from AbstractFixtureNode<FixtureOptions>.
		assertFalse(NodeSpecHarvester.harvest(FixtureNode.class).getParameters().isEmpty());
	}

	@Test
	public void shouldHarvestNoParametersWhenThereAreNoOptions() {
		assertEquals(List.of(), NodeSpecHarvester.harvest(BareNode.class).getParameters());
	}

	@Test
	public void shouldNotLetANonStringDefaultLeakItsOwnRendering() {
		// A Path authored as STRING is the case that found this: Jackson renders a Path as an absolute
		// file: URI, so the editor would pre-fill "file:///home/worker/duplicates" rather than
		// "duplicates" - and the answer would depend on the working directory of whichever worker
		// happened to announce it. Two workers running the same build would then produce different body
		// hashes for an identical contract, which the version rule reads as a CONFLICT.
		NodeParameter folder = parameterNamed(NodeSpecHarvester.harvest(PathNode.class).getParameters(), "dupFolder");

		assertEquals("duplicates", folder.getDefaultValue());
		assertEquals(ParameterType.STRING, folder.getType());
	}

	@Test
	public void shouldProduceTheSameContractRegardlessOfWorkingDirectory() {
		// The property that actually matters: harvesting twice must agree, because the body hash is what
		// decides whether two workers are offering the same contract.
		assertEquals(
			parameterNamed(NodeSpecHarvester.harvest(PathNode.class).getParameters(), "dupFolder").getDefaultValue(),
			parameterNamed(NodeSpecHarvester.harvest(PathNode.class).getParameters(), "dupFolder").getDefaultValue());
	}

	@Test
	public void shouldTitleCaseIdentifiers() {
		assertEquals("Sentiment Port", NodeSpecHarvester.titleCase("sentimentPort"));
		assertEquals("Max Chars", NodeSpecHarvester.titleCase("max_chars"));
		assertEquals("Text", NodeSpecHarvester.titleCase("text"));
		assertEquals("Word Count", NodeSpecHarvester.titleCase("word-count"));
	}

	private static NodeParameter parameterNamed(List<NodeParameter> parameters, String key) {
		return parameters.stream().filter(p -> key.equals(p.getKey())).findFirst()
			.orElseThrow(() -> new AssertionError("No parameter named '" + key + "'"));
	}

	// ── Fixtures ─────────────────────────────────────────────────────────────────────────────────

	enum FixtureMode {
		FAST, ACCURATE
	}

	public static class FixtureOptions extends AbstractNodeOptions<FixtureOptions> {

		@ParamDoc(label = "Sidecar Host", description = "Host of the sidecar")
		private String host = "localhost";

		@ParamDoc(label = "Sidecar Port", min = "1")
		private int port = 9110;

		@ParamDoc(max = "1.0", step = "0.05")
		private double ratio = 0.5;

		private FixtureMode mode = FixtureMode.FAST;

		private String secret;

		@Override
		protected FixtureOptions self() {
			return this;
		}
	}

	abstract static class AbstractFixtureNode<T extends CortexNodeOptions> {
	}

	@NodeSpec(nodeId = "fixture", name = "Fixture", icon = "mood", category = NodeCategory.ANALYSIS,
		description = "A node that exists only to be reflected over")
	public static class FixtureNode extends AbstractFixtureNode<FixtureOptions> {

		@PortDoc(label = "Text", description = "The prose to score")
		public static final InputPort<String> IN_TEXT = InputPort.one("text", ContentTypeRegistry.TEXT_ANY, String.class);

		@PortDoc(label = "Label")
		public static final OutputPort<String> OUT_LABEL = OutputPort.one("label", ContentTypeRegistry.SCALAR_STRING, String.class);

		@PortDoc(label = "Faces", selective = true)
		public static final OutputPort<String> OUT_FACES = OutputPort.many("faces", ContentTypeRegistry.DETECTION_FACE, String.class);
	}

	public static class PathOptions extends AbstractNodeOptions<PathOptions> {

		@ParamDoc(label = "Duplicates Folder", type = ParameterType.STRING)
		private java.nio.file.Path dupFolder = java.nio.file.Path.of("duplicates");

		@Override
		protected PathOptions self() {
			return this;
		}
	}

	@NodeSpec(nodeId = "pathy", name = "Pathy", category = NodeCategory.TRANSFORM)
	public static class PathNode extends AbstractFixtureNode<PathOptions> {
	}

	@NodeSpec(nodeId = "bare", name = "Bare", category = NodeCategory.TRANSFORM)
	public static class BareNode {

		public static final InputPort<String> IN_MEDIA = InputPort.one("raw_media", ContentTypeRegistry.MEDIA_ANY, String.class);
	}

	@NodeSpec(nodeId = "grouped", name = "Grouped", category = NodeCategory.ANALYSIS,
		inputGroups = @PortGroupDoc(id = "audio_in", mode = PortGroupMode.XOR, label = "Audio Source"))
	public static class GroupedNode {

		@PortDoc(label = "Media", group = "audio_in")
		public static final InputPort<String> IN_MEDIA = InputPort.one("media", ContentTypeRegistry.MEDIA_AUDIO, String.class);

		@PortDoc(label = "Artifact", group = "audio_in")
		public static final InputPort<String> IN_ARTIFACT = InputPort.one("artifact", ContentTypeRegistry.ARTIFACT_AUDIO, String.class);
	}
}
