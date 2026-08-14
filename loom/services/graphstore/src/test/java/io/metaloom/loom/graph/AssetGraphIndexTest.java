package io.metaloom.loom.graph;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.metaloom.loom.api.graph.AssetGraphIndex;
import io.metaloom.loom.api.graph.GraphEdge;
import io.metaloom.loom.api.graph.GraphNodeRef;
import io.metaloom.loom.api.graph.RelatedAsset;
import io.metaloom.loom.api.graph.RelatedAssetsQuery;
import io.metaloom.loom.graph.store.GraphStoreAssetGraphIndex;

/**
 * The SPI contract, without a database.
 *
 * <p>
 * {@link AssetGraphDifferentialTest} proves the answers match Postgres; this proves the parts of the contract that
 * Postgres has no opinion about - idempotence, the availability semantics, and the behaviour of the Noop.
 * </p>
 */
public class AssetGraphIndexTest {

	private Path indexPath;
	private GraphStoreAssetGraphIndex index;

	private final UUID assetA = UUID.randomUUID();
	private final UUID assetB = UUID.randomUUID();
	private final UUID assetC = UUID.randomUUID();
	private final UUID tag = UUID.randomUUID();
	private final UUID collection = UUID.randomUUID();

	@BeforeEach
	public void setup() throws Exception {
		indexPath = Files.createTempDirectory("asset-graph-unit-");
		index = new GraphStoreAssetGraphIndex(indexPath);
	}

	@AfterEach
	public void teardown() throws Exception {
		if (index != null) {
			index.close();
		}
		deleteRecursively(indexPath);
	}

	@Test
	public void testAnEmptyIndexIsAvailableAndAnswersNothing() {
		assertThat(index.isAvailable()).isTrue();
		assertThat(index.providerName()).isEqualTo("graphstore");
		assertThat(index.relatedAssets(RelatedAssetsQuery.of(assetA))).isEmpty();
		assertThat(index.contains(GraphNodeRef.asset(assetA))).isFalse();
		assertThat(index.status().isHealthy()).isTrue();
	}

	@Test
	public void testTwoAssetsSharingATagAreRelated() {
		index.linkAll(List.of(GraphEdge.tagged(assetA, tag), GraphEdge.tagged(assetB, tag)));

		List<RelatedAsset> related = index.relatedAssets(RelatedAssetsQuery.of(assetA));
		assertThat(related).hasSize(1);
		assertThat(related.get(0).assetUuid()).isEqualTo(assetB);
		assertThat(related.get(0).sharedConnections()).isEqualTo(1);
		assertThat(related.get(0).via()).containsExactly(GraphNodeRef.tag(tag));
	}

	@Test
	public void testAnAssetIsNeverItsOwnNeighbour() {
		index.linkAll(List.of(GraphEdge.tagged(assetA, tag), GraphEdge.inCollection(assetA, collection)));
		assertThat(index.relatedAssets(RelatedAssetsQuery.of(assetA))).isEmpty();
	}

	@Test
	public void testSharedConnectionsRankTheResults() {
		index.linkAll(List.of(
			GraphEdge.tagged(assetA, tag),
			GraphEdge.inCollection(assetA, collection),
			// B shares both; C shares only the tag.
			GraphEdge.tagged(assetB, tag),
			GraphEdge.inCollection(assetB, collection),
			GraphEdge.tagged(assetC, tag)));

		List<RelatedAsset> related = index.relatedAssets(RelatedAssetsQuery.of(assetA));
		assertThat(related).hasSize(2);
		assertThat(related.get(0).assetUuid()).isEqualTo(assetB);
		assertThat(related.get(0).sharedConnections()).isEqualTo(2);
		assertThat(related.get(1).assetUuid()).isEqualTo(assetC);
		assertThat(related.get(1).sharedConnections()).isEqualTo(1);
	}

	@Test
	public void testTheExplanationHasOneEntryPerSharedConnection() {
		index.linkAll(List.of(
			GraphEdge.tagged(assetA, tag), GraphEdge.tagged(assetB, tag),
			GraphEdge.inCollection(assetA, collection), GraphEdge.inCollection(assetB, collection)));

		RelatedAsset hit = index.relatedAssets(RelatedAssetsQuery.of(assetA)).get(0);
		assertThat(hit.via()).containsExactlyInAnyOrder(GraphNodeRef.tag(tag), GraphNodeRef.collection(collection));
	}

