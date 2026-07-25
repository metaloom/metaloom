package io.metaloom.loom.test.integration.node;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.ResultState;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.common.media.impl.LoomMediaImpl;
import io.metaloom.cortex.node.scene.SceneDetectionNode;
import io.metaloom.cortex.node.scene.SceneDetectionOptions;
import io.metaloom.loom.client.http.LoomHttpClient;
import io.metaloom.loom.rest.model.asset.AssetCreateRequest;
import io.metaloom.loom.rest.model.asset.AssetResponse;
import io.metaloom.loom.rest.model.asset.info.FileInfo;
import io.metaloom.loom.rest.model.asset.info.HashInfo;
import io.metaloom.loom.rest.model.segmentcomp.SegmentCompResponse;
import io.metaloom.utils.hash.HashUtils;
import io.metaloom.utils.hash.SHA512;

/**
 * Integration test for {@code SceneDetectionNode}. The node runs real optical-flow / frame-difference scene detection (OpenCV) on a real video and writes
 * the scene set to {@code asset_segment_comp}; the test reads the segments back through REST and asserts the persisted shape: frame-indexed bounds plus the
 * source frame rate carried in {@code producerVersion}.
 */
public class SceneDetectionNodeIntegrationTest extends AbstractNodeIntegrationTest {

	@Test
	public void testSceneDetectionPersistsSegments() throws Exception {
		withLoom(client -> {
			assumeVideo4j();
			AssetResponse asset = getOrCreateAsset(client, video1(), "video/mp4");

			SceneDetectionNode node = new SceneDetectionNode(client, cortexOptions(), new SceneDetectionOptions());
			NodeResult result = node.process(NodeContext.create(media(video1())));
			assertThat(result.getState()).isEqualTo(ResultState.SUCCESS);

			List<SegmentCompResponse> segments = client.listAssetSegmentComps(asset.getUuid()).sync().body().getData();
			assertThat(segments).as("scene segments must be readable via REST").isNotEmpty();
			assertSegmentShape(segments);
		});
	}

	/**
	 * Drives the node against a genuinely multi-shot clip and asserts that <b>multiple</b> scene boundaries are persisted and read back. Gated on
	 * {@code SCENE_IT_VIDEO} (an existing multi-cut file) so it stays inert in ordinary CI.
	 */
	@Test
	public void testMultiSceneClipPersistsMultipleSegments() throws Exception {
		String path = System.getenv("SCENE_IT_VIDEO");
		assumeTrue(path != null && !path.isBlank() && new File(path).exists(), "Set SCENE_IT_VIDEO to a multi-scene clip to run this");
		File file = new File(path);

		withLoom(client -> {
			assumeVideo4j();
			AssetResponse asset = createAssetForFile(client, file);

			LoomMediaImpl media = new LoomMediaImpl(file.toPath());
			media.setSHA512(HashUtils.computeSHA512(file));

			SceneDetectionNode node = new SceneDetectionNode(client, cortexOptions(), new SceneDetectionOptions());
			NodeResult result = node.process(NodeContext.create(media));
			assertThat(result.getState()).isEqualTo(ResultState.SUCCESS);

			List<SegmentCompResponse> segments = client.listAssetSegmentComps(asset.getUuid()).sync().body().getData();
			System.out.println("[scene-it] persisted " + segments.size() + " segment(s):");
			segments.forEach(s -> System.out.printf("  seq=%d frames %d-%d producerVersion=%s%n", s.getSeq(), s.getTimeFrom(), s.getTimeTo(),
				s.getProducerVersion()));
			assertThat(segments).as("a multi-cut clip must yield more than one scene").hasSizeGreaterThan(1);
			assertSegmentShape(segments);
		});
	}

	/** Assert the invariants of a persisted scene set: contiguous seq, frame-indexed bounds, and the fps carried in producerVersion. */
	private void assertSegmentShape(List<SegmentCompResponse> segments) {
		int expectedSeq = 0;
		for (SegmentCompResponse s : segments) {
			assertThat(s.getSegmentType()).isEqualTo("SCENE");
			assertThat(s.getSeq()).as("seq assigned by list position").isEqualTo(expectedSeq++);
			assertThat(s.getTimeTo()).as("frame end after start").isGreaterThan(s.getTimeFrom());
			assertThat(s.getProducerVersion()).as("source fps carried for frame->time conversion").startsWith("fps=");
		}
	}

	private AssetResponse createAssetForFile(LoomHttpClient client, File file) throws Exception {
		SHA512 sha512 = HashUtils.computeSHA512(file);
		AssetResponse existing = null;
		try {
			existing = client.loadAsset(sha512).sync().body();
		} catch (Exception ignore) {
			// fall through to create
		}
		if (existing != null) {
			return existing;
		}
		AssetCreateRequest request = new AssetCreateRequest();
		request.setFile(new FileInfo()
			.setFilename(file.getName())
			.setMimeType("video/mp4")
			.setOrigin(file.getAbsolutePath())
			.setSize(file.length()));
		request.setHashes(new HashInfo().setSHA512(sha512));
		return client.createAsset(request).sync().body();
	}
}
