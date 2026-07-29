package io.metaloom.loom.nodes.spec;

import static io.metaloom.loom.nodes.spec.ContentTypeRegistry.*;
import static io.metaloom.loom.nodes.spec.NodeCategory.*;
import static io.metaloom.loom.nodes.spec.NodeMode.*;
import static io.metaloom.loom.nodes.spec.ParameterType.*;
import static io.metaloom.loom.nodes.spec.PortSpec.one;
import static io.metaloom.loom.nodes.spec.PortSpec.optionalOne;

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
				.setInputPorts(List.of(
					one("media", MEDIA_ANY)
						.describedAs("Media", "The image or video to render a preview from"),
					// Declared so the node can stop hard-coding an upstream node id for it. Leave it
					// unwired and a half-written file is rendered anyway, which is the old behaviour.
					optionalOne("is_complete", SCALAR_BOOLEAN)
						.describedAs("Is Complete", "Whether the file is whole; an incomplete one is skipped unless processIncomplete is set")))
				.setOutputPorts(List.of(
					one("thumbnail", ARTIFACT_IMAGE)
						.describedAs("Thumbnail", "The rendered preview grid in the worker's local cache; wire it into a sink to keep it"),
					one("flag", SCALAR_STRING)
						.describedAs("Flag", "Processing marker recording how this node finished for the item")))
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