	@Test
	public void testTraversalCanBeFilteredByRelationType() {
		index.linkAll(List.of(
			GraphEdge.tagged(assetA, tag), GraphEdge.tagged(assetB, tag),
			GraphEdge.inCollection(assetA, collection), GraphEdge.inCollection(assetC, collection)));

		assertThat(index.relatedAssets(RelatedAssetsQuery.via(assetA, GraphEdge.TYPE_TAGGED)))
			.extracting(RelatedAsset::assetUuid).containsExactly(assetB);
		assertThat(index.relatedAssets(RelatedAssetsQuery.via(assetA, GraphEdge.TYPE_IN_COLLECTION)))
			.extracting(RelatedAsset::assetUuid).containsExactly(assetC);
	}

	/** A write hook and a rebuild both project the same rows; if that accumulated, every count would be wrong. */
	@Test
	public void testLinkingIsIdempotent() {
		GraphEdge edge = GraphEdge.tagged(assetA, tag);
		index.link(edge);
		index.link(edge);
		index.linkAll(List.of(edge, edge));
		index.link(GraphEdge.tagged(assetB, tag));

		RelatedAsset hit = index.relatedAssets(RelatedAssetsQuery.of(assetA)).get(0);
		assertThat(hit.sharedConnections()).as("re-projecting a row must not add a second edge").isEqualTo(1);
		assertThat(index.neighbours(GraphNodeRef.asset(assetA), null)).containsExactly(GraphNodeRef.tag(tag));
	}

	@Test
	public void testUnlinkingRemovesTheRelationship() {
		index.linkAll(List.of(GraphEdge.tagged(assetA, tag), GraphEdge.tagged(assetB, tag)));
		assertThat(index.relatedAssets(RelatedAssetsQuery.of(assetA))).hasSize(1);

		index.unlink(GraphEdge.tagged(assetB, tag));
		assertThat(index.relatedAssets(RelatedAssetsQuery.of(assetA))).isEmpty();
		assertThat(index.contains(GraphNodeRef.asset(assetB))).isTrue();
	}

	@Test
	public void testRemovingANodeRemovesEveryEdgeTouchingIt() {
		index.linkAll(List.of(
			GraphEdge.tagged(assetA, tag), GraphEdge.tagged(assetB, tag),
			GraphEdge.inCollection(assetB, collection)));

		index.remove(GraphNodeRef.asset(assetB));
		assertThat(index.contains(GraphNodeRef.asset(assetB))).isFalse();
		assertThat(index.relatedAssets(RelatedAssetsQuery.of(assetA))).isEmpty();
		assertThat(index.neighbours(GraphNodeRef.collection(collection), null)).isEmpty();
	}

	@Test
	public void testUnlinkingSomethingThatWasNeverLinkedIsHarmless() {
		index.unlink(GraphEdge.tagged(assetA, tag));
		index.remove(GraphNodeRef.asset(assetC));
		assertThat(index.isAvailable()).isTrue();
	}

	@Test
	public void testARebuildReplacesEverything() {
		index.linkAll(List.of(GraphEdge.tagged(assetA, tag), GraphEdge.tagged(assetB, tag)));
		index.rebuild(Stream.of(GraphEdge.inCollection(assetA, collection), GraphEdge.inCollection(assetC, collection)));

		// The tag relation is gone; the collection relation is there. A rebuild is not a merge.
		assertThat(index.relatedAssets(RelatedAssetsQuery.of(assetA)))
			.extracting(RelatedAsset::assetUuid).containsExactly(assetC);
		assertThat(index.contains(GraphNodeRef.tag(tag))).isFalse();
	}

	@Test
	public void testIndexedAssetsAreEnumerable() {
		index.linkAll(List.of(GraphEdge.tagged(assetA, tag), GraphEdge.tagged(assetB, tag)));
		assertThat(index.streamIndexedAssetUuids().toList()).containsExactlyInAnyOrder(assetA, assetB);
	}

