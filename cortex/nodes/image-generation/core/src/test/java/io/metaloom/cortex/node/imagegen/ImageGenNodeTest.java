package io.metaloom.cortex.node.imagegen;

import static io.metaloom.cortex.media.test.assertj.NodeAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.io.TempDir;

import io.metaloom.cortex.api.node.NodeInputs;
import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.pipeline.test.StubLoomMedia;
import io.metaloom.utils.hash.SHA512;

/**
 * Deterministic unit test for {@link ImageGenNode}. The FastAPI sidecar is replaced by a mocked {@link ImageGenClient}, so no server is required.
 * Verifies the prompt-in / image-out flow: the PNG is written under {@code metaPath/imagegen_bin}, the output keys are emitted, both GENERATE and
 * REMIX modes call the right endpoint, and the node self-skips for non-image media.
 */
class ImageGenNodeTest {

	private static final SHA512 HASH = SHA512.fromString(
		"e7c22b994c59d9cf2b48e549b1e24666636045930d3da7c1acb299d1c3b7f931f94aae41edda2c2b207a36e10f8bcb8d45223e54878f5b316e7ce3b6bc019629");

	private static final byte[] FAKE_PNG = "fake-image-bytes".getBytes();

	/** What the sidecar hands back. The model id is what lands on the ledger row as
	 * producerVersion, so it is part of the fixture rather than a null. */
	private static final String MODEL_ID = "Qwen/Qwen-Image-2.1";

	private static final ImageGenResult FAKE_RESULT = new ImageGenResult(FAKE_PNG, MODEL_ID);

	@TempDir
	File tempDir;

	private CortexOptions cortexOptions;
	private ImageGenClient client;
	private StubLoomMedia media;

