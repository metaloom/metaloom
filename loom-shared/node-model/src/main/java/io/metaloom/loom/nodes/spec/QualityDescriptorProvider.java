package io.metaloom.loom.nodes.spec;

import static io.metaloom.loom.nodes.spec.ContentTypeRegistry.*;
import static io.metaloom.loom.nodes.spec.NodeCategory.*;
import static io.metaloom.loom.nodes.spec.NodeMode.*;
import static io.metaloom.loom.nodes.spec.ParameterType.*;
import static io.metaloom.loom.nodes.spec.PortSpec.one;

import java.util.List;

/**
 * Provides the node descriptor for the quality analysis node.
 */
public class QualityDescriptorProvider implements NodeDescriptorProvider {

	private static final List<String> STANDARD_EVENTS = List.of(
		"NODE_STARTED", "NODE_COMPLETED", "NODE_FAILED", "NODE_SKIPPED", "NODE_STATS");

	@Override
	public List<NodeDescriptor> getDescriptors() {
		return List.of(
			new NodeDescriptor()
				.setKind("quality")
				.setName("Quality Analysis")
				.setDescription("Analyze media quality: blurriness, resolution, bitrate.")
				.setIcon("high_quality")
				.setCategory(ANALYSIS)
				.setInputPorts(List.of(
					one("media", MEDIA_ANY)
						.describedAs("Media", "The image or video to measure")))
				.setOutputPorts(List.of(
					one("metrics", STRUCT_QUALITY)
						.describedAs("Quality Metrics", "The whole measurement bag - resolution, blurriness and bitrates - for a filter to threshold on"),
					one("blurriness", SCALAR_NUMBER)
						.describedAs("Blurriness", "Variance of the Laplacian; the lower the value the blurrier the frame"),
					one("width", SCALAR_INTEGER)
						.describedAs("Width", "Frame width in pixels"),
					one("height", SCALAR_INTEGER)
						.describedAs("Height", "Frame height in pixels"),
					one("fps", SCALAR_NUMBER)
						.describedAs("Frame Rate", "Frames per second; zero for a still image"),
					one("frame_count", SCALAR_INTEGER)
						.describedAs("Frame Count", "Total number of frames; one for a still image"),
					one("flag", SCALAR_STRING)
						.describedAs("Flag", "Processing marker recording how this node finished for the item")))
				.setParameters(List.of(
					commonEnabled(), commonProcessIncomplete(), commonRetryFailed(),
					new NodeParameter().setKey("checkBlurriness").setType(BOOLEAN).setDefaultValue(true)
						.setLabel("Check Blurriness"),
					new NodeParameter().setKey("checkResolution").setType(BOOLEAN).setDefaultValue(true)
						.setLabel("Check Resolution"),
					new NodeParameter().setKey("checkVideoBitrate").setType(BOOLEAN).setDefaultValue(true)
						.setLabel("Check Video Bitrate"),
					new NodeParameter().setKey("checkAudioBitrate").setType(BOOLEAN).setDefaultValue(true)
						.setLabel("Check Audio Bitrate")))
				.setDefaultConcurrency(4)
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
