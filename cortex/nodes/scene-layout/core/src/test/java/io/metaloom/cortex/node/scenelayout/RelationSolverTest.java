package io.metaloom.cortex.node.scenelayout;

import static io.metaloom.cortex.node.scenelayout.SceneLayoutFixtures.object;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

/**
 * The primary correctness test for this node.
 *
 * <p>
 * {@link RelationSolver} is pure, so every case here has one exact right answer: objects are
 * placed by hand at known depths with known spreads, and the asserted predicate follows from
 * arithmetic rather than from a model's opinion. No GPU, no file, no mock.
 * </p>
 */
class RelationSolverTest {

	private final SceneLayoutNodeOptions options = new SceneLayoutNodeOptions();
	private final RelationSolver solver = new RelationSolver(options);

	private Optional<SpatialRelation> find(List<SpatialRelation> relations, String subject, RelationPredicate predicate, String object) {
		return relations.stream()
			.filter(r -> r.subjectId().equals(subject) && r.predicate() == predicate && r.objectId().equals(object))
			.findFirst();
	}

	private void assertHas(List<SpatialRelation> relations, String subject, RelationPredicate predicate, String object) {
		assertThat(find(relations, subject, predicate, object))
			.as("expected %s %s %s in %s", subject, predicate, object, describe(relations))
			.isPresent();
	}

	private void assertHasNot(List<SpatialRelation> relations, String subject, RelationPredicate predicate, String object) {
		assertThat(find(relations, subject, predicate, object))
			.as("did not expect %s %s %s in %s", subject, predicate, object, describe(relations))
			.isEmpty();
	}

	private static List<String> describe(List<SpatialRelation> relations) {
		return relations.stream().map(r -> r.subjectId() + " " + r.predicate() + " " + r.objectId()).toList();
	}

	@Test
	void testClearDepthSeparationYieldsFrontAndBehind() {
		// Nearness 0.8 vs 0.3 with tight spreads: z is far past the threshold either way.
		List<SpatialRelation> relations = solver.solve(List.of(
			object("a", new BoxF(0, 0, 50, 50), 0.8, 0.04),
			object("b", new BoxF(200, 0, 50, 50), 0.3, 0.04)));

		assertHas(relations, "a", RelationPredicate.IN_FRONT_OF, "b");
		assertHas(relations, "b", RelationPredicate.BEHIND, "a");
		assertHasNot(relations, "a", RelationPredicate.SAME_DEPTH, "b");
	}

	@Test
	void testEqualDepthYieldsSameDepthEmittedOnce() {
		List<SpatialRelation> relations = solver.solve(List.of(
			object("a", new BoxF(0, 0, 50, 50), 0.5, 0.04),
			object("b", new BoxF(200, 0, 50, 50), 0.5, 0.04)));

		// Symmetric predicates are emitted once per unordered pair - saying it twice is noise.
		assertThat(relations.stream().filter(r -> r.predicate() == RelationPredicate.SAME_DEPTH)).hasSize(1);
		assertHas(relations, "a", RelationPredicate.SAME_DEPTH, "b");
	}

	@Test
	void testNoisyDepthSuppressesAMarginalOrdering() {
		// The same 0.1 nearness gap as a confident pair would have, but each object's own depth
		// varies by 0.4 - so the gap is well inside the noise and no ordering may be asserted.
		List<SpatialRelation> relations = solver.solve(List.of(
			object("a", new BoxF(0, 0, 50, 50), 0.55, 0.4),
			object("b", new BoxF(200, 0, 50, 50), 0.45, 0.4)));

		assertHasNot(relations, "a", RelationPredicate.IN_FRONT_OF, "b");
		assertHas(relations, "a", RelationPredicate.SAME_DEPTH, "b");
	}

