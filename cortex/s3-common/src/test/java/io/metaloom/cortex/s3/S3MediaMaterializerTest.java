package io.metaloom.cortex.s3;

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

public class S3MediaMaterializerTest {

	private static final String BUCKET = "media";

	@TempDir
	Path cacheRoot;

	private FakeS3ObjectStore store;

	@BeforeEach
	public void setup() {
		store = new FakeS3ObjectStore().put(BUCKET, "2026/clip.mp4", "video-bytes");
	}

	private S3MediaMaterializer materializer() {
		return new S3MediaMaterializer(store, cacheRoot, 0, 0);
	}

	private S3ObjectRef ref(String key) throws IOException {
		return store.head(BUCKET, key);
	}

	@Test
	public void testMaterializeDownloadsTheBytes() throws Exception {
		Path file = materializer().materialize(ref("2026/clip.mp4"));

		assertThat(file).exists();
		assertThat(Files.readString(file)).isEqualTo("video-bytes");
		assertThat(store.downloadCalls).hasValue(1);
	}

	@Test
	public void testExtensionIsPreserved() throws Exception {
		// Cortex detects media type from the path extension, so the cache file must keep it.
		Path file = materializer().materialize(ref("2026/clip.mp4"));
		assertThat(file.getFileName().toString()).endsWith(".mp4");
	}

	@Test
	public void testCacheHitDoesNotDownloadAgain() throws Exception {
		S3MediaMaterializer materializer = materializer();
		Path first = materializer.materialize(ref("2026/clip.mp4"));
		store.resetCounters();

		Path second = materializer.materialize(ref("2026/clip.mp4"));

		assertThat(second).isEqualTo(first);
		assertThat(store.downloadCalls).hasValue(0);
	}

	@Test
	public void testAFreshMaterializerStillSeesTheCache() throws Exception {
		// The cache path is deterministic from (bucket, key, etag), so a different instance -
		// or a restarted worker - reuses what is already on disk.
		materializer().materialize(ref("2026/clip.mp4"));
		store.resetCounters();

		materializer().materialize(ref("2026/clip.mp4"));

		assertThat(store.downloadCalls).hasValue(0);
	}

	@Test
	public void testChangedEtagLandsAtANewPathAndRefetches() throws Exception {
		S3MediaMaterializer materializer = materializer();
		Path original = materializer.materialize(ref("2026/clip.mp4"));

		store.put(BUCKET, "2026/clip.mp4", "different-bytes");
		Path updated = materializer.materialize(ref("2026/clip.mp4"));

		assertThat(updated).isNotEqualTo(original);
		assertThat(Files.readString(updated)).isEqualTo("different-bytes");
		// The stale copy is never served because the etag is part of the file name.
		assertThat(Files.readString(original)).isEqualTo("video-bytes");
	}

	@Test
	public void testMaterializeByReferenceHeadsForTheEtag() throws Exception {
		Path file = materializer().materialize("s3://media/2026/clip.mp4");

		assertThat(Files.readString(file)).isEqualTo("video-bytes");
		assertThat(store.headCalls).hasValue(1);
	}

	@Test
	public void testMaterializeByReferenceFailsForAMissingObject() {
		assertThatThrownBy(() -> materializer().materialize("s3://media/absent.mp4"))
			.isInstanceOf(IOException.class).hasMessageContaining("does not exist");
	}

	@Test
	public void testNoPartialFileIsLeftBehind() throws Exception {
		materializer().materialize(ref("2026/clip.mp4"));
		assertThat(listCache()).noneMatch(path -> path.getFileName().toString().endsWith(".part"));
	}

	@Test
	public void testAFailedDownloadLeavesNoPartialFile() throws Exception {
		S3ObjectRef ref = ref("2026/clip.mp4");
		store.failNextWith(new IOException("network went away"));

		assertThatThrownBy(() -> materializer().materialize(ref))
			.isInstanceOf(IOException.class).hasMessageContaining("network went away");

		assertThat(listCache()).noneMatch(path -> path.getFileName().toString().endsWith(".part"));
	}

	@Test
	public void testMaxObjectSizeRejectsBeforeTransferring() throws Exception {
		S3ObjectRef ref = ref("2026/clip.mp4");
		S3MediaMaterializer materializer = new S3MediaMaterializer(store, cacheRoot, 5, 0);

		assertThatThrownBy(() -> materializer.materialize(ref))
			.isInstanceOf(IOException.class).hasMessageContaining("exceeds the configured maxObjectSize");

		// The guard is checked against the listed size, so nothing is transferred at all.
		assertThat(store.downloadCalls).hasValue(0);
	}

	@Test
	public void testCachePathIsShardedAndDeterministic() {
		S3MediaMaterializer materializer = materializer();
		Path first = materializer.cachePath(S3Uri.of(BUCKET, "2026/clip.mp4"), "etag-1");
		Path second = materializer.cachePath(S3Uri.of(BUCKET, "2026/clip.mp4"), "etag-1");

		assertThat(first).isEqualTo(second);
		assertThat(first.getParent().getFileName().toString()).hasSize(4);
		assertThat(first.startsWith(cacheRoot)).isTrue();
	}

	@Test
	public void testCachePathSeparatesBucketsWithIdenticalKeys() {
		S3MediaMaterializer materializer = materializer();
		assertThat(materializer.cachePath(S3Uri.of("a", "clip.mp4"), "e"))
			.isNotEqualTo(materializer.cachePath(S3Uri.of("b", "clip.mp4"), "e"));
	}

	@Test
	public void testMultipartEtagProducesAUsableFileName() {
		Path path = materializer().cachePath(S3Uri.of(BUCKET, "clip.mp4"), "d41d8cd98f00b204e9800998ecf8427e-7");
		assertThat(path.getFileName().toString()).contains("d41d8cd98f00b204e9800998ecf8427e-7").endsWith(".mp4");
	}

	@Test
	public void testEvictionTrimsTheCacheBackUnderBudget() throws Exception {
		for (int i = 0; i < 12; i++) {
			store.put(BUCKET, "bulk/" + i + ".mp4", "0123456789");
		}
		// Budget of 40 bytes over 12 x 10-byte objects forces repeated sweeps.
		S3MediaMaterializer materializer = new S3MediaMaterializer(store, cacheRoot, 0, 40);
		for (int i = 0; i < 12; i++) {
			materializer.materialize(ref("bulk/" + i + ".mp4"));
		}

		long total = 0;
		for (Path path : listCache()) {
			total += Files.size(path);
		}
		assertThat(total).isLessThanOrEqualTo(40);
		// The most recent object must survive - it is the one the caller is about to read.
		assertThat(materializer.cachePath(S3Uri.of(BUCKET, "bulk/11.mp4"),
			store.head(BUCKET, "bulk/11.mp4").etag())).exists();
	}

	@Test
	public void testEvictionIsDisabledWhenTheBudgetIsZero() throws Exception {
		for (int i = 0; i < 5; i++) {
			store.put(BUCKET, "bulk/" + i + ".mp4", "0123456789");
		}
		S3MediaMaterializer materializer = materializer();
		for (int i = 0; i < 5; i++) {
			materializer.materialize(ref("bulk/" + i + ".mp4"));
		}
		assertThat(listCache()).hasSize(5);
	}

	private List<Path> listCache() throws IOException {
		try (Stream<Path> walk = Files.walk(cacheRoot)) {
			return walk.filter(Files::isRegularFile).toList();
		}
	}
}
