package io.metaloom.loom.nodes.spec;

import static io.metaloom.loom.nodes.spec.ContentTypeRegistry.*;
import static io.metaloom.loom.nodes.spec.NodeCategory.ANALYSIS;
import static io.metaloom.loom.nodes.spec.NodeMode.PARALLEL;
import static io.metaloom.loom.nodes.spec.ParameterType.BOOLEAN;
import static io.metaloom.loom.nodes.spec.ParameterType.ENUM_SET;
import static io.metaloom.loom.nodes.spec.ParameterType.INTEGER;
import static io.metaloom.loom.nodes.spec.ParameterType.NUMBER;
import static io.metaloom.loom.nodes.spec.ParameterType.STRING;
import static io.metaloom.loom.nodes.spec.PortSpec.many;
import static io.metaloom.loom.nodes.spec.PortSpec.one;

import java.util.List;

/**
 * Provides the node descriptor for the scene-layout node, which relates detected objects to one
 * another using a depth map.
 */
public class SceneLayoutDescriptorProvider implements NodeDescriptorProvider {

	private static final List<String> STANDARD_EVENTS = List.of(
		"NODE_STARTED", "NODE_COMPLETED", "NODE_FAILED", "NODE_SKIPPED", "NODE_STATS");

	@Override
	public List<NodeDescriptor> getDescriptors() {
		return List.of(
			new NodeDescriptor()
				.setNodeId("scene-layout")
				.setName("Scene Layout")
				.setDescription(
					"Relate detected objects to one another using a depth map: foreground/background bands plus pairwise relations such as "
						+ "in front of, behind, occludes and next to. Requires an upstream depthmap node on the same worker.")
				.setIcon("schema")
				.setCategory(ANALYSIS)
				.setInputPorts(List.of(
					one("depth", STRUCT_DEPTHMAP)
						.describedAs("Depth Metadata", "Depth metadata from an upstream depthmap node sharing this node's affinity group"),
					many("detections", DETECTION_ANY)
						.describedAs("Detections", "The boxes to place in the scene - faces, objects or plain regions")))
				.setOutputPorts(List.of(
					one("result", STRUCT_SCENE_LAYOUT)
						.describedAs("Scene Layout", "Depth band per object plus pairwise relations such as in front of, occludes and next to"),
					one("object_count", SCALAR_INTEGER)
						.describedAs("Object Count", "How many boxes were placed in the scene"),
					one("relation_count", SCALAR_INTEGER)
						.describedAs("Relation Count", "How many pairwise relations were emitted")))
				.setParameters(List.of(
					commonEnabled(), commonProcessIncomplete(), commonRetryFailed(),
					new NodeParameter().setKey("allowLoomFallback").setType(BOOLEAN).setDefaultValue(true)
						.setLabel("Loom Fallback")
						.setDescription("Read detections back from Loom when no upstream node supplied them"),
					new NodeParameter().setKey("coreInset").setType(NUMBER).setDefaultValue(0.25)
						.setLabel("Core Inset")
						.setDescription("Fraction inset per side before sampling depth; keeps background corners out of the statistic")
						.setMin(0.0).setMax(0.49).setStep(0.05),
					new NodeParameter().setKey("depthZThreshold").setType(NUMBER).setDefaultValue(1.0)
						.setLabel("Depth Threshold")
						.setDescription("Depth separation, in units of the objects' own spread, before an ordering is asserted")
						.setMin(0.0).setStep(0.1),
					new NodeParameter().setKey("occlusionMinOverlap").setType(NUMBER).setDefaultValue(0.05)
						.setLabel("Min Occlusion Overlap")
						.setDescription("Overlap of the smaller box needed before occlusion is reported")
						.setMin(0.0).setMax(1.0).setStep(0.05),
					new NodeParameter().setKey("containmentRatio").setType(NUMBER).setDefaultValue(0.85)
						.setLabel("Containment Ratio")
						.setDescription("How much of a box must lie inside another to call it contained")
						.setMin(0.0).setMax(1.0).setStep(0.05),
					new NodeParameter().setKey("nextToMaxGap").setType(NUMBER).setDefaultValue(0.5)
						.setLabel("Next-To Max Gap")
						.setDescription("Gap over mean box size below which two same-depth objects count as adjacent")
						.setMin(0.0).setStep(0.1),
					new NodeParameter().setKey("foregroundQuantile").setType(NUMBER).setDefaultValue(0.66)
						.setLabel("Foreground Quantile")
						.setDescription("Scene depth quantile at or above which an object is foreground")
						.setMin(0.0).setMax(1.0).setStep(0.01),
					new NodeParameter().setKey("backgroundQuantile").setType(NUMBER).setDefaultValue(0.33)
						.setLabel("Background Quantile")
						.setDescription("Scene depth quantile at or below which an object is background")
						.setMin(0.0).setMax(1.0).setStep(0.01),
					new NodeParameter().setKey("maxObjects").setType(INTEGER).setDefaultValue(40)
						.setLabel("Max Objects").setDescription("Largest-first cap; relations grow with the square of this").setMin(1),
					new NodeParameter().setKey("maxRelations").setType(INTEGER).setDefaultValue(200)
						.setLabel("Max Relations").setDescription("Cap on emitted relations, strongest kept first").setMin(1),
					new NodeParameter().setKey("emitPhrases").setType(BOOLEAN).setDefaultValue(true)
						.setLabel("Emit Phrases")
						.setDescription("Include readable sentences alongside the structured relations")))
				// No model and no device to contend for - only CPU, so this can run wider than the
				// model-backed nodes.
				.setDefaultConcurrency(4)
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
