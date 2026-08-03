package io.metaloom.loom.nodes.spec;

import static io.metaloom.loom.nodes.spec.ContentTypeRegistry.*;
import static io.metaloom.loom.nodes.spec.NodeCategory.ANALYSIS;
import static io.metaloom.loom.nodes.spec.NodeMode.PARALLEL;
import static io.metaloom.loom.nodes.spec.ParameterType.BOOLEAN;
import static io.metaloom.loom.nodes.spec.ParameterType.ENUM;
import static io.metaloom.loom.nodes.spec.ParameterType.INTEGER;
import static io.metaloom.loom.nodes.spec.ParameterType.STRING;
import static io.metaloom.loom.nodes.spec.PortSpec.one;

import java.util.List;

/**
 * Provides the node descriptor for the monocular depth-estimation node.
 */
public class DepthmapDescriptorProvider implements NodeDescriptorProvider {

	private static final List<String> STANDARD_EVENTS = List.of(
		"NODE_STARTED", "NODE_COMPLETED", "NODE_FAILED", "NODE_SKIPPED", "NODE_STATS");

	@Override
	public List<NodeDescriptor> getDescriptors() {
		return List.of(
			new NodeDescriptor()
				.setNodeId("depthmap")
				.setName("Depth Map")
				.setDescription(
					"Estimate a per-pixel depth map from a single image. The map is written to a local cache as a 16-bit PNG where the brightest "
						+ "pixels are nearest the camera.")
				.setIcon("layers")
				.setCategory(ANALYSIS)
				.setInputPorts(List.of(
					one("media", MEDIA_IMAGE)
						.describedAs("Image", "The image to estimate per-pixel depth for")))
				.setOutputPorts(List.of(
					one("meta", STRUCT_DEPTHMAP)
						.describedAs("Depth Metadata", "Mode, model, map dimensions and the value range needed to interpret the map"),
					one("map", ARTIFACT_IMAGE)
						.describedAs("Depth Map", "The 16-bit PNG in the worker's local cache; the brightest pixels are nearest the camera"),
					one("flag", SCALAR_STRING)
						.describedAs("Flag", "Processing marker recording how this node finished for the item")))
				.setParameters(List.of(
					commonEnabled(), commonProcessIncomplete(), commonRetryFailed(),
					new NodeParameter().setKey("depthHost").setType(STRING).setDefaultValue("localhost")
						.setLabel("Sidecar Host").setDescription("Host of the /v1/depth sidecar"),
					new NodeParameter().setKey("depthPort").setType(INTEGER).setDefaultValue(9120)
						.setLabel("Sidecar Port").setDescription("Port of the /v1/depth sidecar").setMin(1),
					new NodeParameter().setKey("mode").setType(ENUM)
						.setValues(List.of("RELATIVE", "METRIC"))
						.setDefaultValue("RELATIVE")
						.setLabel("Depth Mode")
						.setDescription("RELATIVE orders objects front-to-back; METRIC additionally reports a range in metres"),
					new NodeParameter().setKey("model").setType(STRING)
						.setLabel("Model Override")
						.setDescription("Checkpoint to use instead of the sidecar's default for the selected mode"),
					new NodeParameter().setKey("maxDim").setType(INTEGER).setDefaultValue(1024)
						.setLabel("Max Dimension")
						.setDescription("Longest side sent to the sidecar; also the size of the produced map").setMin(1),
					new NodeParameter().setKey("timeoutMs").setType(INTEGER).setDefaultValue(120000)
						.setLabel("Timeout (ms)").setDescription("HTTP request timeout").setMin(1)))
				// The sidecar holds one model on one device, so parallel calls from a single worker
				// only queue behind each other and inflate memory.
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
