package io.metaloom.loom.nodes.spec;

import static io.metaloom.loom.nodes.spec.ContentTypes.*;
import static io.metaloom.loom.nodes.spec.NodeCategory.*;
import static io.metaloom.loom.nodes.spec.NodeMode.*;
import static io.metaloom.loom.nodes.spec.ParameterType.*;

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
				.setInputs(List.of(new NodeInput("media", MEDIA_ANY, true)))
				.setOutputs(List.of(
					new NodeOutput("blurriness", DATA_NUMBER),
					new NodeOutput("image_width", DATA_INTEGER),
					new NodeOutput("image_height", DATA_INTEGER),
					new NodeOutput("video_width", DATA_INTEGER),
					new NodeOutput("video_height", DATA_INTEGER),
					new NodeOutput("video_fps", DATA_NUMBER),
					new NodeOutput("video_frame_count", DATA_LONG),
					new NodeOutput("quality_flag", DATA_STRING)))
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
