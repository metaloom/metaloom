package io.metaloom.loom.graph;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import io.metaloom.loom.api.graph.GraphEdge;
import io.metaloom.loom.api.graph.RelatedAssetsQuery;
import io.metaloom.loom.api.search.IndexStatus;
import io.metaloom.loom.graph.store.GraphStoreAssetGraphIndex;

/**
 * The measurement Phase 10 is supposed to decide on.
 *
 * <p>
 * The plan says the decision is made "on the basis of measured results, not architecture preference", so this runs the
 * same question against both sides at three scales and prints a table. It is not a rigorous benchmark — one process,
 * a Postgres in a container on the same machine, no separate client — and the absolute numbers are worth little. What
 * it is good for is the <b>shape</b>: how each side's latency moves as the graph grows, which is the only thing that
 * decides whether an index is worth maintaining.
 * </p>
 *
 * <p>
 * Run it with {@code mvn test -pl loom/services/graphstore -Dtest=AssetGraphBenchmarkTest -Dgroups=graphbench}, or
 * just by name. It skips when no Postgres is reachable.
 * </p>
 */
@Tag("graphbench")
public class AssetGraphBenchmarkTest {

	private static final String URL = System.getProperty("assetgraph.test.jdbc.url", "jdbc:postgresql://localhost:5432/loom");
	private static final String USER = System.getProperty("assetgraph.test.jdbc.user", "postgres");
	private static final String PASSWORD = System.getProperty("assetgraph.test.jdbc.password", "finger");

	/** assets, tags, collections, remixes, people, edges. */
	private static final int[][] SCALES = {
		{ 1_000, 200, 100, 60, 150, 8_000 },
		{ 10_000, 2_000, 800, 500, 1_500, 80_000 },
		{ 50_000, 8_000, 3_000, 2_000, 6_000, 400_000 }
	};

	@Test
	public void benchmarkAgainstPostgres() throws Exception {
		List<String> rows = new ArrayList<>();
		rows.add(String.format("%-9s %-9s %12s %12s %12s %10s %10s %10s",
			"assets", "edges", "naive sql", "tuned sql", "index", "vs naive", "vs tuned", "index MB"));

		for (int[] scale : SCALES) {
			rows.add(runScale(scale));
		}

		System.out.println();
		System.out.println("asset graph: SQL self-join vs graph index (related assets, 2 hops)");
		rows.forEach(row -> System.out.println("  " + row));
		System.out.println();
		assertTrue(rows.size() > 1, "at least one scale must have run");
	}

	private String runScale(int[] scale) throws Exception {
		int assetCount = scale[0];
		int edgeCount = scale[5];

		try (PostgresLinkTables postgres = PostgresLinkTables.openOrNull(URL, USER, PASSWORD)) {
			assumeTrue(postgres != null, "no Postgres at " + URL);
			Path indexPath = Files.createTempDirectory("asset-graph-bench-");
			GraphStoreAssetGraphIndex index = new GraphStoreAssetGraphIndex(indexPath);
			try {
				Random random = new Random(20260813L + assetCount);
				List<UUID> assets = uuids(random, assetCount);
				List<GraphEdge> edges = randomEdges(random, assets, scale);

				postgres.insertAll(edges);
				postgres.analyze();

				long buildStart = System.nanoTime();
				index.rebuild(edges.stream());
				index.commit();
				long buildMillis = (System.nanoTime() - buildStart) / 1_000_000;

				List<UUID> probes = assets.subList(0, Math.min(200, assets.size()));
				// Warm both sides: an unwarmed comparison measures the JIT and the buffer cache, not the design.
				for (UUID asset : probes.subList(0, Math.min(20, probes.size()))) {
					postgres.relatedAssets(asset, null, 50);
					postgres.relatedAssetsOptimised(asset, null, 50);
					index.relatedAssets(new RelatedAssetsQuery(asset, null, 50, 1));
				}

				long[] naiveTimes = new long[probes.size()];
				long[] tunedTimes = new long[probes.size()];
				long[] indexTimes = new long[probes.size()];
				for (int i = 0; i < probes.size(); i++) {
					long start = System.nanoTime();
					postgres.relatedAssets(probes.get(i), null, 50);
					naiveTimes[i] = System.nanoTime() - start;

					start = System.nanoTime();
					postgres.relatedAssetsOptimised(probes.get(i), null, 50);
					tunedTimes[i] = System.nanoTime() - start;

					start = System.nanoTime();
					index.relatedAssets(new RelatedAssetsQuery(probes.get(i), null, 50, 1));
					indexTimes[i] = System.nanoTime() - start;
				}
				double naiveP50 = median(naiveTimes);
				double tunedP50 = median(tunedTimes);
				double indexP50 = median(indexTimes);

				IndexStatus status = index.status();
				System.out.printf("  built %d edges in %d ms%n", edgeCount, buildMillis);
				return String.format("%-9d %-9d %10.0f us %10.0f us %10.0f us %9.1fx %9.1fx %10.1f",
					assetCount, edgeCount, naiveP50, tunedP50, indexP50,
					naiveP50 / indexP50, tunedP50 / indexP50,
					status.getSizeBytes() / (1024.0 * 1024.0));
			} finally {
				index.close();
				deleteRecursively(indexPath);
			}
		}
	}

	private static double median(long[] nanos) {
		java.util.Arrays.sort(nanos);
		return nanos[nanos.length / 2] / 1000.0;
	}

	private static List<GraphEdge> randomEdges(Random random, List<UUID> assets, int[] scale) {
		List<UUID> tags = uuids(random, scale[1]);
		List<UUID> collections = uuids(random, scale[2]);
		List<UUID> remixes = uuids(random, scale[3]);
		List<UUID> people = uuids(random, scale[4]);
		Set<GraphEdge> edges = new LinkedHashSet<>();
		while (edges.size() < scale[5]) {
			UUID asset = assets.get(random.nextInt(assets.size()));
			edges.add(switch (random.nextInt(4)) {
				case 0 -> GraphEdge.tagged(asset, tags.get(random.nextInt(tags.size())));
				case 1 -> GraphEdge.inCollection(asset, collections.get(random.nextInt(collections.size())));
				case 2 -> GraphEdge.inRemix(asset, remixes.get(random.nextInt(remixes.size())));
				default -> GraphEdge.depicts(asset, people.get(random.nextInt(people.size())));
			});
		}
		return new ArrayList<>(edges);
	}

	private static List<UUID> uuids(Random random, int count) {
		List<UUID> result = new ArrayList<>(count);
		for (int i = 0; i < count; i++) {
			result.add(new UUID(random.nextLong(), random.nextLong()));
		}
		return result;
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
