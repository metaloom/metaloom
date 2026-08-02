package io.metaloom.cortex.cloud;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class CloudLoomMediaTest {

	@TempDir
	Path cacheDir;

	private FakeCloudFileStore store;
	private CloudMediaMaterializer materializer;

	@BeforeEach
	public void setup() {
		store = new FakeCloudFileStore(CloudProviderId.GDRIVE);
		materializer = new CloudMediaMaterializer(store, cacheDir, 0, 0);
	}

	private CloudLoomMedia media(String fileId) {
		return new CloudLoomMedia(store.peek("d", fileId), materializer);
	}

	@Test
	public void testReferenceCostsNothing() {
		String id = store.putFile("d", null, "clip.mp4", "x");
		assertThat(media(id).reference()).isEqualTo("gdrive://d/" + id + "/clip.mp4");
		assertThat(store.downloadCalls).hasValue(0);
	}

	@Test
	public void testSizeComesFromTheListingWithoutFetching() {
		String id = store.putFile("d", null, "clip.mp4", "hello");
		assertThat(media(id).size()).isEqualTo(5);
		assertThat(store.downloadCalls).hasValue(0);
	}

	/**
	 * Regression guard. {@code S3LoomMedia.size()} falls back to materializing when the size is
	 * unknown, which is safe there because an S3 listing always reports one. A Google native
	 * document genuinely has none, and {@code SourceTaskRunner} asks every enumerated item for its
	 * size - so that fallback here would download every Doc during enumeration.
	 */
	@Test
	public void testSizeIsMinusOneForANativeDocRatherThanDownloading() {
		String id = store.putNativeDoc("d", null, "Q3 Report", "application/pdf");

		assertThat(media(id).size()).isEqualTo(-1);
		assertThat(store.downloadCalls).hasValue(0);
	}

	/**
	 * The other half of the same guard: {@code S3LoomMedia} uses {@code size >= 0} as an existence
	 * proxy, and {@code AbstractMediaNode} asks every item whether it exists. With a size-less file
	 * that proxy is wrong, so existence is an explicit flag.
	 */
	@Test
	public void testExistsIsTrueForANativeDocWithNoSize() {
		String id = store.putNativeDoc("d", null, "Q3 Report", "application/pdf");

		assertThat(media(id).exists()).isTrue();
		assertThat(store.downloadCalls).hasValue(0);
	}

	@Test
	public void testExistsIsFalseForAFileTheProviderNeverShowedUs() {
		CloudLoomMedia missing = new CloudLoomMedia(
			CloudFileRef.absent(CloudUri.parse("gdrive://d/gone/x.mp4")), materializer);

		assertThat(missing.exists()).isFalse();
		assertThat(store.downloadCalls).hasValue(0);
	}

	@Test
	public void testMediaTypeIsDerivedFromTheNameWithoutFetching() {
		String video = store.putFile("d", null, "clip.mp4", "x");
		String image = store.putFile("d", null, "shot.jpg", "x");

		assertThat(media(video).isVideo()).isTrue();
		assertThat(media(image).isImage()).isTrue();
		assertThat(store.downloadCalls).hasValue(0);
	}

	@Test
	public void testPathMaterializesOnDemand() throws IOException {
		String id = store.putFile("d", null, "clip.mp4", "hello");
		CloudLoomMedia handle = media(id);

		assertThat(handle.isMaterialized()).isFalse();
		Path path = handle.path();

		assertThat(handle.isMaterialized()).isTrue();
		assertThat(java.nio.file.Files.readString(path)).isEqualTo("hello");
	}

	@Test
	public void testRepeatedAccessMaterializesOnlyOnce() {
		String id = store.putFile("d", null, "clip.mp4", "x");
		CloudLoomMedia handle = media(id);

		handle.path();
		handle.absolutePath();
		handle.file();

		assertThat(store.downloadCalls).hasValue(1);
	}

	@Test
	public void testOpenReadsTheMaterializedBytes() throws IOException {
		String id = store.putFile("d", null, "clip.mp4", "hello");
		try (var stream = media(id).open()) {
			assertThat(new String(stream.readAllBytes())).isEqualTo("hello");
		}
	}

	@Test
	public void testTwoHandlesForTheSameFileShareTheCachedCopy() {
		String id = store.putFile("d", null, "clip.mp4", "x");

		media(id).path();
		media(id).path();

		assertThat(store.downloadCalls).hasValue(1);
	}

	@Test
	public void testToStringSaysWhetherBytesArePresent() {
		String id = store.putFile("d", null, "clip.mp4", "x");
		CloudLoomMedia handle = media(id);

		assertThat(handle.toString()).contains("not materialized");
		handle.path();
		assertThat(handle.toString()).doesNotContain("not materialized");
	}
}
