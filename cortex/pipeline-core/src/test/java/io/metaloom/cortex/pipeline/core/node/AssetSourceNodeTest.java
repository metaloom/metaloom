package io.metaloom.cortex.pipeline.core.node;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.ResultState;
import io.metaloom.utils.hash.SHA512;

class AssetSourceNodeTest {

	@Test
	void testEmitsOnlySingleAssetPerRun() {
		LoomMedia asset = new StubLoomMedia("/tmp/my-asset.mp4");
		AssetSourceNode node = new AssetSourceNode(asset);

		node.initialize();
		NodeResult first = node.process(null, Map.of());
		NodeResult second = node.process(null, Map.of());

		assertEquals(ResultState.SUCCESS, first.getState());
		assertEquals("/tmp/my-asset.mp4", first.getOutput("path"));
		assertEquals("asset", first.getOutput("source"));

		assertEquals(ResultState.SKIPPED, second.getState());
		assertTrue(node.isSource());
		assertSame(asset, node.asset());
	}

	private static class StubLoomMedia implements LoomMedia {

		private final String path;
		private SHA512 sha512;

		private StubLoomMedia(String path) {
			this.path = path;
		}

		@Override
		public SHA512 getSHA512() {
			return sha512;
		}

		@Override
		public void setSHA512(SHA512 hash) {
			this.sha512 = hash;
		}

		@Override
		public boolean hasSHA512() {
			return sha512 != null;
		}

		@Override
		public boolean isVideo() {
			return true;
		}

		@Override
		public boolean isImage() {
			return false;
		}

		@Override
		public boolean isAudio() {
			return false;
		}

		@Override
		public boolean isDocument() {
			return false;
		}

		@Override
		public File file() {
			return new File(path);
		}

		@Override
		public Path path() {
			return Path.of(path);
		}

		@Override
		public void setPath(Path path) {
			// NOOP
		}

		@Override
		public long size() {
			return 1_024L;
		}

		@Override
		public String absolutePath() {
			return path;
		}

		@Override
		public boolean exists() {
			return true;
		}

		@Override
		public InputStream open() throws FileNotFoundException {
			throw new FileNotFoundException(path);
		}

		@Override
		public List<String> listXAttr() {
			return List.of();
		}
	}
}
