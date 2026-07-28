package io.metaloom.cortex.s3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pins the fake's upload behaviour, because every {@code s3-sink} unit test asserts through it and
 * a fake that quietly diverged from {@link AwsS3ObjectStore} would make those tests meaningless.
 */
public class FakeS3ObjectStoreUploadTest {

	private static final String BUCKET = "media";

	@TempDir
	Path dir;

	private FakeS3ObjectStore store;

	@BeforeEach
	public void setup() {
		store = new FakeS3ObjectStore();
	}

	private Path file(String name, String content) throws IOException {
		return Files.writeString(dir.resolve(name), content);
	}

	@Test
	public void testUploadStoresBytesEtagAndContentType() throws Exception {
		S3ObjectRef ref = store.upload(BUCKET, "a/b.png", file("b.png", "pixels"), "image/png");

		assertThat(store.bytes(BUCKET, "a/b.png")).isEqualTo("pixels".getBytes(StandardCharsets.UTF_8));
		assertThat(store.contentTypeOf(BUCKET, "a/b.png")).isEqualTo("image/png");
		assertThat(ref.key()).isEqualTo("a/b.png");
		assertThat(ref.size()).isEqualTo(6);
		assertThat(ref.etag()).isNotBlank();
		assertThat(store.uploadCalls).hasValue(1);
	}

	@Test
	public void testUploadedObjectIsVisibleToHeadAndList() throws Exception {
		store.upload(BUCKET, "a/b.png", file("b.png", "pixels"), "image/png");

		assertThat(store.head(BUCKET, "a/b.png")).isNotNull();
		assertThat(store.list(BUCKET, "a/", null, null).objects()).extracting(S3ObjectRef::key)
			.containsExactly("a/b.png");
	}

	@Test
	public void testSameContentYieldsSameEtag() throws Exception {
		S3ObjectRef first = store.upload(BUCKET, "one", file("one", "same"), null);
		S3ObjectRef second = store.upload(BUCKET, "two", file("two", "same"), null);

		assertThat(first.etag()).isEqualTo(second.etag());
	}

	@Test
	public void testDifferentContentYieldsDifferentEtag() throws Exception {
		S3ObjectRef first = store.upload(BUCKET, "one", file("one", "aaa"), null);
		S3ObjectRef second = store.upload(BUCKET, "two", file("two", "bbb"), null);

		assertThat(first.etag()).isNotEqualTo(second.etag());
	}

	@Test
	public void testUploadReplacesAnExistingObject() throws Exception {
		store.upload(BUCKET, "k", file("a", "first"), null);
		store.upload(BUCKET, "k", file("b", "second"), null);

		assertThat(store.bytes(BUCKET, "k")).isEqualTo("second".getBytes(StandardCharsets.UTF_8));
		assertThat(store.keys(BUCKET)).containsExactly("k");
	}

	@Test
	public void testFailUploadWithAffectsOnlyTheNextUpload() throws Exception {
		Path source = file("a", "x");
		store.failUploadWith(new IOException("bucket is full"));

		assertThatThrownBy(() -> store.upload(BUCKET, "k", source, null))
			.isInstanceOf(IOException.class).hasMessage("bucket is full");

		store.upload(BUCKET, "k", source, null);
		assertThat(store.bytes(BUCKET, "k")).isNotNull();
	}

	@Test
	public void testNullContentTypeIsRecordedAsNull() throws Exception {
		store.upload(BUCKET, "k", file("a", "x"), null);

		assertThat(store.contentTypeOf(BUCKET, "k")).isNull();
	}
}
