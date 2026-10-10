package io.metaloom.cortex.node.hash;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.attribute.FileTime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.ResultOrigin;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.pipeline.test.StubLoomMedia;
import io.metaloom.utils.hash.HashUtils;

/**
 * The in-heap skip caches must never answer for bytes they did not hash.
 *
 * <p>
 * They used to be keyed by path alone, so a file rewritten in place - or hashed while still being copied in - kept returning its first digest for the
 * worker's whole life. For {@code sha512} that digest is the media's identity, which then pointed every later node at the wrong asset.
 * </p>
 */
class HashNodeLocalCacheTest {

	@TempDir
	File tempDir;

	private HashNodeOptions options() {
		HashNodeOptions options = mock(HashNodeOptions.class);
		when(options.isEnabled()).thenReturn(true);
		when(options.isSHA512()).thenReturn(true);
		when(options.isMD5()).thenReturn(true);
		return options;
	}

	@Test
	void testSha512OfARewrittenFileIsRecomputed() throws Exception {
		SHA512Node node = new SHA512Node(null, new CortexOptions(), options());
		StubLoomMedia media = StubLoomMedia.ofBytes(tempDir, "clip.bin", "first");

		NodeResult first = node.process(NodeContext.create(media));
		assertEquals(HashUtils.computeSHA512(media.file()).toString(), first.get(SHA512Node.OUT_HASH));

		// Same path, same instant would be indistinguishable; a real rewrite moves the mtime.
		Files.writeString(media.file().toPath(), "second, longer content");
		Files.setLastModifiedTime(media.file().toPath(), FileTime.fromMillis(media.file().lastModified() + 5_000));

		NodeResult second = node.process(NodeContext.create(media));
		assertEquals(HashUtils.computeSHA512(media.file()).toString(), second.get(SHA512Node.OUT_HASH));
		assertEquals(ResultOrigin.COMPUTED, second.getOrigin());
	}

	@Test
	void testAnUnchangedFileIsStillACacheHit() throws Exception {
		MD5Node node = new MD5Node(null, new CortexOptions(), options());
		StubLoomMedia media = StubLoomMedia.ofBytes(tempDir, "clip.bin", "payload");

		node.process(NodeContext.create(media));
		NodeResult again = node.process(NodeContext.create(media));

		assertEquals(ResultOrigin.LOCAL, again.getOrigin());
		assertEquals(HashUtils.computeMD5(media.file()).toString(), again.get(MD5Node.OUT_HASH));
	}

	@Test
	void testMd5OfARewrittenFileIsRecomputed() throws Exception {
		MD5Node node = new MD5Node(null, new CortexOptions(), options());
		StubLoomMedia media = StubLoomMedia.ofBytes(tempDir, "clip.bin", "first");
		node.process(NodeContext.create(media));

		Files.writeString(media.file().toPath(), "second, longer content");

		NodeResult second = node.process(NodeContext.create(media));
		assertEquals(HashUtils.computeMD5(media.file()).toString(), second.get(MD5Node.OUT_HASH));
	}
}
