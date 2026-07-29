package io.metaloom.cortex.node.sink.s3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.node.NodeInputs;
import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.ResultState;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.s3.FakeS3ObjectStore;
import io.metaloom.cortex.s3.S3MediaMaterializer;
import io.metaloom.cortex.s3.S3Support;
import io.metaloom.utils.hash.SHA512;
import io.vertx.core.json.JsonObject;

/**
 * Behaviour of the sink against an in-memory store. Loom persistence is covered separately by
 * {@link S3SinkNodePersistenceTest}; here the client is null, i.e. offline mode.
 */
public class S3SinkNodeTest {

	private static final String BUCKET = "media";

	@TempDir
	Path metaPath;

	private FakeS3ObjectStore store;
	private CortexOptions cortexOptions;
	private Path thumb;

	@BeforeEach
	public void setup() throws IOException {
		store = new FakeS3ObjectStore();
		cortexOptions = new CortexOptions().setMetaPath(metaPath);
		thumb = write("thumbnail_bin/ab/sheet.thumb", "contact-sheet-bytes");
	}

	private Path write(String relative, String content) throws IOException {
		Path file = metaPath.resolve(relative);
		Files.createDirectories(file.getParent());
		Files.writeString(file, content);
		return file.toAbsolutePath().normalize();
	}

	private S3Support support() {
		return S3Support.active(store, new S3MediaMaterializer(store, metaPath.resolve("s3_bin"), 0, 0),
			metaPath.resolve("s3-index"));
	}

	private S3SinkNode node(JsonObject def) {
		S3SinkNode node = new S3SinkNode(null, cortexOptions, new S3SinkNodeOptions(), support());
		node.configure(def.copy().put("bucket", def.getString("bucket", BUCKET)));
		return node;
	}

	private S3SinkNode node() {
		return node(new JsonObject().put("id", "archive"));
	}

	private LoomMedia media() {
		LoomMedia media = mock(LoomMedia.class);
		Path path = metaPath.resolve("library/clip.mp4");
		when(media.path()).thenReturn(path);
		when(media.reference()).thenReturn(path.toString());
		when(media.absolutePath()).thenReturn(path.toString());
		when(media.exists()).thenReturn(true);
		when(media.getSHA512()).thenReturn(SHA512.fromString("a".repeat(128)));
		return media;
	}

	/**
	 * Fill the sink's {@code artifacts} port, one element per file - which is what an edge into that
	 * port delivers. No upstream node id appears anywhere: the graph decided that, not the sink.
	 */
	private NodeContext<LoomMedia> ctx(String... artifacts) {
		return ctx(media(), artifacts);
	}

	private NodeContext<LoomMedia> ctx(LoomMedia media, String... artifacts) {
		NodeInputs inputs = artifacts.length == 0
			? NodeInputs.empty()
			: NodeInputs.builder().inputs(S3SinkNode.IN_ARTIFACTS, List.of(artifacts)).build();
		return NodeContext.create(media, inputs);
	}

	private NodeContext<LoomMedia> thumbnailCtx() {
		return ctx(thumb.toString());
	}

	// --- the happy path -------------------------------------------------------------------

	@Test
	public void testUploadsAnArtifactFromThePort() {
		NodeResult result = node().process(thumbnailCtx());

		assertThat(result.getState()).isEqualTo(ResultState.SUCCESS);
		assertThat(store.uploadCalls).hasValue(1);
		assertThat(store.keys(BUCKET)).hasSize(1);
		assertThat(new String(store.bytes(BUCKET, store.keys(BUCKET).iterator().next()),
			StandardCharsets.UTF_8)).isEqualTo("contact-sheet-bytes");
	}

	@Test
	public void testKeyFollowsTheDefaultTemplate() {
		node().process(thumbnailCtx());

		String key = store.keys(BUCKET).iterator().next();
		// {sourceNode}/{sourceKey} is the port's own id twice over now: the selector cannot know
		// which node filled the port, and recording the port is honest where guessing a node name
		// was not. Keys stay content-addressed, so a re-run is still free.
		assertThat(key).startsWith("cortex/artifacts/artifacts/").endsWith(".thumb");
	}

