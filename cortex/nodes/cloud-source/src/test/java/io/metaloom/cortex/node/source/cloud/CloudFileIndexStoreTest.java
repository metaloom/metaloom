package io.metaloom.cortex.node.source.cloud;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.metaloom.cortex.cloud.CloudFileRef;
import io.metaloom.cortex.cloud.CloudProviderId;

public class CloudFileIndexStoreTest {

	@TempDir
	Path dir;

	private final CloudFileIndexStore store = new CloudFileIndexStore();

	private static CloudFileRef file(String id, String name, String parentId, boolean folder, long size) {
		return new CloudFileRef(CloudProviderId.GDRIVE, "d", id, name, parentId, "video/mp4",
			"md5:" + id, size, 1234, folder, false, null, true);
	}

	@Test
	public void testRoundTrip() throws IOException {
		CloudFileIndex index = new CloudFileIndex();
		index.put(file("f1", "a.mp4", "p1", false, 100));
		index.put(file("d1", "Folder", null, true, -1));
		index.setLastFullScanMillis(999);
		index.setDeltaToken("cursor-7");
		index.setAccountId("ingest@example.com");

		Path file = dir.resolve("index.avro");
		store.store(file, index);
		CloudFileIndex loaded = store.load(file);

		assertThat(loaded.size()).isEqualTo(2);
		assertThat(loaded.getLastFullScanMillis()).isEqualTo(999);
		assertThat(loaded.getDeltaToken()).isEqualTo("cursor-7");
		assertThat(loaded.getAccountId()).isEqualTo("ingest@example.com");

		CloudFileRef restored = loaded.get("f1");
		assertThat(restored.name()).isEqualTo("a.mp4");
		assertThat(restored.parentId()).isEqualTo("p1");
		assertThat(restored.changeToken()).isEqualTo("md5:f1");
		assertThat(restored.size()).isEqualTo(100);
		assertThat(loaded.isKnownFolder("d1")).isTrue();
	}

	@Test
	public void testASizelessNativeDocSurvivesAsMinusOne() throws IOException {
		CloudFileIndex index = new CloudFileIndex();
		index.put(new CloudFileRef(CloudProviderId.GDRIVE, "d", "doc", "Q3 Report", null,
			"application/vnd.google-apps.document", "v:1", -1, 0, false, false, "application/pdf", true));

		Path file = dir.resolve("index.avro");
		store.store(file, index);

		CloudFileRef loaded = store.load(file).get("doc");
		assertThat(loaded.size()).isEqualTo(-1);
		assertThat(loaded.exportMimeType()).isEqualTo("application/pdf");
	}

	@Test
	public void testANullParentSurvives() throws IOException {
		CloudFileIndex index = new CloudFileIndex();
		index.put(file("f1", "a.mp4", null, false, 1));

		Path file = dir.resolve("index.avro");
		store.store(file, index);

		assertThat(store.load(file).get("f1").parentId()).isNull();
	}

	@Test
	public void testAMissingFileLoadsEmpty() {
		// This is what makes a first run report everything as NEW rather than failing.
		CloudFileIndex index = store.load(dir.resolve("absent.avro"));

		assertThat(index.isEmpty()).isTrue();
		assertThat(index.getDeltaToken()).isNull();
	}

	@Test
	public void testACorruptFileLoadsEmptyRatherThanFailing() throws IOException {
		Path file = dir.resolve("corrupt.avro");
		Files.write(file, "not avro at all".getBytes(StandardCharsets.UTF_8));

		// A half-written index costs one redundant walk, which is recoverable; refusing to start
		// is not.
		assertThat(store.load(file).isEmpty()).isTrue();
	}

	@Test
	public void testAPartialWriteLeavesThePreviousIndexIntact() throws IOException {
		Path file = dir.resolve("index.avro");
		CloudFileIndex first = new CloudFileIndex();
		first.put(file("f1", "a.mp4", null, false, 1));
		store.store(file, first);

		CloudFileIndex second = new CloudFileIndex();
		second.put(file("f2", "b.mp4", null, false, 1));
		store.store(file, second);

		// Written beside the target and moved into place: no .part is left behind.
		assertThat(Files.exists(file.resolveSibling(file.getFileName() + ".part"))).isFalse();
		assertThat(store.load(file).get("f2")).isNotNull();
	}

	@Test
	public void testProviderIsPreserved() throws IOException {
		CloudFileIndex index = new CloudFileIndex();
		index.put(new CloudFileRef(CloudProviderId.ONEDRIVE, "d", "f1", "a.mp4", null, "video/mp4",
			"ctag:x", 1, 0, false, false, null, true));

		Path file = dir.resolve("index.avro");
		store.store(file, index);

		assertThat(store.load(file).get("f1").provider()).isEqualTo(CloudProviderId.ONEDRIVE);
	}
}
