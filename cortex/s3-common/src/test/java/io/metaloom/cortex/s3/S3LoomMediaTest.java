package io.metaloom.cortex.s3;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class S3LoomMediaTest {

	private static final String BUCKET = "media";

	@TempDir
	Path cacheRoot;

	private FakeS3ObjectStore store;
	private S3MediaMaterializer materializer;

	@BeforeEach
	public void setup() {
		store = new FakeS3ObjectStore()
			.put(BUCKET, "2026/clip.mp4", "video-bytes")
			.put(BUCKET, "2026/photo.jpg", "image-bytes")
			.put(BUCKET, "2026/notes.txt", "text-bytes");
		materializer = new S3MediaMaterializer(store, cacheRoot, 0, 0);
	}

	private S3LoomMedia media(String key) throws Exception {
		return new S3LoomMedia(store.head(BUCKET, key), materializer);
	}

	@Test
	public void testReferenceIsTheUriAndCostsNothing() throws Exception {
		S3LoomMedia media = media("2026/clip.mp4");
		store.resetCounters();

		assertThat(media.reference()).isEqualTo("s3://media/2026/clip.mp4");

		assertThat(media.isMaterialized()).isFalse();
		assertThat(store.downloadCalls).hasValue(0);
	}

	@Test
	public void testSizeComesFromTheListingWithoutFetching() throws Exception {
		S3LoomMedia media = media("2026/clip.mp4");
		store.resetCounters();

		assertThat(media.size()).isEqualTo("video-bytes".length());

		assertThat(media.isMaterialized()).isFalse();
		assertThat(store.downloadCalls).hasValue(0);
	}

	@Test
	public void testMediaTypeIsDerivedFromTheKeyWithoutFetching() throws Exception {
		// This is what lets a filter node reject an object before any transfer happens.
		S3LoomMedia video = media("2026/clip.mp4");
		S3LoomMedia image = media("2026/photo.jpg");
		store.resetCounters();

		assertThat(video.isVideo()).isTrue();
		assertThat(video.isImage()).isFalse();
		assertThat(image.isImage()).isTrue();
		assertThat(image.isVideo()).isFalse();

		assertThat(store.downloadCalls).hasValue(0);
		assertThat(video.isMaterialized()).isFalse();
	}

	@Test
	public void testPathMaterializesOnDemand() throws Exception {
		S3LoomMedia media = media("2026/clip.mp4");

		Path path = media.path();

		assertThat(media.isMaterialized()).isTrue();
		assertThat(Files.readString(path)).isEqualTo("video-bytes");
		assertThat(store.downloadCalls).hasValue(1);
	}

	@Test
	public void testRepeatedAccessMaterializesOnlyOnce() throws Exception {
		S3LoomMedia media = media("2026/clip.mp4");

		media.path();
		media.file();
		media.absolutePath();
		media.exists();

		assertThat(store.downloadCalls).hasValue(1);
	}

	@Test
	public void testOpenReadsTheMaterializedBytes() throws Exception {
		S3LoomMedia media = media("2026/clip.mp4");

		try (var in = media.open()) {
			assertThat(new String(in.readAllBytes())).isEqualTo("video-bytes");
		}
	}

	@Test
	public void testSha512IsComputedFromTheMaterializedFile() throws Exception {
		S3LoomMedia media = media("2026/clip.mp4");

		assertThat(media.getSHA512()).isNotNull();
		assertThat(media.getSHA512().toString()).isNotBlank();
	}

	@Test
	public void testTwoHandlesForTheSameObjectShareTheCachedFile() throws Exception {
		Path first = media("2026/clip.mp4").path();
		store.resetCounters();

		Path second = media("2026/clip.mp4").path();

		assertThat(second).isEqualTo(first);
		assertThat(store.downloadCalls).hasValue(0);
	}

	@Test
	public void testExistsDoesNotFetchTheObject() throws Exception {
		// AbstractMediaNode asks this for every item before deciding to do any work, so answering
		// it must never cost a transfer.
		S3LoomMedia media = media("2026/clip.mp4");
		store.resetCounters();

		assertThat(media.exists()).isTrue();

		assertThat(media.isMaterialized()).isFalse();
		assertThat(store.downloadCalls).hasValue(0);
	}

	@Test
	public void testExistsIsFalseForAnObjectThatWasNotFound() {
		// The resolver builds this shape when an object vanished between enumeration and execution.
		S3LoomMedia media = new S3LoomMedia(new S3ObjectRef(BUCKET, "gone.mp4", null, -1, 0), materializer);

		assertThat(media.exists()).isFalse();
		assertThat(media.isMaterialized()).isFalse();
	}

	@Test
	public void testToStringSaysWhetherBytesArePresent() throws Exception {
		S3LoomMedia media = media("2026/clip.mp4");
		assertThat(media.toString()).contains("s3://media/2026/clip.mp4").contains("not materialized");

		media.path();
		assertThat(media.toString()).doesNotContain("not materialized");
	}
}
