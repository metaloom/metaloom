package io.metaloom.cortex.common.avro;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.metaloom.cortex.common.node.media.avro.DummyElement;
import io.metaloom.utils.hash.SHA512;

/**
 * Writing and reading back the per-asset Avro files.
 *
 * <p>
 * {@code writeElements} used to call {@code DataFileWriter.create} once per element, which fails on the second element because the writer is
 * already open - so only single-element files could ever be written - and never created a file at all for an empty list.
 * </p>
 */
public class AbstractAvroStorageTest {

	private static final SHA512 HASH = SHA512.fromString(
		"0e3e75234abc68f4378a86b3f4b32a198ba301845b0cd6e50106e874345700cc6663a86c1ea125dc5e92be17c98f9a0f85ca9d5f595db2012f7cc3571945c123");

	@TempDir
	Path dir;

	private class Storage extends AbstractAvroStorage<DummyElement> {

		@Override
		protected File getFileForHash(SHA512 hash) {
			return dir.resolve(hash.toString() + ".avro").toFile();
		}

		@Override
		protected Class<DummyElement> getElementClass() {
			return DummyElement.class;
		}
	}

	@Test
	void testSeveralElementsRoundTrip() throws IOException {
		Storage storage = new Storage();
		storage.writeElements(HASH, List.of(element("a"), element("b"), element("c")));

		List<DummyElement> read = storage.readElements(HASH);
		assertEquals(List.of("a", "b", "c"), read.stream().map(e -> e.getAssetHash().toString()).toList());
	}

	@Test
	void testAnEmptyListWritesAReadableEmptyFile() throws IOException {
		Storage storage = new Storage();
		storage.writeElements(HASH, List.of());

		assertTrue(storage.getFileForHash(HASH).exists());
		assertTrue(storage.readElements(HASH).isEmpty());
	}

	private static DummyElement element(String value) {
		return DummyElement.newBuilder().setAssetHash(value).build();
	}
}
