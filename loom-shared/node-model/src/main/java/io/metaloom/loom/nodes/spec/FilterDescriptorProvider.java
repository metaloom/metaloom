package io.metaloom.loom.nodes.spec;

import static io.metaloom.loom.nodes.spec.ContentTypeRegistry.MEDIA_ANY;
import static io.metaloom.loom.nodes.spec.ContentTypeRegistry.TEXT_ANY;
import static io.metaloom.loom.nodes.spec.NodeCategory.FILTER;
import static io.metaloom.loom.nodes.spec.NodeMode.PARALLEL;
import static io.metaloom.loom.nodes.spec.ParameterType.BOOLEAN;
import static io.metaloom.loom.nodes.spec.ParameterType.ENUM;
import static io.metaloom.loom.nodes.spec.ParameterType.NUMBER;
import static io.metaloom.loom.nodes.spec.ParameterType.PORT_LIST;
import static io.metaloom.loom.nodes.spec.ParameterType.STRING;
import static io.metaloom.loom.nodes.spec.PortSpec.one;
import static io.metaloom.loom.nodes.spec.PortSpec.optionalOne;

import java.util.List;

/**
 * Provides the descriptor for the one {@code filter} node.
 *
 * <p>
 * This replaced eight {@code filter-*} kinds that were advertised in the palette and could never run: their implementations extended
 * {@code AbstractPipelineNode} rather than {@code FilesystemNode}, so they could not be bound into the executable-kind map at all, and a graph using
 * one saved, validated, dispatched, and then failed at the worker. They also advertised a {@code media} output port that no filter ever emitted.
 * </p>
 *
 * <p>
 * The output side is <strong>dynamic</strong>: {@link FilterPortResolver} derives one port per configured bucket from the {@code buckets} option, so
 * routing is expressed by drawing an edge from a named port rather than by tagging an edge {@code PASS} or {@code REJECT}. The bucket ports are
 * {@link PortSpec#isSelective() selective}, which is what makes the engine skip a consumer for the items that went down another branch.
 * </p>
 *
 * <p>
 * The category stays {@link NodeCategory#FILTER}: {@code PipelineValidationService} rejects a non-{@code ANY} branch whose source is not a filter, and
 * graphs that still use the boolean {@code passed} verdict must keep working.
 * </p>
 */
public class FilterDescriptorProvider implements NodeDescriptorProvider {

	private static final List<String> STANDARD_EVENTS = List.of(
		"NODE_STARTED", "NODE_COMPLETED", "NODE_FAILED", "NODE_SKIPPED", "NODE_STATS");

	@Override
	public List<NodeDescriptor> getDescriptors() {
		return List.of(
			new NodeDescriptor()
				.setKind("filter")
				.setName("Filter")
				.setDescription("Route each item down one branch per configured bucket. The output port is the branch: a node wired to a port that "
					+ "carried nothing for an item is skipped for that item.")
				// call_split rather than filter_alt: this routes N ways, it does not merely gate.
				.setIcon("call_split")
				.setCategory(FILTER)
				.setInputPorts(List.of(
					one("media", MEDIA_ANY)
						.describedAs("Media", "The item being routed"),
					optionalOne("text", TEXT_ANY)
						.describedAs("Text", "The text to classify - a transcript, extracted document text or a caption. "
							+ "Without it every item lands in 'other'")))
				// Owned by FilterPortResolver. A dynamic descriptor must declare no static outputs -
				// NodeDescriptorPortsTest enforces both halves of that contract.
				.setOutputPorts(List.of())
				.setDynamicPorts(true)
				.setParameters(List.of(
					commonEnabled(),
					new NodeParameter().setKey("filterBy").setType(ENUM)
						.setValues(List.of("LANGUAGE"))
						.setDefaultValue("LANGUAGE")
						.setLabel("Filter By")
						.setDescription("What the buckets are matched against"),
					new NodeParameter().setKey("buckets").setType(PORT_LIST)
						.setDefaultValue(List.of())
						.setLabel("Buckets")
						.setDescription("One output port per bucket. An 'other' port for everything else is always present"),
					new NodeParameter().setKey("model").setType(STRING)
						.setDefaultValue("meta-llama/Llama-3.2-3B-Instruct")
						.setLabel("Model")
						.setDescription("The model asked to classify each item"),
					new NodeParameter().setKey("openaiUrl").setType(STRING)
						.setDefaultValue("http://127.0.0.1:8080/v1")
						.setLabel("OpenAI URL").setDescription("Base URL of the OpenAI-compatible backend (llama.cpp, vLLM, Ollama /v1, ...)"),
					new NodeParameter().setKey("maxTextChars").setType(ParameterType.INTEGER)
						.setDefaultValue(2000).setMin(1)
						.setLabel("Max Text Characters")
						.setDescription("How much of the text to send to the model"),
					new NodeParameter().setKey("minConfidence").setType(NUMBER)
						.setDefaultValue(0.0).setMin(0.0).setMax(1.0)
						.setLabel("Minimum Confidence")
						.setDescription("Classifications below this confidence are routed to 'other'")))
				.setDefaultConcurrency(4)
				.setDefaultMode(PARALLEL)
				.setEvents(STANDARD_EVENTS));
	}

	private static NodeParameter commonEnabled() {
		return new NodeParameter().setKey("enabled").setType(BOOLEAN).setDefaultValue(true)
			.setLabel("Enabled").setDescription("Whether this node is active in the pipeline");
	}
}