	@Test
	public void testNeighboursWorkFromBothEnds() {
		index.linkAll(List.of(GraphEdge.tagged(assetA, tag), GraphEdge.tagged(assetB, tag)));
		assertThat(index.neighbours(GraphNodeRef.asset(assetA), null)).containsExactly(GraphNodeRef.tag(tag));
		assertThat(index.neighbours(GraphNodeRef.tag(tag), null))
			.containsExactlyInAnyOrder(GraphNodeRef.asset(assetA), GraphNodeRef.asset(assetB));
	}

	@Test
	public void testTheIndexSurvivesAReopen() throws Exception {
		index.linkAll(List.of(GraphEdge.tagged(assetA, tag), GraphEdge.tagged(assetB, tag)));
		index.commit();
		index.close();

		index = new GraphStoreAssetGraphIndex(indexPath);
		assertThat(index.isAvailable()).isTrue();
		assertThat(index.relatedAssets(RelatedAssetsQuery.of(assetA)))
			.extracting(RelatedAsset::assetUuid).containsExactly(assetB);
	}

	/**
	 * The whole arrangement rests on this: nothing outside the index holds an engine id, so compaction - which
	 * invalidates every one of them - cannot break a caller.
	 */
	@Test
	public void testCompactionPreservesEveryAnswer() {
		index.linkAll(List.of(
			GraphEdge.tagged(assetA, tag), GraphEdge.tagged(assetB, tag), GraphEdge.tagged(assetC, tag),
			GraphEdge.inCollection(assetA, collection), GraphEdge.inCollection(assetB, collection)));
		index.remove(GraphNodeRef.asset(assetC));
		index.commit();

		List<RelatedAsset> before = index.relatedAssets(RelatedAssetsQuery.of(assetA));
		index.compact();
		assertThat(index.relatedAssets(RelatedAssetsQuery.of(assetA))).isEqualTo(before);
	}

	// ---------------------------------------------------------------- the Noop

	@Test
	public void testTheNoopReportsItselfUnavailable() {
		AssetGraphIndex noop = new NoopAssetGraphIndex();

		// The important part: a caller that checks availability cannot mistake this for "nothing is related".
		assertThat(noop.isAvailable()).isFalse();
		assertThat(noop.providerName()).isEqualTo("none");
		assertThat(noop.status().isHealthy()).isFalse();
		assertThat(noop.status().getDetail()).contains("LOOM_ASSET_GRAPH_PROVIDER");

		noop.link(GraphEdge.tagged(assetA, tag));
		noop.linkAll(List.of(GraphEdge.tagged(assetB, tag)));
		noop.unlink(GraphEdge.tagged(assetA, tag));
		noop.remove(GraphNodeRef.asset(assetA));
		noop.rebuild(Stream.of(GraphEdge.tagged(assetA, tag)));
		noop.commit();
		noop.compact();

		assertThat(noop.relatedAssets(RelatedAssetsQuery.of(assetA))).isEmpty();
		assertThat(noop.neighbours(GraphNodeRef.asset(assetA), Set.of())).isEmpty();
		assertThat(noop.contains(GraphNodeRef.asset(assetA))).isFalse();
		assertThat(noop.streamIndexedAssetUuids()).isEmpty();
	}

	@Test
	public void testAnUnopenableIndexDegradesRatherThanThrowing() throws Exception {
		// A file where a directory should be: the engine cannot open this.
		Path file = Files.createTempFile("asset-graph-not-a-dir-", ".tmp");
		GraphStoreAssetGraphIndex broken = new GraphStoreAssetGraphIndex(file);
		try {
			assertThat(broken.isAvailable()).isFalse();
			assertThat(broken.status().isHealthy()).isFalse();
			// Writes are absorbed and reads answer empty; the binding is what turns this into a Noop at boot.
			broken.link(GraphEdge.tagged(assetA, tag));
			assertThat(broken.relatedAssets(RelatedAssetsQuery.of(assetA))).isEmpty();
		} finally {
			broken.close();
			Files.deleteIfExists(file);
		}
	}

	private static void deleteRecursively(Path root) throws Exception {
		if (root == null || !Files.exists(root)) {
			return;
		}
		try (Stream<Path> walk = Files.walk(root)) {
			for (Path entry : walk.sorted(Comparator.reverseOrder()).toList()) {
				Files.deleteIfExists(entry);
			}
		}
	}
}
