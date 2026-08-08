package io.metaloom.cortex.node.facedetect.cluster;

import java.util.List;

/**
 * One proposed subject: the detections believed to be the same person, with the shape of that belief.
 *
 * @param index      deterministic ordinal within the asset, used as the {@code cluster_index} upsert key. Derived from the cluster's content rather
 *                   than from input order, because the video scanner returns faces sharpest-first and that order is not stable between runs
 * @param members    indices into the detection list the clusterer was given, ascending
 * @param centroid   unit-length mean of the member vectors, or the member vector itself for a singleton
 * @param score      mean cosine similarity of the members to {@code centroid}, or {@code null} for a singleton, which has nothing to cohere with
 * @param confidences per-member cosine similarity to {@code centroid}, positionally aligned to {@code members}
 * @param noise      whether DBSCAN classified this as an outlier rather than a dense group. Kept as a cluster anyway - see {@link FaceClusterer}
 */
public record FaceCluster(int index, List<Integer> members, float[] centroid, Float score, float[] confidences, boolean noise) {

	/** How many detections this subject was seen in. */
	public int size() {
		return members.size();
	}

}
