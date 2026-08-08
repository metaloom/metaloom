package io.metaloom.cortex.node.facedetect.cluster;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

import io.metaloom.cortex.api.node.payload.BoundingBox;
import io.metaloom.cortex.api.node.payload.Detection;

/**
 * Clustering behaviour, with synthetic vectors - no model pack, no natives, no database.
 */
public class FaceClustererTest {

	private static final float EPS = 0.6f;

	private static final int MIN_POINTS = 2;

	/**
	 * Three well-separated groups plus one face that matches nobody: four subjects, one of them a singleton.
	 */
	@Test
	public void testSeparatedBlobsBecomeSeparateClusters() {
		List<Detection> detections = new ArrayList<>();
		detections.addAll(blob(0, 0, 3));
		detections.addAll(blob(1, 10, 3));
		detections.addAll(blob(2, 20, 3));
		detections.add(detection(30, direction(7)));

		FaceClusterResult result = FaceClusterer.cluster(detections, EPS, MIN_POINTS);

		assertThat(result.count()).as("three groups plus the outlier").isEqualTo(4);
		assertThat(result.embeddedCount()).isEqualTo(10);
		assertThat(result.skippedCount()).isZero();
		assertThat(result.clusters()).filteredOn(FaceCluster::noise).hasSize(1);
		assertThat(result.clusters()).filteredOn(c -> !c.noise()).allSatisfy(c -> assertThat(c.size()).isEqualTo(3));
	}

	/**
	 * A single face is noise under minPoints=2, and must still be reported as one subject.
	 *
	 * <p>
	 * This is the case that makes discarding noise wrong: a portrait would otherwise report zero people.
	 * </p>
	 */
	@Test
	public void testLoneFaceBecomesASingletonCluster() {
		FaceClusterResult result = FaceClusterer.cluster(List.of(detection(0, direction(0))), EPS, MIN_POINTS);

		assertThat(result.count()).isEqualTo(1);
		FaceCluster cluster = result.clusters().get(0);
		assertThat(cluster.size()).isEqualTo(1);
		assertThat(cluster.noise()).isTrue();
		assertThat(cluster.score()).as("a singleton has no cohesion to report").isNull();
		assertThat(cluster.centroid()).isNotNull();
	}

	/**
	 * The cluster index is the upsert key, so it must not depend on the order the scanner happened to return faces in - which is sharpest-first, and
	 * not stable between runs.
	 */
	@Test
	public void testClusterIndexIsStableUnderInputReordering() {
		List<Detection> detections = new ArrayList<>();
		detections.addAll(blob(0, 0, 3));
		detections.addAll(blob(1, 10, 3));
		detections.addAll(blob(2, 20, 3));

		List<String> baseline = signature(FaceClusterer.cluster(detections, EPS, MIN_POINTS), detections);

		for (int seed = 0; seed < 5; seed++) {
			List<Detection> shuffled = new ArrayList<>(detections);
			Collections.shuffle(shuffled, new Random(seed));

			List<String> actual = signature(FaceClusterer.cluster(shuffled, EPS, MIN_POINTS), shuffled);

			assertThat(actual).as("shuffle seed %s must yield the same indexed partition", seed).isEqualTo(baseline);
		}
	}

	/**
	 * A radius wide enough to span two groups merges them; that is the knob doing its job, and it is worth pinning that it actually reaches the
	 * algorithm rather than being validated and ignored, which is what it did before.
	 */
	@Test
	public void testEpsControlsMerging() {
		List<Detection> detections = new ArrayList<>();
		detections.addAll(blob(0, 0, 3));
		detections.addAll(blob(1, 10, 3));

		assertThat(FaceClusterer.cluster(detections, 0.2f, MIN_POINTS).count()).as("a tight radius keeps them apart").isEqualTo(2);
		assertThat(FaceClusterer.cluster(detections, 2.0f, MIN_POINTS).count()).as("a radius spanning everything merges them").isEqualTo(1);
	}