	@Test
	void testSameGapWithTightSpreadDoesYieldAnOrdering() {
		// Control for the test above: identical 0.1 gap, but measured confidently.
		List<SpatialRelation> relations = solver.solve(List.of(
			object("a", new BoxF(0, 0, 50, 50), 0.55, 0.02),
			object("b", new BoxF(200, 0, 50, 50), 0.45, 0.02)));

		assertHas(relations, "a", RelationPredicate.IN_FRONT_OF, "b");
	}

	@Test
	void testOverlapPlusDepthGapYieldsOcclusion() {
		List<SpatialRelation> relations = solver.solve(List.of(
			object("a", new BoxF(0, 0, 100, 100), 0.8, 0.04),
			object("b", new BoxF(50, 0, 100, 100), 0.3, 0.04)));

		assertHas(relations, "a", RelationPredicate.OCCLUDES, "b");
		assertHas(relations, "b", RelationPredicate.OCCLUDED_BY, "a");
	}

	@Test
	void testOverlapAtSameDepthIsNotOcclusion() {
		// The classic 2D-only mistake this node exists to avoid: overlapping boxes at the same
		// depth are adjacent, not occluding.
		List<SpatialRelation> relations = solver.solve(List.of(
			object("a", new BoxF(0, 0, 100, 100), 0.5, 0.04),
			object("b", new BoxF(50, 0, 100, 100), 0.5, 0.04)));

		assertHasNot(relations, "a", RelationPredicate.OCCLUDES, "b");
		assertHasNot(relations, "b", RelationPredicate.OCCLUDED_BY, "a");
	}

	@Test
	void testContainment() {
		List<SpatialRelation> relations = solver.solve(List.of(
			object("outer", new BoxF(0, 0, 200, 200), 0.5, 0.04),
			object("inner", new BoxF(50, 50, 50, 50), 0.5, 0.04)));

		assertHas(relations, "outer", RelationPredicate.CONTAINS, "inner");
		assertHasNot(relations, "inner", RelationPredicate.CONTAINS, "outer");
	}

	@Test
	void testLeftAndRight() {
		List<SpatialRelation> relations = solver.solve(List.of(
			object("a", new BoxF(0, 0, 50, 50), 0.5, 0.04),
			object("b", new BoxF(300, 0, 50, 50), 0.5, 0.04)));

		assertHas(relations, "a", RelationPredicate.LEFT_OF, "b");
		assertHas(relations, "b", RelationPredicate.RIGHT_OF, "a");
	}

	@Test
	void testAboveAndBelowOnlyWhenHorizontallyOverlapping() {
		// Stacked vertically and overlapping in x, so left/right is meaningless and above/below is
		// the only sensible lateral claim.
		List<SpatialRelation> relations = solver.solve(List.of(
			object("top", new BoxF(0, 0, 50, 50), 0.5, 0.04),
			object("bottom", new BoxF(0, 300, 50, 50), 0.5, 0.04)));

		assertHas(relations, "top", RelationPredicate.ABOVE, "bottom");
		assertHas(relations, "bottom", RelationPredicate.BELOW, "top");
		assertHasNot(relations, "top", RelationPredicate.LEFT_OF, "bottom");
	}

	@Test
	void testNextToRequiresProximityAndEqualDepth() {
		List<SpatialRelation> close = solver.solve(List.of(
			object("a", new BoxF(0, 0, 50, 50), 0.5, 0.04),
			object("b", new BoxF(60, 0, 50, 50), 0.5, 0.04)));
		assertHas(close, "a", RelationPredicate.NEXT_TO, "b");
		// Symmetric, so emitted once.
		assertThat(close.stream().filter(r -> r.predicate() == RelationPredicate.NEXT_TO)).hasSize(1);

		List<SpatialRelation> far = solver.solve(List.of(
			object("a", new BoxF(0, 0, 50, 50), 0.5, 0.04),
			object("b", new BoxF(500, 0, 50, 50), 0.5, 0.04)));
		assertHasNot(far, "a", RelationPredicate.NEXT_TO, "b");
	}

