package io.metaloom.loom.nodes.spec;

import static io.metaloom.loom.nodes.spec.ContentTypeRegistry.ARTIFACT_IMAGE;
import static io.metaloom.loom.nodes.spec.ContentTypeRegistry.ARTIFACT_VIDEO;
import static io.metaloom.loom.nodes.spec.ContentTypeRegistry.MEDIA_ANY;
import static io.metaloom.loom.nodes.spec.ContentTypeRegistry.SCALAR_STRING;
import static io.metaloom.loom.nodes.spec.NodeCategory.TRANSFORM;
import static io.metaloom.loom.nodes.spec.NodeMode.PARALLEL;
import static io.metaloom.loom.nodes.spec.ParameterType.BOOLEAN;
import static io.metaloom.loom.nodes.spec.ParameterType.CODE;
import static io.metaloom.loom.nodes.spec.ParameterType.INTEGER;
import static io.metaloom.loom.nodes.spec.ParameterType.NUMBER;
import static io.metaloom.loom.nodes.spec.ParameterType.STRING;
import static io.metaloom.loom.nodes.spec.PortSpec.one;

import java.util.List;

/**
 * Provides the node descriptor for the watermark node.
 *
 * <p>
 * One input, two artifact outputs: the node accepts images and video on the same {@code media} port and writes to whichever output matches the item it
 * was handed. They are <strong>not</strong> an exclusive group - a graph that watermarks a mixed library legitimately wires both, each into its own
 * downstream branch, and a graph that only cares about one leaves the other unwired.
 * </p>
 */
public class WatermarkDescriptorProvider implements NodeDescriptorProvider {

	private static final List<String> STANDARD_EVENTS = List.of(
		"NODE_STARTED", "NODE_COMPLETED", "NODE_FAILED", "NODE_SKIPPED", "NODE_STATS");

	@Override
	public List<NodeDescriptor> getDescriptors() {
		return List.of(
			new NodeDescriptor()
				.setKind("watermark")
				.setName("Watermark")
				.setDescription("Composite a configured watermark image onto the asset - a still is redrawn with Graphics2D, a video is re-encoded "
					+ "through the ffmpeg overlay filter with its audio copied untouched. The source file is never modified; the marked copy is "
					+ "written to the worker's local cache, so wire it into a sink to keep it.")
				.setIcon("branding_watermark")
				.setCategory(TRANSFORM)
				.setInputPorts(List.of(
					one("media", MEDIA_ANY)
						.describedAs("Media", "The image or video to mark. Audio and documents are skipped")))
				.setOutputPorts(List.of(
					one("image", ARTIFACT_IMAGE)
						.describedAs("Marked Image", "The watermarked PNG, written for image items only"),
					one("video", ARTIFACT_VIDEO)
						.describedAs("Marked Video", "The watermarked clip in the source container, written for video items only"),
					one("flag", SCALAR_STRING)
						.describedAs("Flag", "Processing marker recording how this node finished for the item")))
				.setParameters(List.of(
					commonEnabled(), commonProcessIncomplete(), commonRetryFailed(),
					new NodeParameter().setKey("watermarkBase64").setType(CODE).setRows(6).setDefaultValue("")
						.setLabel("Watermark Image (base64)")
						.setDescription("The overlay image, base64-encoded. A full data:image/png;base64,... URI is accepted too. "
							+ "Carrying the image here rather than as a path keeps the pipeline runnable on any worker"),
					new NodeParameter().setKey("relX").setType(NUMBER).setDefaultValue(0.95)
						.setLabel("Horizontal Position")
						.setDescription("0.0 is flush left, 1.0 flush right, 0.5 centred. Measured against the space the overlay can slide in, "
							+ "so it never leaves the frame")
						.setMin(0.0).setMax(1.0).setStep(0.01),
					new NodeParameter().setKey("relY").setType(NUMBER).setDefaultValue(0.95)
						.setLabel("Vertical Position")
						.setDescription("0.0 is flush top, 1.0 flush bottom, 0.5 centred")
						.setMin(0.0).setMax(1.0).setStep(0.01),
					new NodeParameter().setKey("scale").setType(NUMBER).setDefaultValue(0.20)
						.setLabel("Relative Size")
						.setDescription("Overlay width as a fraction of the media width, aspect preserved. 0 keeps the overlay's own pixel size, "
							+ "which makes it look different on every resolution")
						.setMin(0.0).setMax(1.0).setStep(0.01),
					new NodeParameter().setKey("opacity").setType(NUMBER).setDefaultValue(1.0)
						.setLabel("Opacity")
						.setDescription("Scales the overlay's own transparency. 1.0 leaves it as authored")
						.setMin(0.01).setMax(1.0).setStep(0.05),
					new NodeParameter().setKey("videoCodec").setType(STRING).setDefaultValue("libx264")
						.setLabel("Video Codec")
						.setDescription("Encoder for the video path. An overlay changes pixels, so the video stream must be re-encoded; audio is copied"),
					new NodeParameter().setKey("videoCrf").setType(INTEGER).setDefaultValue(23)
						.setLabel("Video Quality (CRF)")
						.setDescription("Constant rate factor. Lower is better quality and a larger file; 0 is lossless")
						.setMin(0).setMax(51),
					new NodeParameter().setKey("videoPreset").setType(STRING).setDefaultValue("medium")
						.setLabel("Encoder Preset")
						.setDescription("Encoder speed/size trade-off, e.g. ultrafast, medium, slow"),
					new NodeParameter().setKey("ffmpegPath").setType(STRING).setDefaultValue("ffmpeg")
						.setLabel("ffmpeg Path")
						.setDescription("The ffmpeg executable, resolved on PATH when left as a bare name. Only the video path needs it"),
					new NodeParameter().setKey("ffprobePath").setType(STRING).setDefaultValue("ffprobe")
						.setLabel("ffprobe Path")
						.setDescription("The ffprobe executable, used to read the video's frame size"),
					new NodeParameter().setKey("timeoutMs").setType(INTEGER).setDefaultValue(600000)
						.setLabel("Timeout (ms)")
						.setDescription("Wall-clock budget per item. A CPU video re-encode is far slower than an image composite")
						.setMin(1)))
				// A video re-encode saturates the machine on its own - ffmpeg is already internally threaded, so running several items at once only makes
				// each of them slower.
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