	/**
	 * Raising the required neighbourhood size turns a group into a set of singletons rather than removing it.
	 */
	@Test
	public void testMinPointsControlsDensity() {
		List<Detection> detections = new ArrayList<>(blob(0, 0, 3));

		assertThat(FaceClusterer.cluster(detections, EPS, 2).count()).as("three mutually close faces are one subject").isEqualTo(1);

		FaceClusterResult strict = FaceClusterer.cluster(detections, EPS, 5);
		assertThat(strict.count()).as("too sparse to merge, so each is its own subject").isEqualTo(3);
		assertThat(strict.clusters()).allMatch(FaceCluster::noise);
	}

	/**
	 * Detections without a vector are not clusterable, but they are still real detections and must not vanish silently.
	 */
	@Test
	public void testDetectionsWithoutEmbeddingsAreCounted() {
		List<Detection> detections = new ArrayList<>(blob(0, 0, 2));
		detections.add(new Detection(new BoundingBox(0, 0, 10, 10), 0, 0.9f, "face", null));

		FaceClusterResult result = FaceClusterer.cluster(detections, EPS, MIN_POINTS);

		assertThat(result.embeddedCount()).isEqualTo(2);
		assertThat(result.skippedCount()).isEqualTo(1);
		assertThat(result.count()).isEqualTo(1);
	}

	@Test
	public void testEmptyInputProducesNoClusters() {
		assertThat(FaceClusterer.cluster(List.of(), EPS, MIN_POINTS).count()).isZero();
		assertThat(FaceClusterer.cluster(null, EPS, MIN_POINTS).count()).isZero();
	}

	/**
	 * Members index back into the caller's detection list, which is what lets the node pair each member with the embedding row it wrote.
	 */
	@Test
	public void testMembersIndexIntoTheInputList() {
		List<Detection> detections = new ArrayList<>();
		detections.addAll(blob(0, 0, 2));
		detections.addAll(blob(1, 10, 2));

		FaceClusterResult result = FaceClusterer.cluster(detections, EPS, MIN_POINTS);

		List<Integer> all = result.clusters().stream().flatMap(c -> c.members().stream()).sorted().toList();
		assertThat(all).as("every detection is accounted for exactly once").containsExactly(0, 1, 2, 3);
		assertThat(result.clusters()).allSatisfy(c -> assertThat(c.confidences()).hasSize(c.size()));
	}

	/**
	 * Cohesion is the mean similarity of the members to the centroid, so an identical set scores 1.
	 */
	@Test
	public void testScoreReportsCohesion() {
		float[] vector = direction(0);
		List<Detection> detections = List.of(detection(0, vector), detection(1, vector.clone()));

		FaceCluster cluster = FaceClusterer.cluster(detections, EPS, MIN_POINTS).clusters().get(0);

		assertThat(cluster.score()).isNotNull();
		assertThat(cluster.score()).isCloseTo(1f, org.assertj.core.data.Offset.offset(0.0001f));
	}

	// ---------------------------------------------------------------------------------------------

	/**
	 * A tight group of near-identical vectors around one direction, positioned so its bounding boxes order predictably.
	 */
	private static List<Detection> blob(int direction, int x, int count) {
		List<Detection> detections = new ArrayList<>(count);
		float[] base = direction(direction);
		for (int i = 0; i < count; i++) {
			float[] vector = base.clone();
			// A nudge far smaller than eps, so the members stay mutually reachable.
			vector[(direction + 1) % vector.length] += 0.01f * (i + 1);
			detections.add(detection(x + i, vector));
		}
		return detections;
	}

	/** A unit vector pointing along one axis, so two different directions are orthogonal and therefore at cosine distance 1. */
	private static float[] direction(int axis) {
		float[] vector = new float[16];
		vector[axis] = 1f;
		return vector;
	}

	private static Detection detection(int x, float[] embedding) {
		return new Detection(new BoundingBox(x, 0, 10, 10), 0, 0.9f, "face", embedding);
	}

	/**
	 * Describe the partition in terms that survive reordering: for each cluster index, the set of detections it holds, identified by their bounding
	 * box rather than by their position in the input list.
	 */
	private static List<String> signature(FaceClusterResult result, List<Detection> detections) {
		List<String> signature = new ArrayList<>();
		for (FaceCluster cluster : result.clusters()) {
			List<Integer> boxes = cluster.members().stream()
				.map(i -> detections.get(i).boundingBox().x())
				.sorted()
				.toList();
			signature.add(cluster.index() + "=" + boxes);
		}
		return signature;
	}

}
