package io.metaloom.cortex.node.hash;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.pipeline.api.node.PipelineNode;
import io.metaloom.cortex.pipeline.core.node.CortexNodeAdapter;
import io.metaloom.cortex.pipeline.api.NodeMode;
import io.metaloom.cortex.pipeline.test.StubLoomMedia;
import io.metaloom.cortex.runtime.NodeTaskRunner;
import io.metaloom.cortex.runtime.SegmentTaskRunner;
import io.metaloom.loom.pipeline.model.MediaRef;
import io.metaloom.loom.pipeline.model.NodeTask;
import io.metaloom.loom.pipeline.model.SegmentNode;
import io.metaloom.loom.pipeline.model.SegmentTask;
import io.metaloom.loom.pipeline.model.SegmentTaskResult;

/**
 * Measures per-node dispatch against segment dispatch over real files.
 *
 * <h2>What this measures, and what it does not</h2>
 *
 * <p><strong>Measures:</strong> the worker-side cost of running N nodes over one
 * media item as N separate tasks versus one segment — real hashing over real files,
 * with real I/O.</p>
 *
 * <p><strong>Does not measure:</strong> network round trips, Loom-side engine
 * overhead, serialisation, or worker scheduling. There is no socket and no Loom in
 * this harness. The end-to-end saving from affinity is therefore <em>at least</em>
 * what this reports, plus N-1 round trips per item that are not counted here.</p>
 *
 * <p>Disabled by default because it depends on {@code /opt/metaloom/loom-testdata}
 * and takes seconds rather than milliseconds. Run with:</p>
 *
 * <pre>mvn test -pl cortex/nodes/hash/core -Dtest=SegmentDispatchBenchmark -Dbenchmark=true</pre>
 */
@EnabledIfSystemProperty(named = "benchmark", matches = "true")
public class SegmentDispatchBenchmark {

	private static final Path TESTDATA = Paths.get("/opt/metaloom/loom-testdata");
	private static final int WARMUP_ROUNDS = 1;
	private static final int MEASURED_ROUNDS = 3;

	private SHA512Node sha512Node() {
		HashNodeOptions options = mock(HashNodeOptions.class);
		when(options.isSHA512()).thenReturn(true);
		when(options.isEnabled()).thenReturn(true);
		return new SHA512Node(null, new CortexOptions(), options);
	}

	private MD5Node md5Node() {
		HashNodeOptions options = mock(HashNodeOptions.class);
		when(options.isMD5()).thenReturn(true);
		when(options.isEnabled()).thenReturn(true);
		return new MD5Node(null, new CortexOptions(), options);
	}

	private PipelineNode adapt(io.metaloom.cortex.api.node.FilesystemNode<?, ?> node) {
		return new CortexNodeAdapter(node, NodeMode.PARALLEL, true, 1);
	}

	/** Resolves a node id to a fresh instance, as the real factory would. */
	private PipelineNode instantiate(String id) {
		switch (id) {
			case "sha512":
				return adapt(sha512Node());
			case "md5":
				return adapt(md5Node());
			default:
				throw new IllegalArgumentException("Unknown node '" + id + "'");
		}
	}

	private List<File> mediaFiles() throws Exception {
		if (!Files.isDirectory(TESTDATA)) {
			return List.of();
		}
		try (Stream<Path> walk = Files.walk(TESTDATA)) {
			return walk.filter(Files::isRegularFile)
				.filter(p -> {
					String name = p.toString().toLowerCase();
					return name.endsWith(".mp4") || name.endsWith(".m4v") || name.endsWith(".jpg")
						|| name.endsWith(".png");
				})
				.map(Path::toFile)
				.sorted((a, b) -> Long.compare(b.length(), a.length()))
				.toList();
		}
	}

	@Test
	void benchmarkSegmentVersusPerNodeDispatch() throws Exception {
		List<File> files = mediaFiles();
		Assumptions.assumeFalse(files.isEmpty(), "No test media under " + TESTDATA);

		long totalBytes = files.stream().mapToLong(File::length).sum();
		System.out.println("=== Segment vs per-node dispatch ===");
		System.out.printf("Files: %d, total %.1f MiB%n", files.size(), totalBytes / (1024.0 * 1024.0));
		System.out.println("Graph: sha512 -> md5 (2 nodes, both read the whole file)");

		for (int i = 0; i < WARMUP_ROUNDS; i++) {
			runPerNode(files);
			runSegment(files);
		}

		List<Long> perNode = new ArrayList<>();
		List<Long> segment = new ArrayList<>();
		for (int i = 0; i < MEASURED_ROUNDS; i++) {
			perNode.add(runPerNode(files));
			segment.add(runSegment(files));
		}

		long perNodeMs = median(perNode);
		long segmentMs = median(segment);

		System.out.printf("per-node dispatch : %d ms (median of %d)%n", perNodeMs, MEASURED_ROUNDS);
		System.out.printf("segment dispatch  : %d ms (median of %d)%n", segmentMs, MEASURED_ROUNDS);
		if (segmentMs > 0) {
			System.out.printf("ratio             : %.2fx%n", perNodeMs / (double) segmentMs);
		}
		System.out.printf("delta             : %d ms over %d files%n", perNodeMs - segmentMs, files.size());
		System.out.println("NOTE: worker-side only. Network round trips and Loom engine overhead are NOT included,");
		System.out.println("      so the end-to-end difference is this plus N-1 round trips per item.");
	}

	/** Each node dispatched separately, resolving the media independently. */
	private long runPerNode(List<File> files) {
		NodeTaskRunner runner = new NodeTaskRunner(def -> instantiate(def.getString("id")),
			path -> StubLoomMedia.ofFile(path.toFile()));

		long start = System.nanoTime();
		for (File file : files) {
			UUID runUuid = UUID.randomUUID();
			MediaRef ref = MediaRef.of(file.getAbsolutePath());
			Map<String, Object> sha = runner.run(new NodeTask(UUID.randomUUID(), runUuid, "item", "sha512", "sha512",
				ref, Map.of(), Map.of())).getOutputs();
			runner.run(new NodeTask(UUID.randomUUID(), runUuid, "item", "md5", "md5", ref, Map.of(),
				Map.of("sha512", sha)));
		}
		return (System.nanoTime() - start) / 1_000_000;
	}

	/** Both nodes in one segment, resolving the media once. */
	private long runSegment(List<File> files) {
		SegmentTaskRunner runner = new SegmentTaskRunner(def -> instantiate(def.getString("id")),
			path -> StubLoomMedia.ofFile(path.toFile()));

		long start = System.nanoTime();
		for (File file : files) {
			SegmentTask task = new SegmentTask(UUID.randomUUID(), UUID.randomUUID(), "item", "seg", "hashing",
				MediaRef.of(file.getAbsolutePath()),
				List.of(
					new SegmentNode("sha512", "sha512", true, Map.of(), List.of()),
					new SegmentNode("md5", "md5", true, Map.of(), List.of("sha512"))),
				Map.of());
			SegmentTaskResult result = runner.run(task);
			if (result.getResults().size() != 2) {
				throw new IllegalStateException("Segment did not run both nodes: " + result);
			}
		}
		return (System.nanoTime() - start) / 1_000_000;
	}

	private static long median(List<Long> values) {
		List<Long> sorted = new ArrayList<>(values);
		sorted.sort(Long::compare);
		return sorted.get(sorted.size() / 2);
	}

}
