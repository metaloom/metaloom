package io.metaloom.loom.cortex.dedup;

import static io.metaloom.cortex.media.test.assertj.NodeAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.common.media.LoomMediaLoader;
import io.metaloom.cortex.node.dedup.DedupNodeOptions;
import io.metaloom.cortex.node.dedup.HashDedupNode;
import io.metaloom.cortex.pipeline.test.StubLoomMedia;
import io.metaloom.loom.client.common.LoomClientRequest;
import io.metaloom.loom.client.http.LoomHttpClient;
import io.metaloom.loom.client.http.impl.LoomClientResponseImpl;
import io.metaloom.loom.rest.model.asset.AssetResponse;
import io.metaloom.loom.rest.model.asset.info.FileInfo;
import io.metaloom.loom.rest.model.noderesult.NodeResultResponse;
import io.metaloom.utils.hash.HashUtils;
import io.metaloom.utils.hash.SHA512;

/**
 * Unit tests for the exact-hash dedup node.
 *
 * <p>
 * The size-mismatch case is the reason this class exists: it used to call {@code System.in.read()}, which hangs a headless worker forever waiting for
 * a keypress nobody is there to give. The {@link Timeout} on that test is the actual assertion - it fails by hanging if the blocking read ever comes
 * back.
 * </p>
 */
class HashDedupNodeTest {

	@TempDir
	File tempDir;

	private LoomHttpClient client;
	private LoomMediaLoader loader;
	private CortexOptions cortexOptions;
	private Path dupFolder;

	private File localFile;
	private SHA512 hash;

	@BeforeEach
	@SuppressWarnings("unchecked")
	void setup() throws Exception {
		cortexOptions = new CortexOptions().setMetaPath(tempDir.toPath());
		client = mock(LoomHttpClient.class);
		loader = mock(LoomMediaLoader.class);
		dupFolder = new File(tempDir, "duplicates").toPath();

		LoomClientRequest<NodeResultResponse> ledger = mock(LoomClientRequest.class);
		when(ledger.sync()).thenReturn(new LoomClientResponseImpl<>(new NodeResultResponse().setUuid(UUID.randomUUID()), 201, "Created", Map.of()));
		when(client.createAssetNodeResult(any(), any())).thenReturn(ledger);

		localFile = new File(tempDir, "local-copy.mp4");
		Files.write(localFile.toPath(), "identical bytes".getBytes());
		hash = HashUtils.computeSHA512(localFile);
	}

	private HashDedupNode node() {
		return new HashDedupNode(client, cortexOptions, new DedupNodeOptions().setDupFolder(dupFolder), loader);
	}

	private StubLoomMedia mediaOf(File file) {
		StubLoomMedia media = new StubLoomMedia(file.getAbsolutePath(), true, false, false, false);
		media.setSHA512(hash);
		return media;
	}

	/** Stub the asset Loom already knows for this hash, recorded at {@code knownPath}. */
	@SuppressWarnings("unchecked")
	private void knownAssetAt(File knownPath) throws Exception {
		LoomClientRequest<AssetResponse> req = mock(LoomClientRequest.class);
		when(req.sync()).thenReturn(new LoomClientResponseImpl<>(new AssetResponse()
			.setUuid(UUID.randomUUID())
			.setFile(new FileInfo().setFilename(knownPath.getAbsolutePath()).setSize(knownPath.length())), 200, "OK", Map.of()));
		when(client.loadAsset(nullable(SHA512.class))).thenReturn(req);
	}

	private boolean localWasMoved() {
		return !localFile.exists() && new File(dupFolder.toFile(), localFile.getName()).exists();
	}

	@Test
	void testMovesALocalCopyOfAnAlreadyKnownFile() throws Exception {
		File known = new File(tempDir, "original.mp4");
		Files.write(known.toPath(), "identical bytes".getBytes());
		knownAssetAt(known);
		when(loader.load(known.toPath())).thenReturn(mediaOf(known));

		assertThat(node().process(NodeContext.create(mediaOf(localFile)))).isSuccess();

		assertTrue(localWasMoved(), "A second copy of a file Loom already has must be moved aside");
		assertTrue(known.exists(), "The known original must stay where it is");
	}

	/**
	 * A local file whose size disagrees with the database record must be reported and skipped - never moved, and above all never blocked on.
	 */
	@Test
	@Timeout(10)
	void testASizeMismatchIsSkippedRatherThanBlockingForInput() throws Exception {
		File known = new File(tempDir, "original.mp4");
		Files.write(known.toPath(), "identical bytes".getBytes());
		knownAssetAt(known);

		// The media handle reports a different size than the file on disk actually has - the inconsistency the node must refuse to guess about.
		StubLoomMedia inconsistent = new StubLoomMedia(known.getAbsolutePath(), true, false, false, false) {
			@Override
			public long size() {
				return known.length() + 4096L;
			}
		};
		inconsistent.setSHA512(hash);
		when(loader.load(known.toPath())).thenReturn(inconsistent);

		assertThat(node().process(NodeContext.create(mediaOf(localFile)))).isSkipped();

		assertFalse(localWasMoved(), "Records that disagree are exactly when a move could destroy the only good copy");
		assertTrue(known.exists());
	}

	@Test
	void testTheSameFileIsNotMovedOntoItself() throws Exception {
		knownAssetAt(localFile);
		when(loader.load(localFile.toPath())).thenReturn(mediaOf(localFile));

		node().process(NodeContext.create(mediaOf(localFile)));

		assertTrue(localFile.exists(), "The recorded path and the local path are the same file - there is nothing to deduplicate");
		assertFalse(localWasMoved());
	}

	@Test
	void testAnUnknownFileIsSkipped() throws Exception {
		@SuppressWarnings("unchecked")
		LoomClientRequest<AssetResponse> req = mock(LoomClientRequest.class);
		when(req.sync()).thenReturn(new LoomClientResponseImpl<>(null, 404, "Not Found", Map.of()));
		when(client.loadAsset(nullable(SHA512.class))).thenReturn(req);

		assertThat(node().process(NodeContext.create(mediaOf(localFile)))).isSkipped();

		assertFalse(localWasMoved());
	}
}
