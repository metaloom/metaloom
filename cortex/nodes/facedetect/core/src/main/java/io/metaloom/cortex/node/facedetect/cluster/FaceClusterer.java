package io.metaloom.cortex.node.facedetect.cluster;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.metaloom.cortex.api.node.payload.Detection;

/**
 * Groups an asset's detected faces into proposed subjects, using DBSCAN over cosine distance.
 *
 * <p>
 * Scoped to <strong>one asset</strong>. That answers "who appears in this video", not "who is this person" - the same face in a second video produces
 * a second, unrelated proposal with no memory of the first. Cross-asset identity needs a library-wide pass over the vector index and is a separate
 * piece of work; this one needs no index at all, which is why it can run inside the node.
 * </p>
 *
 * <h2>Noise points become singletons</h2>
 *
 * <p>
 * DBSCAN labels a point that fails the density test as noise, and the textbook answer is to discard it. That is wrong here. A portrait contains
 * exactly one face, which is noise under the default {@code minPoints = 2}, and discarding it would report zero subjects for a photograph of a person.
 * </p>
 *
 * <p>
 * So every noise point becomes a single-member cluster, flagged {@link FaceCluster#noise()}. {@code faceClusterMinimum} governs what DBSCAN
 * <em>merges</em> - how much corroboration it takes to call two faces the same person - not whether an unmatched face is recorded at all. Please do
 * not "fix" this back.
 * </p>
 *
 * <h2>Determinism</h2>
 *
 * <p>
 * {@link FaceCluster#index()} is the {@code cluster_index} half of the database upsert key, so the same input must always produce the same indices or
 * a re-run appends instead of replacing. Input order cannot be that key: the video scanner sorts its faces sharpest-first, and blur ordering is not
 * stable. Clusters are therefore ordered by their content - earliest frame, then leftmost box, then a quantised hash of the centroid - and indexed
 * afterwards.
 * </p>
 */
public final class FaceClusterer {

	/**
	 * Decimal places the centroid is quantised to for the final ordering tie-break.
	 *
	 * <p>
	 * Two clusters that start in the same frame at the same x are already vanishingly unlikely; without quantisation the last resort would be raw float
	 * comparison, where a difference of one ulp between runs flips the order and re-indexes both.
	 * </p>
	 */
	private static final float TIE_BREAK_QUANTISATION = 10_000f;

	private FaceClusterer() {
	}

	/**
	 * Cluster the detections that carry a recognition vector.
	 *
	 * @param detections the asset's detections, in detection-index order; those without an embedding are ignored
	 * @param eps        cosine-distance radius ({@code faceClusterEPS}). Note this is a <em>distance</em>, not a similarity: InspireFace's own pack
	 *                   manifest quotes similarity thresholds, and 0.48 similarity is 0.52 distance
	 * @param minPoints  minimum neighbourhood size for a core point, counting the point itself ({@code faceClusterMinimum})
	 */
	public static FaceClusterResult cluster(List<Detection> detections, float eps, int minPoints) {
		if (detections == null || detections.isEmpty()) {
			return FaceClusterResult.EMPTY;
		}

		// Keep the mapping back to the caller's indices: the node pairs each member with the embedding row it wrote for that detection.
		List<Integer> sourceIndices = new ArrayList<>();
		List<float[]> normalised = new ArrayList<>();
		for (int i = 0; i < detections.size(); i++) {
			Detection detection = detections.get(i);
			if (detection != null && detection.hasEmbedding()) {
				sourceIndices.add(i);
				normalised.add(Vectors.l2normalize(detection.embedding()));
			}
		}

		int skipped = detections.size() - normalised.size();
		if (normalised.isEmpty()) {
			return new FaceClusterResult(List.of(), 0, skipped);
		}

		float[][] vectors = normalised.toArray(float[][]::new);
		// Normalised up front, so cosine distance is 1 - dot and costs no square roots.
		float[][] distances = Dbscan.distanceMatrix(vectors, Vectors::cosineDistance);
		int[] labels = Dbscan.cluster(distances, eps, minPoints);

		List<List<Integer>> groups = groupByLabel(labels);

		List<Draft> drafts = new ArrayList<>();
		for (List<Integer> group : groups) {
			drafts.add(draft(group, vectors, sourceIndices, detections, group.size() == 1 && labels[group.get(0)] == Dbscan.NOISE));
		}

		drafts.sort(Comparator
			.comparingInt((Draft d) -> d.firstFrame)
			.thenComparingInt(d -> d.firstBboxX)
			.thenComparingInt(d -> d.centroidHash)
			.thenComparingInt(d -> d.members.get(0)));

		List<FaceCluster> clusters = new ArrayList<>(drafts.size());
		for (int index = 0; index < drafts.size(); index++) {
			Draft d = drafts.get(index);
			clusters.add(new FaceCluster(index, d.members, d.centroid, d.score, d.confidences, d.noise));
		}
		return new FaceClusterResult(List.copyOf(clusters), normalised.size(), skipped);
	}

