package io.metaloom.loom.nodes.spec;

import static io.metaloom.loom.nodes.spec.ContentTypes.*;
import static io.metaloom.loom.nodes.spec.NodeCategory.*;
import static io.metaloom.loom.nodes.spec.NodeMode.*;
import static io.metaloom.loom.nodes.spec.ParameterType.*;

import java.util.List;

/**
 * Provides the node descriptor for the VLM (vision-language model) node.
 */
public class VlmDescriptorProvider implements NodeDescriptorProvider {

	private static final List<String> STANDARD_EVENTS = List.of(
		"NODE_STARTED", "NODE_COMPLETED", "NODE_FAILED", "NODE_SKIPPED", "NODE_STATS");

	@Override
	public List<NodeDescriptor> getDescriptors() {
		return List.of(
			new NodeDescriptor()
				.setKind("vlm")
				.setName("VLM (Vision-Language Model)")
				.setDescription("Read an image with a vision-language model served over an OpenAI-compatible endpoint. "
					+ "Ships an olmOCR preset for transcribing document pages.")
				.setIcon("image_search")
				.setCategory(ANALYSIS)
				.setInputs(List.of(new NodeInput("media", MEDIA_IMAGE, true)))
				.setOutputs(List.of(new NodeOutput("vlm_result", DATA_TEXT)))
				.setParameters(List.of(
					commonEnabled(), commonProcessIncomplete(), commonRetryFailed(),
					new NodeParameter().setKey("endpointUrl").setType(STRING)
						.setDefaultValue("http://127.0.0.1:8000")
						.setLabel("Endpoint URL").setDescription("Base URL of the OpenAI-compatible vision endpoint (e.g. vLLM)"),
					new NodeParameter().setKey("model").setType(STRING)
						.setDefaultValue("allenai/olmOCR-2-7B-1025-FP8")
						.setLabel("Model").setDescription("Model id to select on the endpoint"),
					new NodeParameter().setKey("responseFormat").setType(ENUM)
						.setDefaultValue("OLMOCR")
						.setLabel("Response Format").setDescription("How to read the model's answer: TEXT, JSON or OLMOCR front matter"),
					new NodeParameter().setKey("maxImageDim").setType(INTEGER)
						.setDefaultValue(1288)
						.setLabel("Max Image Size").setDescription("Longest image side in pixels sent to the model; 0 disables scaling"),
					new NodeParameter().setKey("maxTokens").setType(INTEGER)
						.setDefaultValue(4096)
						.setLabel("Max Tokens").setDescription("Output token budget; a full document page needs a few thousand")))
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
