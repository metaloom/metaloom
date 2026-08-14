package io.metaloom.loom.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import io.metaloom.loom.api.graph.GraphEdge;
import io.metaloom.loom.api.graph.GraphNodeRef;
import io.metaloom.loom.api.graph.RelatedAsset;
import io.metaloom.loom.api.graph.RelatedAssetsQuery;
import io.metaloom.loom.graph.store.GraphStoreAssetGraphIndex;

/**
 * The Phase 10 differential check: the same relationships, in Postgres and in the graph index, asked the same question.
 *
 * <p>
 * The plan for this phase is explicit that the prototype must run <b>alongside</b> Postgres rather than replacing it,
 * with a check that both return the same answers, and that the decision be made on measured results rather than on
 * architecture preference. This is that check. The SQL in {@link PostgresLinkTables} is not a stand-in — it is the
 * four-way union self-join MetaLoom would otherwise write, running against a real Postgres.
 * </p>
 *
 * <p>
 * The comparison is on the <b>answer set and the ranking signal</b>, not on the row order beyond it: both sides sort by
 * shared-connection count descending then by uuid, so the order is fully determined and is compared too.
 * </p>
 *
 * <p>
 * Skips rather than fails when no Postgres is reachable. A differential test that cannot reach one of its two sides has
 * nothing to say, and saying it loudly as a failure would train people to ignore it.
 * </p>
 */
@Tag("graphdiff")
public class AssetGraphDifferentialTest {

	private static final String URL = System.getProperty("assetgraph.test.jdbc.url", "jdbc:postgresql://localhost:5432/loom");
	private static final String USER = System.getProperty("assetgraph.test.jdbc.user", "postgres");
	private static final String PASSWORD = System.getProperty("assetgraph.test.jdbc.password", "finger");

	private Path indexPath;
	private GraphStoreAssetGraphIndex index;
	private PostgresLinkTables postgres;

	@BeforeEach
	public void setup() throws Exception {
		postgres = PostgresLinkTables.openOrNull(URL, USER, PASSWORD);
		assumeTrue(postgres != null, "no Postgres at " + URL + "; the differential check needs both sides");
		indexPath = Files.createTempDirectory("asset-graph-diff-");
		index = new GraphStoreAssetGraphIndex(indexPath);
		assertThat(index.isAvailable()).isTrue();
	}

	@AfterEach
	public void teardown() throws Exception {
		if (index != null) {
			index.close();
		}
		if (postgres != null) {
			postgres.close();
		}
		if (indexPath != null) {
			deleteRecursively(indexPath);
		}
	}

	// ---------------------------------------------------------------- the check

	@Test
	public void testBothSidesAgreeOnEveryAsset() throws Exception {
		Dataset dataset = Dataset.random(new Random(20260813L), 120, 25, 12, 8, 15, 900);
		project(dataset);

		for (UUID asset : dataset.assets()) {
			assertRelatedMatches(asset, null);
		}
	}

	@Test
	public void testBothSidesAgreeWhenTheTraversalIsFiltered() throws Exception {
		Dataset dataset = Dataset.random(new Random(4242L), 80, 20, 10, 6, 10, 600);
		project(dataset);

		List<Set<String>> filters = List.of(
			Set.of(GraphEdge.TYPE_TAGGED),
			Set.of(GraphEdge.TYPE_IN_COLLECTION),
			Set.of(GraphEdge.TYPE_TAGGED, GraphEdge.TYPE_DEPICTS),
			Set.of(GraphEdge.TYPE_IN_REMIX, GraphEdge.TYPE_IN_COLLECTION));

		for (Set<String> filter : filters) {
			for (UUID asset : dataset.assets()) {
				assertRelatedMatches(asset, filter);
			}
		}
	}

	@Test
	public void testBothSidesAgreeOnImmediateNeighbours() throws Exception {
		Dataset dataset = Dataset.random(new Random(77L), 60, 15, 8, 5, 8, 400);
		project(dataset);

		for (GraphNodeRef node : dataset.allNodes()) {
			Set<GraphNodeRef> expected = postgres.neighbours(node, null);
			List<GraphNodeRef> actual = index.neighbours(node, null);
			assertThat(actual).as("neighbours of %s", node).containsExactlyInAnyOrderElementsOf(expected);
		}
	}

	/** The index has to track deletions, not just insertions, or it drifts from Postgres the first time a row goes. */
	@Test
	public void testBothSidesAgreeAfterDeletions() throws Exception {
		Random random = new Random(31337L);
		Dataset dataset = Dataset.random(random, 90, 20, 10, 6, 10, 700);
		project(dataset);

		// Unlink a sample of edges from both sides.
		List<GraphEdge> doomed = new ArrayList<>(dataset.edges());
		java.util.Collections.shuffle(doomed, random);
		for (GraphEdge edge : doomed.subList(0, 150)) {
			postgres.delete(edge);
			index.unlink(edge);
		}

		// And remove some assets entirely, as a cascade would.
		List<UUID> removed = new ArrayList<>(dataset.assets()).subList(0, 10);
		for (UUID asset : removed) {
			postgres.deleteAsset(asset);
			index.remove(GraphNodeRef.asset(asset));
		}
		index.commit();

		for (UUID asset : dataset.assets()) {
			assertRelatedMatches(asset, null);
		}
	}

