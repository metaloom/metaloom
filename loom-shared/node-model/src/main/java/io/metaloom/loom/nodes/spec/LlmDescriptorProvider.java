package io.metaloom.loom.nodes.spec;

import static io.metaloom.loom.nodes.spec.ContentTypes.*;
import static io.metaloom.loom.nodes.spec.NodeCategory.*;
import static io.metaloom.loom.nodes.spec.NodeMode.*;
import static io.metaloom.loom.nodes.spec.ParameterType.*;

import java.util.List;

/**
 * Provides the node descriptor for the LLM node.
 */
public class LlmDescriptorProvider implements NodeDescriptorProvider {

	private static final List<String> STANDARD_EVENTS = List.of(
		"NODE_STARTED", "NODE_COMPLETED", "NODE_FAILED", "NODE_SKIPPED", "NODE_STATS");

	@Override
	public List<NodeDescriptor> getDescriptors() {
		return List.of(
			new NodeDescriptor()
				.setKind("llm")
				.setName("LLM (Large Language Model)")
				.setDescription("Process media through an LLM (e.g. Ollama) with configurable prompts.")
				.setIcon("psychology")
				.setCategory(ANALYSIS)
				.setInputs(List.of(new NodeInput("media", MEDIA_ANY, true)))
				.setOutputs(List.of(new NodeOutput("llm_result", DATA_TEXT)))
				.setParameters(List.of(
					commonEnabled(), commonProcessIncomplete(), commonRetryFailed(),
					new NodeParameter().setKey("ollamaUrl").setType(STRING)
						.setDefaultValue("http://127.0.0.1:11434")
						.setLabel("Ollama URL").setDescription("URL of the Ollama service")))
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
