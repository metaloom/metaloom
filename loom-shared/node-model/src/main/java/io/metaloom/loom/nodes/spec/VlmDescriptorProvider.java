package io.metaloom.loom.nodes.spec;

import static io.metaloom.loom.nodes.spec.ContentTypeRegistry.*;
import static io.metaloom.loom.nodes.spec.NodeCategory.*;
import static io.metaloom.loom.nodes.spec.NodeMode.*;
import static io.metaloom.loom.nodes.spec.ParameterType.*;
import static io.metaloom.loom.nodes.spec.PortSpec.one;

import java.util.List;
import java.util.Map;

/**
 * Provides the node descriptor for the VLM (vision-language model) node.
 *
 * <p>
 * The output side is <strong>dynamic</strong>: the node emits one result per configured prompt, so
 * {@link VlmPortResolver} derives the ports from the {@code prompts} option
 * rather than the descriptor declaring a single fixed result key the node never actually writes.
 * </p>
 */
public class VlmDescriptorProvider implements NodeDescriptorProvider {

	private static final List<String> STANDARD_EVENTS = List.of(
		"NODE_STARTED", "NODE_COMPLETED", "NODE_FAILED", "NODE_SKIPPED", "NODE_STATS");

	@Override
	public List<NodeDescriptor> getDescriptors() {
		return List.of(
			new NodeDescriptor()
				.setNodeId("vlm")
				.setName("VLM (Vision-Language Model)")
				.setDescription("Read an image with a vision-language model served over an OpenAI-compatible endpoint. "
					+ "Ships an olmOCR preset for transcribing document pages.")
				.setIcon("image_search")
				.setCategory(ANALYSIS)
				.setInputPorts(List.of(
					one("media", MEDIA_IMAGE)
						.describedAs("Image", "The page or frame the configured prompts are asked about")))
				.setOutputPorts(List.of())
				.setDynamicPorts(true)
				.setParameters(List.of(
					commonEnabled(), commonProcessIncomplete(), commonRetryFailed(),
					new NodeParameter().setKey("endpointUrl").setType(STRING)
						.setDefaultValue("http://127.0.0.1:8000")
						.setLabel("Endpoint URL").setDescription("Base URL of the OpenAI-compatible vision endpoint (e.g. vLLM)"),
					new NodeParameter().setKey("apiKey").setType(STRING)
						.setLabel("API Key").setDescription("Bearer token for the endpoint. Usually unset - a local vLLM does not check it"),
					// model / responseFormat / maxImageDim / maxTokens used to be advertised here. They are
					// fields of VlmNodePrompt, not of VlmNodeOptions, which is what a vlm node's config binds
					// into - so anything an author set in those four form fields was silently discarded. They
					// are per-prompt settings and belong in the prompts map below.
					new NodeParameter().setKey("prompts").setType(JSON).setDefaultValue(Map.of()).setRows(8)
						.setLabel("Prompts")
						.setDescription("Prompt id to task, as {\"<id>\": {\"model\": ..., \"prompt\": ..., \"responseFormat\": "
							+ "TEXT|JSON|OLMOCR, \"maxImageDim\": ..., \"maxTokens\": ...}}. Each entry adds a "
							+ "result_<id> output port. Left empty the node falls back to the olmOCR preset")))
				// A single GPU serves one image at a time, so do not pile requests onto the endpoint by default.
				.setDefaultConcurrency(1)
				.setDefaultMode(PARALLEL)
				.setEvents(STANDARD_EVENTS)
		);
	}

	private static NodeParameter commonEnabled() {
		return new NodeParameter().setKey("enabled").setType(BOOLEAN).setDefaultValue(true)
			.setLabel("Enabled").setDescription("Whether this node is active in the pipeline");
	}

	private static NodeParameter commonProcessIncomplete() {
		return new NodeParameter().setKey("processIncomplete").setType(BOOLEAN).setDefaultValue(false)
			.setLabel("Process Incomplete").setDescription("Process media files that are still being written");
	}

	private static NodeParameter commonRetryFailed() {
		return new NodeParameter().setKey("retryFailed").setType(BOOLEAN).setDefaultValue(false)
			.setLabel("Retry Failed").setDescription("Retry processing media that previously failed");
	}
}