	@Test
	public void testContentTypeIsDerivedFromTheExtension() {
		node().process(thumbnailCtx());

		// .thumb is a JPEG - octet-stream here would make browsers download it.
		assertThat(store.contentTypeOf(BUCKET, store.keys(BUCKET).iterator().next())).isEqualTo("image/jpeg");
	}

	@Test
	public void testOutputsReportWhatHappened() {
		NodeResult result = node().process(thumbnailCtx());

		assertThat(result.get(S3SinkNode.OUT_FLAG)).isEqualTo("DONE");
		// scalar/integer is widened to Long at the port boundary.
		assertThat(result.get(S3SinkNode.OUT_COUNT)).isEqualTo(1L);
		JsonObject payload = new JsonObject(result.get(S3SinkNode.OUT_RESULT));
		assertThat(payload.getString("bucket")).isEqualTo(BUCKET);
		assertThat(payload.getJsonArray("artifacts").getJsonObject(0).getString("state")).isEqualTo("UPLOADED");
	}

	@Test
	public void testUploadsSeveralArtifacts() throws IOException {
		Path depth = write("depthmap_bin/cd/map.png", "depth-bytes");

		NodeResult result = node().process(ctx(thumb.toString(), depth.toString()));

		assertThat(result.getState()).isEqualTo(ResultState.SUCCESS);
		assertThat(store.keys(BUCKET)).hasSize(2);
		assertThat(result.get(S3SinkNode.OUT_COUNT)).isEqualTo(2L);
	}

	// --- idempotency ----------------------------------------------------------------------

	@Test
	public void testRerunSkipsAnObjectAlreadyPresent() {
		node().process(thumbnailCtx());
		store.resetCounters();

		NodeResult result = node().process(thumbnailCtx());

		assertThat(result.getState()).isEqualTo(ResultState.SUCCESS);
		// One HEAD, no PUT: that is what makes a re-run over a published corpus cheap.
		assertThat(store.uploadCalls).hasValue(0);
		assertThat(store.headCalls).hasValue(1);
	}

	@Test
	public void testOverwriteAlwaysSkipsTheHeadAndUploadsAgain() {
		node().process(thumbnailCtx());
		store.resetCounters();

		S3SinkNode always = node(new JsonObject().put("id", "archive").put("overwrite", "ALWAYS"));
		always.process(thumbnailCtx());

		assertThat(store.headCalls).hasValue(0);
		assertThat(store.uploadCalls).hasValue(1);
	}

	@Test
	public void testChangedArtifactBytesLandAtANewKey() throws IOException {
		node().process(thumbnailCtx());
		String firstKey = store.keys(BUCKET).iterator().next();

		Files.writeString(thumb, "different-bytes");
		node().process(thumbnailCtx());

		// The key is content-addressed, so different bytes cannot overwrite the old object.
		assertThat(store.keys(BUCKET)).hasSize(2).doesNotContainNull();
		assertThat(new String(store.bytes(BUCKET, firstKey), StandardCharsets.UTF_8))
			.isEqualTo("contact-sheet-bytes");
	}

	// --- selection ------------------------------------------------------------------------

	@Test
	public void testNoArtifactsIsASkipNotAFailure() {
		// A sink downstream of a producer that skipped this media type must not redden the run.
		NodeResult result = node().process(ctx());

		assertThat(result.getState()).isEqualTo(ResultState.SKIPPED);
		assertThat(store.uploadCalls).hasValue(0);
	}

	@Test
	public void testOnlyWhatIsWiredIntoThePortIsUploaded() throws IOException {
		// This replaces the old "explicit artifacts option wins over auto-discovery" case. Both the
		// option and the discovery are gone: choosing what to publish is now done by drawing the
		// edge, so the sink uploads exactly the elements its port carries and nothing more.
		Path depth = write("depthmap_bin/cd/map.png", "depth-bytes");

		node().process(ctx(depth.toString()));

		assertThat(store.keys(BUCKET)).hasSize(1);
		assertThat(new String(store.bytes(BUCKET, store.keys(BUCKET).iterator().next()),
			StandardCharsets.UTF_8)).isEqualTo("depth-bytes");
	}

	@Test
	public void testIncludeSourceUploadsTheMediaItem() throws IOException {
		write("library/clip.mp4", "video-bytes");
		S3SinkNode node = node(new JsonObject().put("id", "archive").put("includeSource", true));

		node.process(thumbnailCtx());

		assertThat(store.keys(BUCKET)).hasSize(2);
		assertThat(store.keys(BUCKET)).anyMatch(key -> key.contains("/media/"));
	}

