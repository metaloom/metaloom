package io.metaloom.cortex.node.filter;

import static io.metaloom.cortex.media.test.assertj.NodeAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.util.Map;

import javax.inject.Provider;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.metaloom.cortex.api.node.NodeInputs;
import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.node.filter.SizeFilterStrategy.Threshold;
import io.metaloom.cortex.pipeline.test.StubLoomMedia;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Size bucketing. Like {@link MimeFilterNodeTest} the node is built with no {@code LLMProvider} in
 * the strategy map at all, so this configuration cannot quietly acquire a round trip.
 */
class SizeFilterNodeTest {

	@TempDir
	File tempDir;

	private FilterNode node(JsonObject nodeDef) {
		Provider<FilterStrategy> strategy = SizeFilterStrategy::new;
		FilterNode node = new FilterNode(null, new CortexOptions().setMetaPath(tempDir.toPath()), new FilterNodeOptions(),
			Map.of(FilterBy.SIZE, strategy));
		node.configure(nodeDef);
		return node;
	}

	private static JsonObject nodeDef() {
		return new JsonObject().put("id", "by-size").put("filterBy", "SIZE")
			.put("buckets", new JsonArray()
				.add(new JsonObject().put("id", "small").put("label", "Small").put("match", "<1kb"))
				.add(new JsonObject().put("id", "medium").put("label", "Medium").put("match", "1kb..8kb")));
	}

	/** A file of exactly {@code bytes} bytes; the strategy reads {@code LoomMedia.size()}. */
	private StubLoomMedia file(String name, int bytes) throws Exception {
		File file = new File(tempDir, name);
		Files.write(file.toPath(), new byte[bytes]);
		return StubLoomMedia.ofFile(file);
	}

	private NodeResult run(FilterNode node, StubLoomMedia media) {
		return node.process(NodeContext.create(media, NodeInputs.empty()));
	}

	@Test
	void testASmallFileLandsOnItsBucketPortAndNowhereElse() throws Exception {
		StubLoomMedia media = file("tiny.bin", 100);

		assertThat(run(node(nodeDef()), media))
			.isSuccess()
			.hasOutput(FilterNode.bucketPort("small"), media.absolutePath())
			.hasNoOutput(FilterNode.bucketPort("medium"))
			.hasNoOutput(FilterNode.OUT_OTHER)
			.hasOutput(FilterNode.OUT_PASSED, Boolean.TRUE)
			.hasOutput(FilterNode.OUT_BUCKET, "small");
	}

	@Test
	void testAMediumFileTakesTheOtherBranch() throws Exception {
		StubLoomMedia media = file("mid.bin", 4096);

		assertThat(run(node(nodeDef()), media))
			.isSuccess()
			.hasOutput(FilterNode.bucketPort("medium"), media.absolutePath())
			.hasNoOutput(FilterNode.bucketPort("small"))
			.hasOutput(FilterNode.OUT_BUCKET, "medium");
	}

	@Test
	void testAnUnmatchedItemGoesToOther() throws Exception {
		StubLoomMedia media = file("big.bin", 32_768);

		assertThat(run(node(nodeDef()), media))
			.isSuccess()
			.hasOutput(FilterNode.OUT_OTHER, media.absolutePath())
			.hasNoOutput(FilterNode.bucketPort("small"))
			.hasNoOutput(FilterNode.bucketPort("medium"))
			.hasOutput(FilterNode.OUT_PASSED, Boolean.FALSE)
			.hasOutput(FilterNode.OUT_BUCKET, "other");
	}

	/**
	 * The ladder that made a bare threshold an upper bound: three rows, no operators, and every file
	 * lands where the row above it stopped.
	 */
	@Test
	void testABareThresholdIsAnUpperBound() throws Exception {
		JsonObject def = nodeDef().put("buckets", new JsonArray()
			.add(new JsonObject().put("id", "small").put("match", "1kb"))
			.add(new JsonObject().put("id", "medium").put("match", "8kb"))
			.add(new JsonObject().put("id", "large").put("match", ">8kb")));

		assertEquals("small", run(node(def), file("a.bin", 512)).get(FilterNode.OUT_BUCKET));
		assertEquals("small", run(node(def), file("b.bin", 1024)).get(FilterNode.OUT_BUCKET), "'1kb' includes 1024 bytes");
		assertEquals("medium", run(node(def), file("c.bin", 1025)).get(FilterNode.OUT_BUCKET));
		assertEquals("large", run(node(def), file("d.bin", 9000)).get(FilterNode.OUT_BUCKET));
	}

	@Test
	void testThresholdForms() {
		assertTrue(SizeFilterStrategy.parse("<1kb").holds(1023));
		assertTrue(SizeFilterStrategy.parse("<=1kb").holds(1024));
		org.junit.jupiter.api.Assertions.assertFalse(SizeFilterStrategy.parse("<1kb").holds(1024));
		assertTrue(SizeFilterStrategy.parse(">1kb").holds(1025));
		org.junit.jupiter.api.Assertions.assertFalse(SizeFilterStrategy.parse(">1kb").holds(1024));
		assertTrue(SizeFilterStrategy.parse(">=1kb").holds(1024));

		// Lower bound inclusive, upper exclusive, so adjacent ranges tile without an overlap.
		Threshold range = SizeFilterStrategy.parse("1kb..2kb");
		assertTrue(range.holds(1024));
		org.junit.jupiter.api.Assertions.assertFalse(range.holds(2048));
		org.junit.jupiter.api.Assertions.assertFalse(range.holds(1023));
	}

	@Test
	void testUnits() {
		assertEquals(1L, SizeFilterStrategy.bytes("1"), "a bare number is bytes");
		assertEquals(1024L, SizeFilterStrategy.bytes("1kb"));
		assertEquals(1024L, SizeFilterStrategy.bytes("1kib"), "KiB is an alias, not a second scale");
		assertEquals(1024L * 1024, SizeFilterStrategy.bytes("1mb"));
		assertEquals(1024L * 1024 * 1024, SizeFilterStrategy.bytes("1gb"));
		assertEquals(1536L, SizeFilterStrategy.bytes("1.5kb"));

		assertNull(SizeFilterStrategy.bytes("10 megabytes"));
		assertNull(SizeFilterStrategy.bytes("kb"));
		assertNull(SizeFilterStrategy.bytes(""));
	}

	/**
	 * A hint nothing can parse must fail the task, not route every item to {@code other} for the whole
	 * run — that failure mode is indistinguishable from data that genuinely did not match.
	 */
	@Test
	void testAnUnparseableHintIsRejectedAtConfigureTime() {
		IllegalStateException e = assertThrows(IllegalStateException.class,
			() -> node(nodeDef().put("buckets", new JsonArray().add(new JsonObject().put("id", "small").put("match", "<10 megabytes")))));

		assertTrue(e.getMessage().contains("<10 megabytes"), e.getMessage());
		assertTrue(e.getMessage().contains("small"), e.getMessage());
	}

	/** And a bucket with no hint at all: there is no id that could be read as a size. */
	@Test
	void testABucketWithNoHintIsRejectedAtConfigureTime() {
		IllegalStateException e = assertThrows(IllegalStateException.class,
			() -> node(nodeDef().put("buckets", new JsonArray().add(new JsonObject().put("id", "small")))));

		assertTrue(e.getMessage().contains("needs a size threshold"), e.getMessage());
	}

	@Test
	void testTheProducerVersionNamesTheStrategy() {
		assertTrue(node(nodeDef()).producerVersion().startsWith("filter/1:SIZE:"));
	}
}