	@Test
	void testEvidenceIsRecorded() {
		List<SpatialRelation> relations = solver.solve(List.of(
			object("a", new BoxF(0, 0, 50, 50), 0.8, 0.04),
			object("b", new BoxF(200, 0, 50, 50), 0.3, 0.04)));

		SpatialRelation front = find(relations, "a", RelationPredicate.IN_FRONT_OF, "b").orElseThrow();
		// "Why did it say in front?" must be answerable from the stored payload alone.
		assertThat(front.evidence().getDouble("deltaNear")).isCloseTo(0.5, org.assertj.core.data.Offset.offset(1e-6));
		assertThat(front.evidence().getDouble("z")).isGreaterThan(1.0);
		assertThat(front.confidence()).isBetween(0.0, 1.0);
	}

	@Test
	void testRelationsAreCappedStrongestFirst() {
		SceneLayoutNodeOptions capped = new SceneLayoutNodeOptions().setMaxRelations(3);
		List<SpatialRelation> relations = new RelationSolver(capped).solve(List.of(
			object("a", new BoxF(0, 0, 50, 50), 0.9, 0.02),
			object("b", new BoxF(200, 0, 50, 50), 0.5, 0.02),
			object("c", new BoxF(400, 0, 50, 50), 0.1, 0.02)));

		assertThat(relations).hasSize(3);
		// Descending confidence, so a cut keeps the assertions worth keeping.
		assertThat(relations).isSortedAccordingTo((x, y) -> Double.compare(y.confidence(), x.confidence()));
	}

	private DepthMap rampMap() throws Exception {
		java.io.File mapFile = java.io.File.createTempFile("depth", ".png");
		mapFile.deleteOnExit();
		SceneLayoutFixtures.writeRampMap(mapFile, 200, 100);
		return DepthMap.read(mapFile, 200, 100);
	}

	@Test
	void testBandsComeFromTheWholeSceneNotTheObjects() throws Exception {
		// Both objects sit deep in the far third of the scene. Ranking them against each other
		// would call the nearer one "foreground"; ranking against the scene's own distribution
		// correctly calls both background. This is the whole reason banding uses scene quantiles.
		List<LayoutObject> banded = solver.assignBands(List.of(
			object("a", new BoxF(140, 0, 20, 20), 0.20, 0.01),
			object("b", new BoxF(170, 0, 20, 20), 0.10, 0.01)), rampMap());

		assertThat(banded).extracting(LayoutObject::band)
			.containsExactly(DepthBand.BACKGROUND, DepthBand.BACKGROUND);
	}

	@Test
	void testBandsSpanForegroundToBackground() throws Exception {
		List<LayoutObject> banded = solver.assignBands(List.of(
			object("near", new BoxF(10, 20, 20, 20), 0.90, 0.01),
			object("mid", new BoxF(90, 20, 20, 20), 0.50, 0.01),
			object("far", new BoxF(170, 20, 20, 20), 0.10, 0.01)), rampMap());

		assertThat(banded).extracting(LayoutObject::band)
			.containsExactly(DepthBand.FOREGROUND, DepthBand.MIDGROUND, DepthBand.BACKGROUND);
	}

	@Test
	void testPhrasesReadAsEnglish() {
		List<LayoutObject> objects = List.of(
			object("face-0", new BoxF(0, 0, 50, 50), 0.8, 0.04).with(new DepthStats(0.8, 0.78, 0.82, 100), DepthBand.FOREGROUND),
			object("face-1", new BoxF(200, 0, 50, 50), 0.3, 0.04).with(new DepthStats(0.3, 0.28, 0.32, 100), DepthBand.BACKGROUND));

		List<String> phrases = solver.phrases(objects, solver.solve(objects));

		assertThat(phrases).contains(
			"face-0 is in the foreground",
			"face-1 is in the background",
			"face-0 is in front of face-1",
			"face-0 is left of face-1");
		// SAME_DEPTH is implied by the band sentences and would read as filler beside them.
		assertThat(phrases).noneMatch(p -> p.contains("same depth"));
	}
}