	@Test
	public void testAlreadyRemoteMediaIsNotReUploaded() throws IOException {
		write("library/clip.mp4", "video-bytes");
		LoomMedia remote = media();
		when(remote.reference()).thenReturn("s3://other/clip.mp4");
		S3SinkNode node = node(new JsonObject().put("id", "archive").put("includeSource", true));

		node.process(ctx(remote, thumb.toString()));

		// Only the thumbnail: re-uploading bytes s3-source just fetched would be pure waste.
		assertThat(store.keys(BUCKET)).hasSize(1);
	}

	@Test
	public void testTooManyArtifactsFailsRatherThanTruncating() throws IOException {
		String[] elements = new String[5];
		for (int i = 0; i < elements.length; i++) {
			elements[i] = write("script_bin/n/" + i + ".png", "x" + i).toString();
		}
		S3SinkNode node = node(new JsonObject().put("id", "archive").put("maxArtifacts", 2));

		NodeResult result = node.process(ctx(elements));

		assertThat(result.getState()).isEqualTo(ResultState.FAILED);
		assertThat(result.getMessage()).contains("maxArtifacts");
	}

	@Test
	public void testOversizedArtifactFails() {
		S3SinkNode node = node(new JsonObject().put("id", "archive").put("maxArtifactBytes", 5));

		NodeResult result = node.process(thumbnailCtx());

		assertThat(result.getState()).isEqualTo(ResultState.FAILED);
		assertThat(result.getMessage()).contains("maxArtifactBytes");
	}

	// --- failure behaviour ----------------------------------------------------------------

	@Test
	public void testMissingArtifactFileFailsWithTheAffinityHint() {
		NodeResult result = node().process(ctx(metaPath.resolve("thumbnail_bin/gone.thumb").toString()));

		// The single most important failure to get right: a sink that silently uploads nothing
		// looks like success.
		assertThat(result.getState()).isEqualTo(ResultState.FAILED);
		assertThat(result.getMessage()).contains("same worker");
	}

	@Test
	public void testUploadFailureIsReportedAsFailedNotSuccess() {
		store.failUploadWith(new IOException("bucket is full"));

		NodeResult result = node().process(thumbnailCtx());

		assertThat(result.getState()).isEqualTo(ResultState.FAILED);
		assertThat(result.getMessage()).contains("bucket is full");
	}

	@Test
	public void testPartialFailureStillUploadsWhatItCan() throws IOException {
		Path depth = write("depthmap_bin/cd/map.png", "depth-bytes");
		store.failUploadWith(new IOException("transient"));

		NodeResult result = node().process(ctx(thumb.toString(), depth.toString()));

		// One failure must never abandon the rest - uploading what can be uploaded is strictly
		// better, and the IF_DIFFERENT skip means a retry only redoes the failure.
		assertThat(result.getState()).isEqualTo(ResultState.FAILED);
		assertThat(store.keys(BUCKET)).hasSize(1);
		assertThat(result.getMessage()).contains("uploaded 1 of 2");
		// Note: NodeContextImpl.abort() returns an empty output map by design, so the per-artifact
		// detail is only reachable through the persisted component - see S3SinkNodePersistenceTest.
	}

	@Test
	public void testFailOnPartialCanBeDisabled() throws IOException {
		Path depth = write("depthmap_bin/cd/map.png", "depth-bytes");
		store.failUploadWith(new IOException("transient"));
		S3SinkNode node = node(new JsonObject().put("id", "archive").put("failOnPartial", false));

		NodeResult result = node.process(ctx(thumb.toString(), depth.toString()));

		assertThat(result.getState()).isEqualTo(ResultState.SUCCESS);
		assertThat(result.get(S3SinkNode.OUT_FLAG)).isEqualTo("PARTIAL");
	}

	@Test
	public void testInactiveS3FailsRatherThanSkipping() {
		S3SinkNode node = new S3SinkNode(null, cortexOptions, new S3SinkNodeOptions(), S3Support.inactive());
		node.configure(new JsonObject().put("id", "archive").put("bucket", BUCKET));

		NodeResult result = node.process(thumbnailCtx());

		// Skipping would be silent data loss on a green run.
		assertThat(result.getState()).isEqualTo(ResultState.FAILED);
		assertThat(result.getMessage()).contains("CORTEX_S3_ENDPOINT");
	}

