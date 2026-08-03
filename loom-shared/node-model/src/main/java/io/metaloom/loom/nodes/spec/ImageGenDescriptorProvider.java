package io.metaloom.loom.nodes.spec;

import static io.metaloom.loom.nodes.spec.ContentTypeRegistry.*;
import static io.metaloom.loom.nodes.spec.NodeCategory.*;
import static io.metaloom.loom.nodes.spec.NodeMode.*;
import static io.metaloom.loom.nodes.spec.ParameterType.*;
import static io.metaloom.loom.nodes.spec.PortSpec.optionalOne;
import static io.metaloom.loom.nodes.spec.PortSpec.one;

import java.util.List;

/**
 * Provides the node descriptor for the image-generation node.
 *
 * <p>
 * Both inputs are <strong>optional</strong>, because the two modes need different ones: a
 * {@code GENERATE} node runs from its configured prompt alone and needs neither, while a
 * {@code REMIX} node needs the source image. They are not an XOR group - a remix driven by an
 * upstream prompt legitimately wires both.
 * </p>
 */
public class ImageGenDescriptorProvider implements NodeDescriptorProvider {

	private static final List<String> STANDARD_EVENTS = List.of(
		"NODE_STARTED", "NODE_COMPLETED", "NODE_FAILED", "NODE_SKIPPED", "NODE_STATS");

	@Override
	public List<NodeDescriptor> getDescriptors() {
		return List.of(
			new NodeDescriptor()
				.setNodeId("imagegen")
				.setName("Image Generation")
				.setDescription("Generate an image through the image-generation sidecar - text-to-image from a prompt, or "
					+ "image-to-image from the source asset. The PNG is written to the worker's local cache; wire it into a "
					+ "sink to keep it.")
				.setIcon("auto_awesome")
				.setCategory(TRANSFORM)
				.setInputPorts(List.of(
					optionalOne("prompt", TEXT_ANY)
						.describedAs("Prompt", "Upstream text used instead of the configured prompt - an LLM answer or a caption"),
					optionalOne("media", MEDIA_IMAGE)
						.describedAs("Source Image", "The image to remix. Required in REMIX mode and ignored in GENERATE mode")))
				.setOutputPorts(List.of(
					one("image", ARTIFACT_IMAGE)
						.describedAs("Image", "The generated PNG in the worker's local cache; wire it into a sink to keep it"),
					one("flag", SCALAR_STRING)
						.describedAs("Flag", "Processing marker recording how this node finished for the item")))
				.setParameters(List.of(
					commonEnabled(), commonProcessIncomplete(), commonRetryFailed(),
					new NodeParameter().setKey("mode").setType(ENUM)
						.setValues(List.of("GENERATE", "REMIX"))
						.setDefaultValue("GENERATE")
						.setLabel("Mode")
						.setDescription("GENERATE ignores the source pixels and works from the prompt; REMIX feeds the source image in too"),
					new NodeParameter().setKey("prompt").setType(STRING).setDefaultValue("")
						.setLabel("Prompt")
						.setDescription("What to draw. Used unless the Prompt input port is wired"),
					new NodeParameter().setKey("host").setType(STRING).setDefaultValue("localhost")
						.setLabel("Sidecar Host").setDescription("Host of the image-generation sidecar"),
					new NodeParameter().setKey("port").setType(INTEGER).setDefaultValue(9200)
						.setLabel("Sidecar Port").setDescription("Port of the image-generation sidecar").setMin(1),
					new NodeParameter().setKey("generateEndpoint").setType(STRING).setDefaultValue("/generate")
						.setLabel("Generate Endpoint").setDescription("Sidecar path called in GENERATE mode"),
					new NodeParameter().setKey("remixEndpoint").setType(STRING).setDefaultValue("/remix")
						.setLabel("Remix Endpoint").setDescription("Sidecar path called in REMIX mode"),
					new NodeParameter().setKey("width").setType(INTEGER).setDefaultValue(1024)
						.setLabel("Width (px)").setDescription("Width of the generated image").setMin(1),
					new NodeParameter().setKey("height").setType(INTEGER).setDefaultValue(1024)
						.setLabel("Height (px)").setDescription("Height of the generated image").setMin(1),
					new NodeParameter().setKey("strength").setType(NUMBER).setDefaultValue(0.6)
						.setLabel("Remix Strength")
						.setDescription("How far REMIX may depart from the source image; 1.0 keeps almost nothing of it")
						.setMin(0.01).setMax(1.0).setStep(0.05),
					new NodeParameter().setKey("steps").setType(INTEGER).setDefaultValue(30)
						.setLabel("Steps").setDescription("Diffusion steps. More steps cost proportionally more time").setMin(1),
					new NodeParameter().setKey("seed").setType(INTEGER)
						.setLabel("Seed")
						.setDescription("Fix the seed to make a run reproducible. Left empty the sidecar picks one per item"),
					new NodeParameter().setKey("timeoutMs").setType(INTEGER).setDefaultValue(120000)
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