	/**
	 * A rebuild is what makes the whole arrangement safe — it is the reason a lost index write costs nothing. So the
	 * rebuilt index must agree with Postgres exactly as the incrementally maintained one does.
	 */
	@Test
	public void testARebuiltIndexAgreesWithPostgres() throws Exception {
		Dataset dataset = Dataset.random(new Random(5150L), 100, 20, 10, 6, 12, 800);
		for (GraphEdge edge : dataset.edges()) {
			postgres.insert(edge);
		}
		// Nothing was written to the index incrementally; it is built entirely from the link rows.
		index.rebuild(dataset.edges().stream());
		index.commit();

		for (UUID asset : dataset.assets()) {
			assertRelatedMatches(asset, null);
		}
		assertThat(index.streamIndexedAssetUuids().toList())
			.as("every asset that appears in a link row must be indexed")
			.containsExactlyInAnyOrderElementsOf(dataset.linkedAssets());
	}

	/** Compaction relocates records and invalidates the engine's internal ids. The answers must not change. */
	@Test
	public void testCompactionDoesNotChangeAnyAnswer() throws Exception {
		Random random = new Random(909L);
		Dataset dataset = Dataset.random(random, 100, 20, 10, 6, 12, 800);
		project(dataset);

		List<GraphEdge> doomed = new ArrayList<>(dataset.edges());
		java.util.Collections.shuffle(doomed, random);
		for (GraphEdge edge : doomed.subList(0, 300)) {
			postgres.delete(edge);
			index.unlink(edge);
		}
		index.commit();
		index.compact();

		for (UUID asset : dataset.assets()) {
			assertRelatedMatches(asset, null);
		}
	}

	// ---------------------------------------------------------------- helpers

	private void assertRelatedMatches(UUID asset, Set<String> viaTypes) throws Exception {
		int limit = 500;
		List<PostgresLinkTables.SqlRelatedAsset> expected = postgres.relatedAssets(asset, viaTypes, limit);
		List<RelatedAsset> actual = index.relatedAssets(new RelatedAssetsQuery(asset, viaTypes, limit, 1));

		// Both SQL formulations are compared. The tuned one is what the benchmark reports against and what anyone
		// would actually deploy, so an index that agreed only with the naive query would be proving nothing.
		assertThat(postgres.relatedAssetsOptimised(asset, viaTypes, limit))
			.as("the two SQL formulations must agree with each other for %s via %s", asset, viaTypes)
			.containsExactlyElementsOf(expected);

		assertThat(actual.stream().map(RelatedAsset::assetUuid).toList())
			.as("related assets of %s via %s", asset, viaTypes)
			.containsExactlyElementsOf(expected.stream().map(PostgresLinkTables.SqlRelatedAsset::assetUuid).toList());

		for (int i = 0; i < actual.size(); i++) {
			assertThat(actual.get(i).sharedConnections())
				.as("shared connections between %s and %s", asset, actual.get(i).assetUuid())
				.isEqualTo(expected.get(i).sharedConnections());
			assertThat(actual.get(i).via())
				.as("the explanation must have one entry per shared connection")
				.hasSize(actual.get(i).sharedConnections());
		}
	}

	private void project(Dataset dataset) throws Exception {
		for (GraphEdge edge : dataset.edges()) {
			postgres.insert(edge);
		}
		// Written the way the pipeline would: Postgres first, then the index, in batches.
		List<GraphEdge> batch = new ArrayList<>();
		for (GraphEdge edge : dataset.edges()) {
			batch.add(edge);
			if (batch.size() == 200) {
				index.linkAll(batch);
				batch.clear();
			}
		}
		index.linkAll(batch);
		index.commit();
	}

	/** A random but reproducible slice of a DAM: assets carrying tags, in collections and remixes, depicting people. */
	record Dataset(List<UUID> assets, List<GraphEdge> edges) {

		static Dataset random(Random random, int assetCount, int tagCount, int collectionCount, int remixCount,
			int personCount, int edgeCount) {
			List<UUID> assets = uuids(random, assetCount);
			List<UUID> tags = uuids(random, tagCount);
			List<UUID> collections = uuids(random, collectionCount);
			List<UUID> remixes = uuids(random, remixCount);
			List<UUID> people = uuids(random, personCount);

			Set<GraphEdge> edges = new java.util.LinkedHashSet<>();
			while (edges.size() < edgeCount) {
				UUID asset = assets.get(random.nextInt(assets.size()));
				edges.add(switch (random.nextInt(4)) {
					case 0 -> GraphEdge.tagged(asset, tags.get(random.nextInt(tags.size())));
					case 1 -> GraphEdge.inCollection(asset, collections.get(random.nextInt(collections.size())));
					case 2 -> GraphEdge.inRemix(asset, remixes.get(random.nextInt(remixes.size())));
					default -> GraphEdge.depicts(asset, people.get(random.nextInt(people.size())));
				});
			}
			return new Dataset(assets, new ArrayList<>(edges));
		}

		private static List<UUID> uuids(Random random, int count) {
			List<UUID> result = new ArrayList<>(count);
			for (int i = 0; i < count; i++) {
				result.add(new UUID(random.nextLong(), random.nextLong()));
			}
			return result;
		}

		List<UUID> linkedAssets() {
			return edges.stream().map(edge -> edge.to().uuid()).distinct().toList();
		}

		List<GraphNodeRef> allNodes() {
			return Stream.concat(edges.stream().map(GraphEdge::from), edges.stream().map(GraphEdge::to))
				.distinct()
				.sorted(Comparator.comparing(GraphNodeRef::kind).thenComparing(ref -> ref.uuid().toString()))
				.toList();
		}
	}

	private static void deleteRecursively(Path root) throws Exception {
		if (!Files.exists(root)) {
			return;
		}
		try (Stream<Path> walk = Files.walk(root)) {
			for (Path entry : walk.sorted(Comparator.reverseOrder()).toList()) {
				Files.deleteIfExists(entry);
			}
		}
	}
}
