package io.metaloom.loom.nodes.spec;

import static io.metaloom.loom.nodes.spec.ContentTypeRegistry.SCALAR_STRING;
import static io.metaloom.loom.nodes.spec.ContentTypeRegistry.STRUCT_JSON;
import static io.metaloom.loom.nodes.spec.ContentTypeRegistry.TEXT_ANY;
import static io.metaloom.loom.nodes.spec.ContentTypeRegistry.TEXT_PLAIN;
import static io.metaloom.loom.nodes.spec.NodeCategory.ANALYSIS;
import static io.metaloom.loom.nodes.spec.NodeMode.PARALLEL;
import static io.metaloom.loom.nodes.spec.ParameterType.INTEGER;
import static io.metaloom.loom.nodes.spec.ParameterType.STRING;
import static io.metaloom.loom.nodes.spec.PortSpec.one;

import java.util.List;

/**
 * Provides the node descriptor for the translate node.
 *
 * <p>
 * The ports are static, unlike {@code llm} and {@code vlm}: one node instance translates into one
 * language, so there is nothing per-instance to derive and no {@link NodePortResolver} to register.
 * </p>
 */
public class TranslateDescriptorProvider implements NodeDescriptorProvider {

	private static final List<String> STANDARD_EVENTS = List.of(
		"NODE_STARTED", "NODE_COMPLETED", "NODE_FAILED", "NODE_SKIPPED", "NODE_STATS");

	@Override
	public List<NodeDescriptor> getDescriptors() {
		return List.of(
			new NodeDescriptor()
				.setKind("translate")
				.setName("Translate")
				.setDescription("Translate text produced by an upstream node into a target language using a language model.")
				.setIcon("translate")
				.setCategory(ANALYSIS)
				.setInputPorts(List.of(
					one("text", TEXT_ANY)
						.describedAs("Text", "The prose to translate - a transcript, document body, OCR result or any other upstream text")))
				.setOutputPorts(List.of(
					one("translation", TEXT_PLAIN)
						.describedAs("Translation", "The translated text, ready to be spoken by the TTS node or stored"),
					one("language", SCALAR_STRING)
						.describedAs("Language", "The target language the text was translated into"),
					one("result", STRUCT_JSON)
						.describedAs("Result", "The stored payload: translation, languages, model and how many chunks it took")))
				.setParameters(List.of(
					commonEnabled(), commonProcessIncomplete(), commonRetryFailed(),
					new NodeParameter().setKey("targetLanguage").setType(STRING).setDefaultValue("en")
						.setLabel("Target Language").setDescription("Language to translate into; also the variant the result is stored under"),
					new NodeParameter().setKey("sourceLanguage").setType(STRING).setDefaultValue("auto")
						.setLabel("Source Language").setDescription("Language of the input, or 'auto' to let the model work it out"),
					new NodeParameter().setKey("model").setType(STRING).setDefaultValue("google/gemma-2-27b-it")
						.setLabel("Model").setDescription("Model id asked to translate; also recorded as the producer version"),
					new NodeParameter().setKey("openaiUrl").setType(STRING).setDefaultValue("http://127.0.0.1:8080/v1")
						.setLabel("OpenAI URL").setDescription("Base URL of the OpenAI-compatible backend (llama.cpp, vLLM, Ollama /v1, ...)"),
					new NodeParameter().setKey("contextWindow").setType(INTEGER).setDefaultValue(2048)
						.setLabel("Context Window").setDescription("Tokens the model is told it may use for one call").setMin(1),
					new NodeParameter().setKey("promptTemplate").setType(STRING)
						.setLabel("Prompt Template").setDescription("Instruction sent with each chunk; must contain the ${text} placeholder"),
					new NodeParameter().setKey("maxChunkChars").setType(INTEGER).setDefaultValue(8000)
						.setLabel("Max Chunk Characters")
						.setDescription("Longer input is split on paragraph and sentence boundaries into chunks of this size").setMin(200),
					new NodeParameter().setKey("maxChars").setType(INTEGER).setDefaultValue(200000)
						.setLabel("Max Characters").setDescription("Upper bound on the total input text").setMin(1)))
				.setDefaultConcurrency(1)
				.setDefaultMode(PARALLEL)
				.setEvents(STANDARD_EVENTS)
		);
	}

	private static NodeParameter commonEnabled() {
		return new NodeParameter().setKey("enabled").setType(ParameterType.BOOLEAN).setDefaultValue(true)
			.setLabel("Enabled").setDescription("Whether this node is active in the pipeline");
	}

	private static NodeParameter commonProcessIncomplete() {
		return new NodeParameter().setKey("processIncomplete").setType(ParameterType.BOOLEAN).setDefaultValue(false)
			.setLabel("Process Incomplete").setDescription("Process media files that are still being written");
	}

	private static NodeParameter commonRetryFailed() {
		return new NodeParameter().setKey("retryFailed").setType(ParameterType.BOOLEAN).setDefaultValue(false)
			.setLabel("Retry Failed").setDescription("Retry processing media that previously failed");
	}
}