	// --- deleteAfterUpload ----------------------------------------------------------------

	@Test
	public void testLocalFileIsKeptByDefault() {
		node().process(thumbnailCtx());

		// scene-layout reads depthmap_path off the same disk; deleting by default would break it.
		assertThat(thumb).exists();
	}

	@Test
	public void testDeleteAfterUploadRemovesTheLocalFile() {
		S3SinkNode node = node(new JsonObject().put("id", "archive").put("deleteAfterUpload", true));

		node.process(thumbnailCtx());

		assertThat(thumb).doesNotExist();
	}

	@Test
	public void testDeleteAfterUploadNeverRemovesTheMediaItem() throws IOException {
		Path source = write("library/clip.mp4", "video-bytes");
		S3SinkNode node = node(new JsonObject().put("id", "archive")
			.put("includeSource", true).put("deleteAfterUpload", true));

		node.process(thumbnailCtx());

		assertThat(source).as("deleting the pipeline's input would be catastrophic").exists();
	}

	@Test
	public void testDeleteAfterUploadRefusesFilesOutsideTheMetaPath(@TempDir Path elsewhere) throws IOException {
		Path outside = Files.writeString(elsewhere.resolve("stray.png"), "x");
		S3SinkNode node = node(new JsonObject().put("id", "archive").put("deleteAfterUpload", true));

		node.process(ctx(outside.toString()));

		assertThat(outside).exists();
	}

	@Test
	public void testFailedUploadDoesNotDeleteTheLocalFile() {
		store.failUploadWith(new IOException("nope"));
		S3SinkNode node = node(new JsonObject().put("id", "archive").put("deleteAfterUpload", true));

		node.process(thumbnailCtx());

		assertThat(thumb).exists();
	}

	// --- configuration --------------------------------------------------------------------

	@Test
	public void testConfigureRequiresABucket() {
		S3SinkNode node = new S3SinkNode(null, cortexOptions, new S3SinkNodeOptions(), support());

		org.assertj.core.api.Assertions
			.assertThatThrownBy(() -> node.configure(new JsonObject().put("id", "archive")))
			.isInstanceOf(IllegalStateException.class).hasMessageContaining("bucket");
	}

	@Test
	public void testConfigureRejectsABadKeyTemplate() {
		S3SinkNode node = new S3SinkNode(null, cortexOptions, new S3SinkNodeOptions(), support());

		org.assertj.core.api.Assertions
			.assertThatThrownBy(() -> node.configure(new JsonObject().put("bucket", BUCKET)
				.put("keyTemplate", "a/{bogus}")))
			.isInstanceOf(IllegalStateException.class).hasMessageContaining("{bogus}");
	}

	@Test
	public void testCustomKeyTemplateIsUsed() {
		S3SinkNode node = node(new JsonObject().put("id", "archive")
			.put("keyTemplate", "flat/{nodeId}/{basename}{ext}"));

		node.process(thumbnailCtx());

		assertThat(store.keys(BUCKET)).containsExactly("flat/archive/sheet.thumb");
	}

	@Test
	public void testTwoSinkInstancesUseTheirOwnIds() {
		S3SinkNode a = node(new JsonObject().put("id", "archive").put("keyTemplate", "{nodeId}/x"));
		S3SinkNode b = node(new JsonObject().put("id", "backup").put("keyTemplate", "{nodeId}/x"));

		a.process(thumbnailCtx());
		b.process(thumbnailCtx());

		assertThat(store.keys(BUCKET)).containsExactlyInAnyOrder("archive/x", "backup/x");
	}

	@Test
	public void testDisabledNodeSkips() {
		S3SinkNodeOptions options = new S3SinkNodeOptions().setBucket(BUCKET);
		options.setEnabled(false);
		S3SinkNode node = new S3SinkNode(null, cortexOptions, options, support());

		assertThat(node.process(thumbnailCtx()).getState()).isEqualTo(ResultState.SKIPPED);
	}

	@Test
	public void testNodeReportsItsKind() {
		assertThat(node().name()).isEqualTo("s3-sink");
	}
}
