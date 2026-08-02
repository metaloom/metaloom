package io.metaloom.cortex.cloud;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class CloudMediaMaterializerTest {

	@TempDir
	Path cacheDir;

	private FakeCloudFileStore store;

	@BeforeEach
	public void setup() {
		store = new FakeCloudFileStore(CloudProviderId.GDRIVE);
	}

	private CloudMediaMaterializer materializer(long maxObjectSize, long maxCacheBytes) {
		return new CloudMediaMaterializer(store, cacheDir, maxObjectSize, maxCacheBytes);
	}

	@Test
	public void testMaterializeDownloadsTheBytes() throws IOException {
		String id = store.putFile("d", null, "clip.mp4", "hello");
		Path file = materializer(0, 0).materialize(store.peek("d", id));

		assertThat(Files.readString(file)).isEqualTo("hello");
		assertThat(store.downloadCalls).hasValue(1);
	}

	@Test
	public void testExtensionIsPreservedInTheCacheName() throws IOException {
		String id = store.putFile("d", null, "clip.mp4", "x");
		Path file = materializer(0, 0).materialize(store.peek("d", id));

		// Load bearing: cortex detects media type from the path, so a cached file that lost its
		// extension is invisible to every media node.
		assertThat(file.getFileName().toString()).endsWith(".mp4");
	}

	@Test
	public void testAnExportedDocumentGetsTheExportExtension() throws IOException {
		String id = store.putNativeDoc("d", null, "Q3 Report", "application/pdf");
		Path file = materializer(0, 0).materialize(store.peek("d", id));

		assertThat(file.getFileName().toString()).endsWith(".pdf");
	}

	@Test
	public void testCacheHitDoesNotDownloadAgain() throws IOException {
		String id = store.putFile("d", null, "clip.mp4", "x");
		CloudMediaMaterializer materializer = materializer(0, 0);
		materializer.materialize(store.peek("d", id));
		materializer.materialize(store.peek("d", id));

		assertThat(store.downloadCalls).hasValue(1);
	}

	@Test
	public void testAFreshMaterializerStillSeesTheCache() throws IOException {
		String id = store.putFile("d", null, "clip.mp4", "x");
		materializer(0, 0).materialize(store.peek("d", id));
		materializer(0, 0).materialize(store.peek("d", id));

		// The path is deterministic, which is what lets the source node and a resolver on another
		// worker agree without coordinating.
		assertThat(store.downloadCalls).hasValue(1);
	}

	@Test
	public void testAChangedTokenLandsAtANewPathAndRefetches() throws IOException {
		String id = store.putFile("d", null, "clip.mp4", "one");
		CloudMediaMaterializer materializer = materializer(0, 0);
		Path first = materializer.materialize(store.peek("d", id));

		store.update("d", id, "two");
		Path second = materializer.materialize(store.peek("d", id));

		assertThat(second).isNotEqualTo(first);
		assertThat(Files.readString(second)).isEqualTo("two");
		assertThat(store.downloadCalls).hasValue(2);
	}

	@Test
	public void testMaterializeByReferenceReadsMetadataFirst() throws IOException {
		String id = store.putFile("d", null, "clip.mp4", "x");
		Path file = materializer(0, 0).materialize("gdrive://d/" + id + "/clip.mp4");

		assertThat(Files.readString(file)).isEqualTo("x");
		assertThat(store.getCalls).hasValue(1);
	}

	@Test
	public void testMaterializeByReferenceFailsForAMissingFile() {
		assertThatThrownBy(() -> materializer(0, 0).materialize("gdrive://d/nope/x.mp4"))
			.isInstanceOf(IOException.class)
			.hasMessageContaining("does not exist");
	}

	@Test
	public void testMaxObjectSizeRejectsBeforeTransferring() {
		String id = store.putFile("d", null, "big.mp4", "0123456789");

		assertThatThrownBy(() -> materializer(5, 0).materialize(store.peek("d", id)))
			.isInstanceOf(IOException.class)
			.hasMessageContaining("exceeds the configured maxObjectSize");
		assertThat(store.downloadCalls).hasValue(0);
	}

	@Test
	public void testAFailedDownloadLeavesNoPartialFile() throws IOException {
		String id = store.putFile("d", null, "clip.mp4", "x");
		store.failNextWith(new IOException("boom"));

		assertThatThrownBy(() -> materializer(0, 0).materialize(store.peek("d", id)))
			.isInstanceOf(IOException.class);

		try (Stream<Path> walk = Files.walk(cacheDir)) {
			List<Path> leftovers = walk.filter(Files::isRegularFile).toList();
			assertThat(leftovers).isEmpty();
		}
	}

	@Test
	public void testCachePathIsShardedAndDeterministic() {
		CloudMediaMaterializer materializer = materializer(0, 0);
		CloudUri uri = CloudUri.parse("gdrive://d/f/clip.mp4");

		Path first = materializer.cachePath(uri, "md5:abc");
		Path second = materializer.cachePath(uri, "md5:abc");

		assertThat(first).isEqualTo(second);
		assertThat(first.getParent().getFileName().toString()).hasSize(4);
		assertThat(first.startsWith(cacheDir)).isTrue();
	}

	@Test
	public void testTwoProvidersWithTheSameIdsDoNotCollide() {
		CloudMediaMaterializer materializer = materializer(0, 0);
		Path google = materializer.cachePath(CloudUri.parse("gdrive://d/f/x.mp4"), "t");
		Path microsoft = materializer.cachePath(CloudUri.parse("onedrive://d/f/x.mp4"), "t");

		assertThat(google).isNotEqualTo(microsoft);
	}

	@Test
	public void testEvictionTrimsTheCacheBackUnderBudget() throws IOException {
		CloudMediaMaterializer materializer = materializer(0, 30);
		for (int i = 0; i < 8; i++) {
			String id = store.putFile("d", null, "clip" + i + ".mp4", "0123456789");
			materializer.materialize(store.peek("d", id));
		}

		long total;
		try (Stream<Path> walk = Files.walk(cacheDir)) {
			total = walk.filter(Files::isRegularFile).mapToLong(CloudMediaMaterializerTest::sizeOf).sum();
		}
		assertThat(total).isLessThanOrEqualTo(30);
	}

	@Test
	public void testEvictionIsDisabledWhenTheBudgetIsZero() throws IOException {
		CloudMediaMaterializer materializer = materializer(0, 0);
		for (int i = 0; i < 5; i++) {
			String id = store.putFile("d", null, "clip" + i + ".mp4", "0123456789");
			materializer.materialize(store.peek("d", id));
		}

		try (Stream<Path> walk = Files.walk(cacheDir)) {
			assertThat(walk.filter(Files::isRegularFile).count()).isEqualTo(5);
		}
	}

	private static long sizeOf(Path path) {
		try {
			return Files.size(path);
		} catch (IOException e) {
			return 0;
		}
	}
}
