package io.metaloom.loom.nodes.spec;

import static io.metaloom.loom.nodes.spec.ContentTypeRegistry.*;
import static io.metaloom.loom.nodes.spec.NodeCategory.*;
import static io.metaloom.loom.nodes.spec.NodeMode.*;
import static io.metaloom.loom.nodes.spec.ParameterType.*;
import static io.metaloom.loom.nodes.spec.PortGroup.xor;
import static io.metaloom.loom.nodes.spec.PortSpec.many;
import static io.metaloom.loom.nodes.spec.PortSpec.one;

import java.util.List;

/**
 * Provides node descriptors for face detection and face description nodes.
 */
public class FacedetectDescriptorProvider implements NodeDescriptorProvider {

	private static final List<String> STANDARD_EVENTS = List.of(
		"NODE_STARTED", "NODE_COMPLETED", "NODE_FAILED", "NODE_SKIPPED", "NODE_STATS");

	@Override
	public List<NodeDescriptor> getDescriptors() {
		return List.of(
			new NodeDescriptor()
				.setKind("facedetect")
				.setName("Face Detection")
				.setDescription("Detect and cluster faces in images and video frames.")
				.setIcon("face")
				.setCategory(ANALYSIS)
				.setInputPorts(List.of(
					one("image", MEDIA_IMAGE).inGroup("media_alt")
						.describedAs("Image", "A still image to search for faces"),
					one("video", MEDIA_VIDEO).inGroup("media_alt")
						.describedAs("Video", "A video whose frames are sampled and searched")))
				.setInputGroups(List.of(
					xor("media_alt", "Media")))
				.setOutputPorts(List.of(
					many("detections", DETECTION_FACE)
						.describedAs("Face Detections", "One element per detected face, so a downstream node can run once per face rather than once per file"),
					one("face_count", SCALAR_INTEGER)
						.describedAs("Face Count", "How many distinct faces survived clustering"),
					one("flag", SCALAR_STRING)
						.describedAs("Flag", "Processing marker recording how this node finished for the item")))
				.setParameters(List.of(
					commonEnabled(), commonProcessIncomplete(), commonRetryFailed(),
					new NodeParameter().setKey("videoChopRate").setType(INTEGER).setDefaultValue(5)
						.setLabel("Video Chop Rate").setDescription("Process every Nth video frame").setMin(1),
					new NodeParameter().setKey("faceClusterMinimum").setType(INTEGER).setDefaultValue(2)
						.setLabel("Min Cluster Size").setDescription("Minimum detections to form a cluster"),
					new NodeParameter().setKey("faceClusterEPS").setType(NUMBER).setDefaultValue(0.6)
						.setLabel("Cluster Radius").setDescription("DBSCAN cluster radius threshold")
						.setMin(0.0).setMax(2.0).setStep(0.05),
					new NodeParameter().setKey("videoScaleSize").setType(INTEGER).setDefaultValue(384)
						.setLabel("Scale Size (px)").setDescription("Rescale video frames to this size"),
					new NodeParameter().setKey("minFaceHeightFactor").setType(NUMBER).setDefaultValue(0.05)
						.setLabel("Min Face Height Factor").setMin(0.0).setMax(1.0),
					new NodeParameter().setKey("inspirefacePackPath").setType(STRING).setDefaultValue("packs/Pikachu")
						.setLabel("Model Pack Path"),
					new NodeParameter().setKey("capabilities").setType(ENUM_SET)
						.setValues(List.of("INSPIREFACE", "DLIB"))
						.setDefaultValue(List.of("INSPIREFACE"))
						.setLabel("Backends")))
				.setDefaultConcurrency(2)
				.setDefaultMode(PARALLEL)
				.setEvents(STANDARD_EVENTS),

			new NodeDescriptor()
				.setKind("facedescription")
				.setName("Face Description")
				.setDescription("Generate textual descriptions of detected faces.")
				.setIcon("face_retouching_natural")
				.setCategory(ANALYSIS)
				.setInputPorts(List.of(
					many("detections", DETECTION_FACE)
						.describedAs("Face Detections", "The faces to describe, one element each - typically straight from a facedetect node")))
				.setOutputPorts(List.of(
					many("descriptions", TEXT_PLAIN)
						.describedAs("Descriptions", "One description per incoming face, in the same order as the detections")))
				.setParameters(List.of(commonEnabled(), commonProcessIncomplete(), commonRetryFailed()))
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
