package io.metaloom.loom.nodes.spec;

import static io.metaloom.loom.nodes.spec.ContentTypeRegistry.*;
import static io.metaloom.loom.nodes.spec.NodeCategory.*;
import static io.metaloom.loom.nodes.spec.NodeMode.*;
import static io.metaloom.loom.nodes.spec.ParameterType.*;
import static io.metaloom.loom.nodes.spec.PortSpec.one;

import java.util.List;

/**
 * Provides the node descriptor for the text-to-speech node.
 *
 * <p>
 * The node's {@code sourceNodeId} / {@code sourceOutputKey} options are gone: which upstream output
 * supplies the text is now the {@code text} input port and the edge drawn into it. That is what
 * removes the trap where renaming a node in the editor silently starved this one.
 * </p>
 */
public class TtsDescriptorProvider implements NodeDescriptorProvider {

	private static final List<String> STANDARD_EVENTS = List.of(
		"NODE_STARTED", "NODE_COMPLETED", "NODE_FAILED", "NODE_SKIPPED", "NODE_STATS");

	@Override
	public List<NodeDescriptor> getDescriptors() {
		return List.of(
			new NodeDescriptor()
				.setNodeId("tts")
				.setName("Text to Speech")
				.setDescription("Speak upstream text through the /v1/tts sidecar. German routes to Orpheus/Kartoffel, "
					+ "English to Kokoro. The WAV is written to the worker's local cache; wire it into a sink to keep it.")
				.setIcon("record_voice_over")
				.setCategory(TRANSFORM)
				.setInputPorts(List.of(
					one("text", TEXT_ANY)
						.describedAs("Text", "The prose to speak - an LLM answer, a transcript, a caption or any other upstream text")))
				.setOutputPorts(List.of(
					one("audio", ARTIFACT_AUDIO)
						.describedAs("Audio", "The synthesised WAV in the worker's local cache; wire it into a sink to keep it"),
					one("flag", SCALAR_STRING)
						.describedAs("Flag", "Processing marker recording how this node finished for the item")))
				.setParameters(List.of(
					commonEnabled(), commonProcessIncomplete(), commonRetryFailed(),
					new NodeParameter().setKey("ttsHost").setType(STRING).setDefaultValue("localhost")
						.setLabel("Sidecar Host").setDescription("Host of the /v1/tts sidecar"),
					new NodeParameter().setKey("ttsPort").setType(INTEGER).setDefaultValue(9100)
						.setLabel("Sidecar Port").setDescription("Port of the /v1/tts sidecar").setMin(1),
					new NodeParameter().setKey("language").setType(STRING).setDefaultValue("de")
						.setLabel("Language")
						.setDescription("Selects the synthesis stack inside the sidecar: 'de' uses Orpheus/Kartoffel, 'en' uses Kokoro"),
					new NodeParameter().setKey("voice").setType(STRING).setDefaultValue("Jakob")
						.setLabel("Voice")
						.setDescription("Voice id offered by the selected stack. A voice from the other language is not substituted"),
					new NodeParameter().setKey("timeoutMs").setType(INTEGER).setDefaultValue(0)
						.setLabel("Timeout (ms)").setDescription("Wall-clock budget per item; 0 leaves it to the worker default").setMin(0)))
				// One sidecar holds one model on one device, so parallel calls only queue behind each other.
				.setDefaultConcurrency(1)
				.setDefaultMode(PARALLEL)
				.setEvents(STANDARD_EVENTS));
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