	/**
	 * Split the label array into member lists, one per cluster, with every noise point its own group.
	 */
	private static List<List<Integer>> groupByLabel(int[] labels) {
		Map<Integer, List<Integer>> byLabel = new LinkedHashMap<>();
		List<List<Integer>> groups = new ArrayList<>();
		for (int i = 0; i < labels.length; i++) {
			if (labels[i] == Dbscan.NOISE) {
				// Its own subject, seen once. See the class javadoc for why this is not discarded.
				List<Integer> singleton = new ArrayList<>(1);
				singleton.add(i);
				groups.add(singleton);
			} else {
				byLabel.computeIfAbsent(labels[i], k -> new ArrayList<>()).add(i);
			}
		}
		groups.addAll(byLabel.values());
		return groups;
	}

	private static Draft draft(List<Integer> group, float[][] vectors, List<Integer> sourceIndices, List<Detection> detections, boolean noise) {
		float[][] members = new float[group.size()][];
		for (int i = 0; i < group.size(); i++) {
			members[i] = vectors[group.get(i)];
		}
		float[] centroid = Vectors.centroid(members);

		float[] confidences = new float[members.length];
		double sum = 0d;
		for (int i = 0; i < members.length; i++) {
			confidences[i] = Vectors.dot(members[i], centroid);
			sum += confidences[i];
		}
		// Mean similarity to the centroid rather than mean pairwise similarity: the same intuition, O(n) instead of O(n^2), and it is the quantity the
		// review queue wants to rank by. A singleton is trivially at distance 0 from itself, so its cohesion is not a number worth reporting.
		Float score = members.length > 1 ? (float) (sum / members.length) : null;

		List<Integer> mapped = group.stream().map(sourceIndices::get).sorted().toList();

		Draft draft = new Draft();
		draft.members = mapped;
		draft.centroid = centroid;
		draft.score = score;
		draft.confidences = reorder(group, sourceIndices, confidences, mapped);
		draft.noise = noise;
		draft.firstFrame = mapped.stream().mapToInt(i -> detections.get(i).frameIndex()).min().orElse(0);
		draft.firstBboxX = mapped.stream()
			.map(i -> detections.get(i).boundingBox())
			.filter(b -> b != null)
			.mapToInt(b -> b.x())
			.min()
			.orElse(0);
		draft.centroidHash = quantisedHash(centroid);
		return draft;
	}

	/**
	 * Put the per-member confidences into the same order as the sorted member list, so the two stay positionally aligned.
	 */
	private static float[] reorder(List<Integer> group, List<Integer> sourceIndices, float[] confidences, List<Integer> sortedMembers) {
		Map<Integer, Float> bySource = new LinkedHashMap<>();
		for (int i = 0; i < group.size(); i++) {
			bySource.put(sourceIndices.get(group.get(i)), confidences[i]);
		}
		float[] out = new float[sortedMembers.size()];
		for (int i = 0; i < sortedMembers.size(); i++) {
			out[i] = bySource.get(sortedMembers.get(i));
		}
		return out;
	}

	/**
	 * A hash of the centroid rounded to a fixed precision, so float noise between runs cannot flip the ordering tie-break.
	 */
	private static int quantisedHash(float[] centroid) {
		int[] quantised = new int[centroid.length];
		for (int i = 0; i < centroid.length; i++) {
			quantised[i] = Math.round(centroid[i] * TIE_BREAK_QUANTISATION);
		}
		return Arrays.hashCode(quantised);
	}

	/** Mutable working copy of a cluster, before ordering decides its index. */
	private static final class Draft {
		List<Integer> members;
		float[] centroid;
		Float score;
		float[] confidences;
		boolean noise;
		int firstFrame;
		int firstBboxX;
		int centroidHash;
	}

}
