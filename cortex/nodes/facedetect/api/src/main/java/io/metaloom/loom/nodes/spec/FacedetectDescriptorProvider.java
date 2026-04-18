package io.metaloom.loom.nodes.spec;

import static io.metaloom.loom.nodes.spec.ContentTypes.*;
import static io.metaloom.loom.nodes.spec.NodeCategory.*;
import static io.metaloom.loom.nodes.spec.NodeMode.*;
import static io.metaloom.loom.nodes.spec.ParameterType.*;

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
				.setInputs(List.of(
					new NodeInput("media", MEDIA_IMAGE, true),
					new NodeInput("media", MEDIA_VIDEO, true)))
				.setOutputs(List.of(
					new NodeOutput("face_count", DATA_INTEGER),
					new NodeOutput("facedetect_flag", DATA_STRING)))
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
				.setInputs(List.of(new NodeInput("facedetection", DATA_FACEDETECTION, true)))
				.setOutputs(List.of(new NodeOutput("face_description", DATA_TEXT)))
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
