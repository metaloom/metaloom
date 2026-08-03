package io.metaloom.loom.nodes.spec;

import static io.metaloom.loom.nodes.spec.ContentTypeRegistry.*;
import static io.metaloom.loom.nodes.spec.NodeCategory.*;
import static io.metaloom.loom.nodes.spec.NodeMode.*;
import static io.metaloom.loom.nodes.spec.ParameterType.*;
import static io.metaloom.loom.nodes.spec.PortSpec.optionalOne;
import static io.metaloom.loom.nodes.spec.PortSpec.one;

import java.util.List;

/**
 * Provides the node descriptor for the video-generation node.
 *
 * <p>
 * Both inputs are <strong>optional</strong>, because the two modes need different ones: a
 * {@code GENERATE} node runs from its configured prompt alone and needs neither, while an
 * {@code ANIMATE} node needs the source image as its opening frame. They are not an XOR group -
 * an animate driven by an upstream prompt legitimately wires both.
 * </p>
 */
public class VideoGenDescriptorProvider implements NodeDescriptorProvider {

	private static final List<String> STANDARD_EVENTS = List.of(
		"NODE_STARTED", "NODE_COMPLETED", "NODE_FAILED", "NODE_SKIPPED", "NODE_STATS");

	@Override
	public List<NodeDescriptor> getDescriptors() {
		return List.of(
			new NodeDescriptor()
				.setNodeId("videogen")
				.setName("Video Generation")
				.setDescription("Generate a short video clip through the LTX-2 video sidecar - text-to-video from a prompt, or "
					+ "image-to-video from the source asset. The MP4 (with synchronised audio) is written to the worker's local "
					+ "cache; wire it into a sink to keep it.")
				.setIcon("movie")
				.setCategory(TRANSFORM)
				.setInputPorts(List.of(
					optionalOne("prompt", TEXT_ANY)
						.describedAs("Prompt", "Upstream text used instead of the configured prompt - an LLM answer or a caption"),
					optionalOne("media", MEDIA_IMAGE)
						.describedAs("Source Image", "The still image to animate. Required in ANIMATE mode and ignored in GENERATE mode")))
				.setOutputPorts(List.of(
					one("video", ARTIFACT_VIDEO)
						.describedAs("Video", "The generated MP4 in the worker's local cache; wire it into a sink to keep it"),
					one("flag", SCALAR_STRING)
						.describedAs("Flag", "Processing marker recording how this node finished for the item")))
				.setParameters(List.of(
					commonEnabled(), commonProcessIncomplete(), commonRetryFailed(),
					new NodeParameter().setKey("mode").setType(ENUM)
						.setValues(List.of("GENERATE", "ANIMATE"))
						.setDefaultValue("GENERATE")
						.setLabel("Mode")
						.setDescription("GENERATE ignores the source pixels and works from the prompt; ANIMATE feeds the source image in as the opening frame"),
					new NodeParameter().setKey("prompt").setType(STRING).setDefaultValue("")
						.setLabel("Prompt")
						.setDescription("What to animate. Used unless the Prompt input port is wired"),
					new NodeParameter().setKey("negativePrompt").setType(STRING).setDefaultValue("")
						.setLabel("Negative Prompt")
						.setDescription("What to steer away from. Left empty the sidecar applies its own default"),
					new NodeParameter().setKey("host").setType(STRING).setDefaultValue("localhost")
						.setLabel("Sidecar Host").setDescription("Host of the video-generation sidecar"),
					new NodeParameter().setKey("port").setType(INTEGER).setDefaultValue(9220)
						.setLabel("Sidecar Port").setDescription("Port of the video-generation sidecar").setMin(1),
					new NodeParameter().setKey("generateEndpoint").setType(STRING).setDefaultValue("/generate")
						.setLabel("Generate Endpoint").setDescription("Sidecar path called in GENERATE mode"),
					new NodeParameter().setKey("animateEndpoint").setType(STRING).setDefaultValue("/animate")
						.setLabel("Animate Endpoint").setDescription("Sidecar path called in ANIMATE mode"),
					new NodeParameter().setKey("width").setType(INTEGER).setDefaultValue(768)
						.setLabel("Width (px)").setDescription("Width of the generated clip; snapped to a multiple of 32 by the sidecar").setMin(1),
					new NodeParameter().setKey("height").setType(INTEGER).setDefaultValue(512)
						.setLabel("Height (px)").setDescription("Height of the generated clip; snapped to a multiple of 32 by the sidecar").setMin(1),
					new NodeParameter().setKey("numFrames").setType(INTEGER).setDefaultValue(49)
						.setLabel("Frames").setDescription("Number of frames; snapped to k*8+1 by the sidecar").setMin(1),
					new NodeParameter().setKey("fps").setType(INTEGER).setDefaultValue(24)
						.setLabel("FPS").setDescription("Frame rate of the produced clip").setMin(1),
					new NodeParameter().setKey("steps").setType(INTEGER).setDefaultValue(40)
						.setLabel("Steps").setDescription("Diffusion steps. More steps cost proportionally more time").setMin(1),
					new NodeParameter().setKey("guidance").setType(NUMBER).setDefaultValue(4.0)
						.setLabel("Guidance").setDescription("Prompt guidance scale").setMin(0.0).setStep(0.5),
					new NodeParameter().setKey("seed").setType(INTEGER)
						.setLabel("Seed")
						.setDescription("Fix the seed to make a run reproducible. Left empty the sidecar picks one per item"),
					new NodeParameter().setKey("timeoutMs").setType(INTEGER).setDefaultValue(1800000)
						.setLabel("Timeout (ms)").setDescription("Wall-clock budget per item").setMin(1)))
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
