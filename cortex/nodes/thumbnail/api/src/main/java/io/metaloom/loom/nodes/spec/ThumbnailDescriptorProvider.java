package io.metaloom.loom.nodes.spec;

import static io.metaloom.loom.nodes.spec.ContentTypes.*;
import static io.metaloom.loom.nodes.spec.NodeCategory.*;
import static io.metaloom.loom.nodes.spec.NodeMode.*;
import static io.metaloom.loom.nodes.spec.ParameterType.*;

import java.util.List;

/**
 * Provides the node descriptor for the thumbnail generator node.
 */
public class ThumbnailDescriptorProvider implements NodeDescriptorProvider {

	private static final List<String> STANDARD_EVENTS = List.of(
		"NODE_STARTED", "NODE_COMPLETED", "NODE_FAILED", "NODE_SKIPPED", "NODE_STATS");

	@Override
	public List<NodeDescriptor> getDescriptors() {
		return List.of(
			new NodeDescriptor()
				.setKind("thumbnail")
				.setName("Thumbnail Generator")
				.setDescription("Generate a thumbnail grid from video or image content.")
				.setIcon("grid_view")
				.setCategory(TRANSFORM)
				.setInputs(List.of(new NodeInput("media", MEDIA_ANY, true)))
				.setOutputs(List.of(
					new NodeOutput("thumbnail_flag", DATA_STRING),
					new NodeOutput("thumbnail_path", DATA_PATH)))
				.setParameters(List.of(
					commonEnabled(), commonProcessIncomplete(), commonRetryFailed(),
					new NodeParameter().setKey("cols").setType(INTEGER).setDefaultValue(6)
						.setLabel("Grid Columns").setMin(1).setMax(20),
					new NodeParameter().setKey("rows").setType(INTEGER).setDefaultValue(1)
						.setLabel("Grid Rows").setMin(1).setMax(20),
					new NodeParameter().setKey("tileSize").setType(INTEGER).setDefaultValue(384)
						.setLabel("Tile Size (px)").setMin(32).setMax(1024)))
				.setDefaultConcurrency(2)
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
