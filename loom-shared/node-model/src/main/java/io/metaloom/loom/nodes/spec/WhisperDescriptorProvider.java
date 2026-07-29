package io.metaloom.loom.nodes.spec;

import static io.metaloom.loom.nodes.spec.ContentTypeRegistry.*;
import static io.metaloom.loom.nodes.spec.NodeCategory.*;
import static io.metaloom.loom.nodes.spec.NodeMode.*;
import static io.metaloom.loom.nodes.spec.ParameterType.*;
import static io.metaloom.loom.nodes.spec.PortGroup.xor;
import static io.metaloom.loom.nodes.spec.PortSpec.one;

import java.util.List;

/**
 * Provides the node descriptor for the Whisper speech-to-text node.
 */
public class WhisperDescriptorProvider implements NodeDescriptorProvider {

	private static final List<String> STANDARD_EVENTS = List.of(
		"NODE_STARTED", "NODE_COMPLETED", "NODE_FAILED", "NODE_SKIPPED", "NODE_STATS");

	@Override
	public List<NodeDescriptor> getDescriptors() {
		return List.of(
			new NodeDescriptor()
				.setKind("whisper")
				.setName("Whisper (Speech-to-Text)")
				.setDescription("Transcribe audio/video speech to text using Whisper.")
				.setIcon("mic")
				.setCategory(ANALYSIS)
				.setInputPorts(List.of(
					one("audio", MEDIA_AUDIO).inGroup("media_alt")
						.describedAs("Audio", "An audio file to transcribe"),
					one("video", MEDIA_VIDEO).inGroup("media_alt")
						.describedAs("Video", "A video whose audio track is demuxed and transcribed")))
				.setInputGroups(List.of(
					xor("media_alt", "Media")))
				.setOutputPorts(List.of(
					one("transcript", TEXT_TRANSCRIPT)
						.describedAs("Transcript", "The recognised speech with per-segment start and end times")))
				.setParameters(List.of(
					commonEnabled(), commonProcessIncomplete(), commonRetryFailed(),
					new NodeParameter().setKey("modelPath").setType(STRING)
						.setDefaultValue("models/ggml-large-v3-turbo.bin")
						.setLabel("Model Path").setDescription("Path to the Whisper model file"),
					new NodeParameter().setKey("temperature").setType(NUMBER).setDefaultValue(0.0)
						.setLabel("Temperature").setMin(0.0).setMax(1.0).setStep(0.1),
					new NodeParameter().setKey("temperatureInc").setType(NUMBER).setDefaultValue(0.2)
						.setLabel("Temperature Increment").setMin(0.0).setMax(1.0).setStep(0.05),
					new NodeParameter().setKey("language").setType(STRING)
						.setLabel("Language").setDescription("Target language code (e.g. 'en', 'de')"),
					new NodeParameter().setKey("useGpu").setType(BOOLEAN).setDefaultValue(true)
						.setLabel("Use GPU"),
					new NodeParameter().setKey("gpuDevice").setType(INTEGER).setDefaultValue(0)
						.setLabel("GPU Device Index").setMin(0)))
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