	@BeforeEach
	void setup() throws Exception {
		cortexOptions = new CortexOptions().setMetaPath(tempDir.toPath());

		client = mock(ImageGenClient.class);
		when(client.generate(anyString(), anyInt(), anyInt(), nullable(Integer.class), anyInt(), nullable(String.class), anyDouble()))
			.thenReturn(FAKE_RESULT);
		when(client.remix(any(BufferedImage.class), anyString(), anyDouble(), nullable(Integer.class), anyInt(), nullable(String.class),
			anyDouble())).thenReturn(FAKE_RESULT);
		when(client.edit(anyList(), nullable(byte[].class), nullable(String.class), anyString(), nullable(Integer.class), anyInt(),
			nullable(String.class), anyDouble(), anyInt(), anyBoolean())).thenReturn(FAKE_RESULT);
		when(client.mask(any(BufferedImage.class), anyString(), nullable(Integer.class), anyInt(), anyInt())).thenReturn(FAKE_RESULT);

		// Write a real image so REMIX's ImageIO.read succeeds; GENERATE ignores the pixels.
		File imageFile = new File(tempDir, "asset.png");
		ImageIO.write(new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB), "png", imageFile);
		media = new StubLoomMedia(imageFile.getAbsolutePath(), false, true, false, false);
		media.setSHA512(HASH);
	}

	private ImageGenNode node(ImageGenNodeOptions options) {
		return new ImageGenNode(null, cortexOptions, options, client);
	}

	private ImageGenNodeOptions options() {
		return new ImageGenNodeOptions().setPrompt("a red apple");
	}

	@Test
	void testGeneratesAndWritesPng() throws Exception {
		NodeResult result = node(options()).process(NodeContext.create(media));
		assertThat(result).isSuccess();

		String outPath = result.get(ImageGenNode.OUT_IMAGE);
		assertNotNull(outPath, "The node should emit the generated image path");
		assertEquals("DONE", result.get(ImageGenNode.OUT_FLAG));

		Path png = Path.of(outPath);
		assertTrue(Files.exists(png), "The PNG file should be written to the imagegen_bin cache");
		assertTrue(png.startsWith(tempDir.toPath().resolve("imagegen_bin")), "The PNG should live under metaPath/imagegen_bin");
		assertEquals(FAKE_PNG.length, Files.size(png));

		verify(client).generate(eq("a red apple"), anyInt(), anyInt(), nullable(Integer.class), anyInt(), nullable(String.class), anyDouble());
	}

	@Test
	void testRemixModeCallsRemixEndpoint() throws Exception {
		NodeResult result = node(options().setMode(ImageGenMode.REMIX)).process(NodeContext.create(media));
		assertThat(result).isSuccess();
		assertEquals("DONE", result.get(ImageGenNode.OUT_FLAG));
		verify(client).remix(any(BufferedImage.class), eq("a red apple"), anyDouble(), nullable(Integer.class), anyInt(), nullable(String.class), anyDouble());
	}

	@Test
	void testSkippedForNonImage() {
		StubLoomMedia video = new StubLoomMedia(media.absolutePath(), true, false, false, false);
		video.setSHA512(HASH);
		NodeResult result = node(options()).process(NodeContext.create(video));
		assertThat(result).isSkipped();
	}

	@Test
	void testSecondRunServedFromCache() throws Exception {
		ImageGenNode node = node(options());
		NodeResult first = node.process(NodeContext.create(media));
		assertThat(first).isSuccess();

		NodeResult second = node.process(NodeContext.create(media));
		assertThat(second).isSuccess();
		assertEquals(first.get(ImageGenNode.OUT_IMAGE), second.get(ImageGenNode.OUT_IMAGE));

		// The sidecar must be hit exactly once - the second run is served from the in-heap cache.
		verify(client, times(1)).generate(anyString(), anyInt(), anyInt(), nullable(Integer.class), anyInt(), nullable(String.class), anyDouble());
	}

	/**
	 * The cache hit must be visible as provenance: real work reports {@code COMPUTED}, the replay
	 * reports {@code LOCAL} — the distinction the ledger records since the origin stopped being a
	 * constant.
	 */
	@Test
	void testCacheHitReportsLocalOrigin() {
		ImageGenNode node = node(options());
		NodeResult first = node.process(NodeContext.create(media));
		assertEquals(io.metaloom.cortex.api.node.ResultOrigin.COMPUTED, first.getOrigin());

		NodeResult second = node.process(NodeContext.create(media));
		assertEquals(io.metaloom.cortex.api.node.ResultOrigin.LOCAL, second.getOrigin());
	}

	/**
	 * Two instances in one graph differing only in their configuration — the obvious way to render
	 * two prompts — must write two distinct files and carry two distinct ledger ids. Before the
	 * options digest they wrote to the same {@code <sha512>.png} path and served each other's
	 * cached result.
	 */
	@Test
	void testTwoInstancesDifferingOnlyByOptionsWriteDistinctFiles() throws Exception {
		ImageGenNode first = node(options());
		first.configure(new io.vertx.core.json.JsonObject().put("id", "gen-apple").put("prompt", "a red apple"));
		ImageGenNode second = node(options());
		second.configure(new io.vertx.core.json.JsonObject().put("id", "gen-pear").put("prompt", "a blue pear"));

		NodeResult firstResult = first.process(NodeContext.create(media));
		NodeResult secondResult = second.process(NodeContext.create(media));
		assertThat(firstResult).isSuccess();
		assertThat(secondResult).isSuccess();

		String firstPath = firstResult.get(ImageGenNode.OUT_IMAGE);
		String secondPath = secondResult.get(ImageGenNode.OUT_IMAGE);
		assertTrue(!firstPath.equals(secondPath), "Two differently configured instances must not share an output path");
		assertTrue(Files.exists(Path.of(firstPath)));
		assertTrue(Files.exists(Path.of(secondPath)));

		// Each instance renders its own prompt - neither may be served the other's cached result.
		verify(client).generate(eq("a red apple"), anyInt(), anyInt(), nullable(Integer.class), anyInt(), nullable(String.class), anyDouble());
		verify(client).generate(eq("a blue pear"), anyInt(), anyInt(), nullable(Integer.class), anyInt(), nullable(String.class), anyDouble());

		// The ledger identity is the graph-local id, so the two rows upsert side by side instead of
		// overwriting each other on (asset_uuid, node_kind, node_id).
		assertEquals("gen-apple", first.nodeId());
		assertEquals("gen-pear", second.nodeId());
	}

	// ----------------------------------------------------------------------------------------
	// EDIT and MASK - the multi-image modes. The assertions that matter are about WHICH images
	// reach the client and in WHAT ORDER: the sidecar edits element 0 and treats the rest as
	// references, so a list in the wrong order is a picture of the wrong thing, and no output
	// check would catch it.
	// ----------------------------------------------------------------------------------------

	/** An image on disk standing in for another node's output, as an artifact/image port carries. */
	private String artifact(String name) throws Exception {
		File file = new File(tempDir, name);
		ImageIO.write(new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB), "png", file);
		return file.getAbsolutePath();
	}

	@Test
	void testEditModeSendsTheAssetImageFirstThenTheReferences() throws Exception {
		NodeInputs inputs = NodeInputs.builder()
			.inputs(ImageGenNode.IN_REFERENCES, List.of(artifact("ref-a.png"), artifact("ref-b.png")))
			.build();

		NodeResult result = node(options().setMode(ImageGenMode.EDIT)).process(NodeContext.create(media, inputs));
		assertThat(result).isSuccess();

		ArgumentCaptor<List<BufferedImage>> images = ArgumentCaptor.forClass(List.class);
		verify(client).edit(images.capture(), nullable(byte[].class), nullable(String.class), eq("a red apple"),
			nullable(Integer.class), anyInt(), nullable(String.class), anyDouble(), anyInt(), anyBoolean());

		// The asset's own 8x8 image, then the two 4x4 references - the order the sidecar relies on.
		assertEquals(3, images.getValue().size(), "the asset image plus both references");
		assertEquals(8, images.getValue().get(0).getWidth(), "element 0 must be the image being edited");
		assertEquals(4, images.getValue().get(1).getWidth());
	}

	@Test
	void testEditModeWithNoReferencesStillSendsTheAssetImage() throws Exception {
		assertThat(node(options().setMode(ImageGenMode.EDIT)).process(NodeContext.create(media))).isSuccess();

		ArgumentCaptor<List<BufferedImage>> images = ArgumentCaptor.forClass(List.class);
		verify(client).edit(images.capture(), nullable(byte[].class), nullable(String.class), anyString(),
			nullable(Integer.class), anyInt(), nullable(String.class), anyDouble(), anyInt(), anyBoolean());
		assertEquals(1, images.getValue().size());
	}

	/**
	 * A wired mask beats the configured maskPrompt, on the same reasoning as the prompt port. Only
	 * one of the two may be sent - the sidecar rejects both together, deliberately, because they
	 * answer the same question.
	 */
	@Test
	void testAWiredMaskWinsOverTheConfiguredMaskPrompt() throws Exception {
		NodeInputs inputs = NodeInputs.builder()
			.input(ImageGenNode.IN_MASK, artifact("mask.png"))
			.build();

		ImageGenNodeOptions options = options().setMode(ImageGenMode.EDIT).setMaskPrompt("the boy's hair");
		assertThat(node(options).process(NodeContext.create(media, inputs))).isSuccess();

		ArgumentCaptor<byte[]> mask = ArgumentCaptor.forClass(byte[].class);
		ArgumentCaptor<String> maskPrompt = ArgumentCaptor.forClass(String.class);
		verify(client).edit(anyList(), mask.capture(), maskPrompt.capture(), anyString(), nullable(Integer.class), anyInt(),
			nullable(String.class), anyDouble(), anyInt(), anyBoolean());

		assertNotNull(mask.getValue(), "the wired mask's bytes should be sent");
		assertNull(maskPrompt.getValue(), "the configured maskPrompt must not be sent alongside it");
	}

	/** With no mask wired, the configured maskPrompt is what asks the sidecar to derive one. */
	@Test
	void testAnUnwiredMaskPortFallsBackToTheMaskPrompt() throws Exception {
		ImageGenNodeOptions options = options().setMode(ImageGenMode.EDIT).setMaskPrompt("the boy's hair");
		assertThat(node(options).process(NodeContext.create(media))).isSuccess();

		verify(client).edit(anyList(), nullable(byte[].class), eq("the boy's hair"), anyString(), nullable(Integer.class), anyInt(),
			nullable(String.class), anyDouble(), anyInt(), anyBoolean());
	}

	@Test
	void testMaskModeCallsTheMaskEndpointWithTheConfiguredSubject() throws Exception {
		ImageGenNodeOptions options = options().setMode(ImageGenMode.MASK).setMaskPrompt("the boy's hair");
		NodeResult result = node(options).process(NodeContext.create(media));
		assertThat(result).isSuccess();

		verify(client).mask(any(BufferedImage.class), eq("the boy's hair"), nullable(Integer.class), anyInt(), anyInt());
		verify(client, never()).generate(anyString(), anyInt(), anyInt(), nullable(Integer.class), anyInt(), nullable(String.class),
			anyDouble());
	}

	/**
	 * In MASK mode the prompt names a REGION, not a picture, so a wired prompt port still wins -
	 * an upstream LLM answering "which part of this do you mean" is the useful thing to connect.
	 */
	@Test
	void testMaskModeHonoursAWiredPromptPortOverTheMaskPrompt() throws Exception {
		NodeInputs inputs = NodeInputs.builder().input(ImageGenNode.IN_PROMPT, "the red car").build();
		ImageGenNodeOptions options = options().setMode(ImageGenMode.MASK).setMaskPrompt("the boy's hair");

		assertThat(node(options).process(NodeContext.create(media, inputs))).isSuccess();
		verify(client).mask(any(BufferedImage.class), eq("the red car"), nullable(Integer.class), anyInt(), anyInt());
	}

	/**
	 * The reference paths are digest material, so the same asset edited against different
	 * references must not collide on one file name and serve the other's picture - the bug the
	 * options digest was introduced to fix, which a new input port could quietly reintroduce.
	 */
	@Test
	void testDifferentReferencesWriteDistinctFiles() throws Exception {
		NodeInputs first = NodeInputs.builder().inputs(ImageGenNode.IN_REFERENCES, List.of(artifact("ref-a.png"))).build();
		NodeInputs second = NodeInputs.builder().inputs(ImageGenNode.IN_REFERENCES, List.of(artifact("ref-b.png"))).build();

		String firstPath = node(options().setMode(ImageGenMode.EDIT)).process(NodeContext.create(media, first))
			.get(ImageGenNode.OUT_IMAGE);
		String secondPath = node(options().setMode(ImageGenMode.EDIT)).process(NodeContext.create(media, second))
			.get(ImageGenNode.OUT_IMAGE);

		assertNotEquals(firstPath, secondPath, "a different reference image must produce a different artifact path");
	}

	/**
	 * An artifact path is worker-local. A missing file almost always means the producing node ran
	 * on another worker, so the message has to say so - otherwise it sends people to the file
	 * system instead of to their affinity groups. Both artifact ports must say it, not just one.
	 */
	@Test
	void testAMissingMaskFailsWithAnAffinityHintToo() throws Exception {
		NodeInputs inputs = NodeInputs.builder()
			.input(ImageGenNode.IN_MASK, new File(tempDir, "no-such-mask.png").getAbsolutePath())
			.build();

		NodeResult result = node(options().setMode(ImageGenMode.EDIT)).process(NodeContext.create(media, inputs));
		assertThat(result).isFailed();
		assertTrue(result.getMessage().contains("affinity group"), "expected an affinity hint, got: " + result.getMessage());
		assertTrue(result.getMessage().contains("region mask"), "the message should name which port: " + result.getMessage());
	}

	@Test
	void testAMissingReferenceFailsWithAnAffinityHint() throws Exception {
		NodeInputs inputs = NodeInputs.builder()
			.inputs(ImageGenNode.IN_REFERENCES, List.of(new File(tempDir, "never-written.png").getAbsolutePath()))
			.build();

		NodeResult result = node(options().setMode(ImageGenMode.EDIT)).process(NodeContext.create(media, inputs));
		assertThat(result).isFailed();
		assertTrue(result.getMessage().contains("affinity group"), "expected an affinity hint, got: " + result.getMessage());
	}
}
