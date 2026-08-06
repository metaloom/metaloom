package io.metaloom.cortex.node.filter;

import static io.metaloom.cortex.media.test.assertj.NodeAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.util.Map;

import javax.inject.Provider;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.metaloom.cortex.api.node.NodeInputs;
import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.pipeline.test.StubLoomMedia;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * MIME bucketing: which port an item lands on, and that it costs no model.
 *
 * <p>
 * The second half matters as much as the first. {@link LanguageFilterStrategy} needs a reachable
 * backend, and a graph that only splits images from video must not inherit that requirement — these
 * tests construct the node with no {@code LLMProvider} anywhere in the map, so a strategy that
 * reached for one could not even be built.
 * </p>
 */
class MimeFilterNodeTest {

	@TempDir
	File tempDir;

	private FilterNode node(JsonObject nodeDef) {
		Provider<FilterStrategy> strategy = MimeFilterStrategy::new;
		FilterNode node = new FilterNode(null, new CortexOptions().setMetaPath(tempDir.toPath()), new FilterNodeOptions(),
			Map.of(FilterBy.MIME, strategy));
		node.configure(nodeDef);
		return node;
	}

	private static JsonObject nodeDef() {
		return new JsonObject().put("id", "by-type").put("filterBy", "MIME")
			.put("buckets", new JsonArray()
				.add(new JsonObject().put("id", "pictures").put("label", "Pictures").put("match", "image/*"))
				.add(new JsonObject().put("id", "clips").put("label", "Clips").put("match", "video/mp4, video/webm")));
	}

	private StubLoomMedia file(String name) throws Exception {
		File file = new File(tempDir, name);
		Files.writeString(file.toPath(), "bytes are never read - the name carries the type");
		return new StubLoomMedia(file.getAbsolutePath(), false, false, false, false);
	}

	private NodeResult run(FilterNode node, StubLoomMedia media) {
		return node.process(NodeContext.create(media, NodeInputs.empty()));
	}

	@Test
	void testAnImageLandsOnItsBucketPortAndNowhereElse() throws Exception {
		StubLoomMedia media = file("holiday.png");

		assertThat(run(node(nodeDef()), media))
			.isSuccess()
			.hasOutput(FilterNode.bucketPort("pictures"), media.absolutePath())
			// The silence is the routing: a port that carried nothing skips its consumer.
			.hasNoOutput(FilterNode.bucketPort("clips"))
			.hasNoOutput(FilterNode.OUT_OTHER)
			.hasOutput(FilterNode.OUT_PASSED, Boolean.TRUE)
			.hasOutput(FilterNode.OUT_BUCKET, "pictures");
	}

	/** A second, differently-typed file down a different branch — the whole point of the node. */
	@Test
	void testAVideoTakesTheOtherBranch() throws Exception {
		StubLoomMedia media = file("clip.mp4");

		assertThat(run(node(nodeDef()), media))
			.isSuccess()
			.hasOutput(FilterNode.bucketPort("clips"), media.absolutePath())
			.hasNoOutput(FilterNode.bucketPort("pictures"))
			.hasOutput(FilterNode.OUT_BUCKET, "clips");
	}

	@Test
	void testAnUnmatchedItemGoesToOther() throws Exception {
		StubLoomMedia media = file("notes.pdf");

		assertThat(run(node(nodeDef()), media))
			.isSuccess()
			.hasOutput(FilterNode.OUT_OTHER, media.absolutePath())
			.hasNoOutput(FilterNode.bucketPort("pictures"))
			.hasNoOutput(FilterNode.bucketPort("clips"))
			.hasOutput(FilterNode.OUT_PASSED, Boolean.FALSE)
			.hasOutput(FilterNode.OUT_BUCKET, "other");
	}

	/**
	 * An extension nothing maps to is {@code application/octet-stream}, which is a type like any
	 * other: a bucket that asks for it gets it, rather than it being a special "unknown" case.
	 */
	@Test
	void testAnUnknownExtensionIsOctetStream() throws Exception {
		JsonObject def = nodeDef().put("buckets", new JsonArray()
			.add(new JsonObject().put("id", "blobs").put("match", "application/octet-stream")));

		assertEquals("blobs", run(node(def), file("archive.qqq")).get(FilterNode.OUT_BUCKET));
	}

	/**
	 * Three buckets called image/video/audio with nothing typed in the hint column must still route.
	 * Falling back to the id is what stops the common case needing any configuration at all.
	 */
	@Test
	void testABucketWithNoHintFallsBackToItsId() throws Exception {
		JsonObject def = nodeDef().put("buckets", new JsonArray()
			.add(new JsonObject().put("id", "image"))
			.add(new JsonObject().put("id", "video")));

		assertEquals("image", run(node(def), file("a.jpg")).get(FilterNode.OUT_BUCKET));
		assertEquals("video", run(node(def), file("b.mkv")).get(FilterNode.OUT_BUCKET));
	}

	/**
	 * Declaration order decides, so a narrow bucket placed above a broad one behaves as written. If
	 * the broad one won, the narrow bucket would be unreachable and the author would have no way to
	 * express "PNGs here, other images there".
	 */
	@Test
	void testTheFirstMatchingBucketWins() throws Exception {
		JsonObject def = nodeDef().put("buckets", new JsonArray()
			.add(new JsonObject().put("id", "pngs").put("match", "image/png"))
			.add(new JsonObject().put("id", "images").put("match", "image/*")));

		assertEquals("pngs", run(node(def), file("a.png")).get(FilterNode.OUT_BUCKET));
		assertEquals("images", run(node(def), file("b.jpg")).get(FilterNode.OUT_BUCKET));
	}

	@Test
	void testPatternForms() {
		assertTrue(MimeFilterStrategy.matches("image/png", "image/png"));
		assertTrue(MimeFilterStrategy.matches("image/*", "image/png"));
		assertTrue(MimeFilterStrategy.matches("image", "image/png"), "a bare family reads as 'image/*'");
		assertTrue(MimeFilterStrategy.matches("*", "application/octet-stream"), "a catch-all bucket must catch");
		assertTrue(MimeFilterStrategy.matches("application/vnd.*", "application/vnd.ms-excel"));

		Assertions.assertFalse(MimeFilterStrategy.matches("image/png", "image/jpeg"));
		Assertions.assertFalse(MimeFilterStrategy.matches("image/*", "video/mp4"));
		Assertions.assertFalse(MimeFilterStrategy.matches("video", "image/png"));
	}

	/** The producer version has to name this strategy, or a re-run under LANGUAGE would look current. */
	@Test
	void testTheProducerVersionNamesTheStrategy() {
		assertTrue(node(nodeDef()).producerVersion().startsWith("filter/1:MIME:"), "the filterBy is part of the identity");
	}
}
