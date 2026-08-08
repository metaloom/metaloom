package io.metaloom.cortex.node.facedetect.cluster;

import java.util.List;

/**
 * The outcome of clustering one asset's faces.
 *
 * @param clusters      the proposed subjects, in {@link FaceCluster#index()} order
 * @param embeddedCount how many of the input detections carried a usable vector and were therefore clusterable
 * @param skippedCount  how many were skipped for having no vector - they still exist as detections, they just cannot be attributed to anyone
 */
public record FaceClusterResult(List<FaceCluster> clusters, int embeddedCount, int skippedCount) {

	public static final FaceClusterResult EMPTY = new FaceClusterResult(List.of(), 0, 0);

	/**
	 * How many distinct subjects were found.
	 *
	 * <p>
	 * This is what the node's {@code face_count} port reports. It is deliberately not the detection count: forty boxes of two people in a video is two.
	 * </p>
	 */
	public int count() {
		return clusters.size();
	}

}
