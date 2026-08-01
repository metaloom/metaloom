package io.metaloom.loom.storage.s3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Pins the {@code s3://bucket/key} grammar.
 *
 * <p>
 * 🔴 These assertions are a contract with Cortex, not a formatting preference. {@code io.metaloom.cortex.s3.S3Uri} parses what Loom writes into
 * {@code asset_location.path}, and {@code S3MediaMaterializer} is what lets a worker process an S3-hosted asset. If this file is changed to emit
 * anything else, every asset uploaded into an S3-backed library silently stops being processable by any pipeline.
 * </p>
 */
public class S3LocatorTest {

	@Test
	public void shouldRoundTripAReference() {
		S3Locator locator = new S3Locator("media", "ab/cd/ef/deadbeef");
		assertThat(locator.toReference()).isEqualTo("s3://media/ab/cd/ef/deadbeef");
		assertThat(S3Locator.parse(locator.toReference())).isEqualTo(locator);
	}

	@Test
	public void shouldRecogniseS3References() {
		assertThat(S3Locator.isS3("s3://bucket/key")).isTrue();
		assertThat(S3Locator.isS3("/var/lib/loom/storage/ab/cd/ef/hash")).isFalse();
		assertThat(S3Locator.isS3(null)).isFalse();
	}

	@Test
	public void shouldKeepKeysVerbatim() {
		// S3 keys may contain slashes, spaces and unicode. Normalising any of it would produce a key
		// that does not exist in the bucket.
		S3Locator locator = S3Locator.parse("s3://b/holiday photos/île/2026 rushes.mp4");
		assertThat(locator.bucket()).isEqualTo("b");
		assertThat(locator.key()).isEqualTo("holiday photos/île/2026 rushes.mp4");
	}

	@Test
	public void shouldSplitOnTheFirstSlashOnly() {
		assertThat(S3Locator.parse("s3://bucket/a/b/c").key()).isEqualTo("a/b/c");
	}

	@Test
	public void shouldRejectMalformedReferences() {
		assertThatThrownBy(() -> S3Locator.parse("https://example.com/x")).isInstanceOf(IllegalArgumentException.class);
		// A bucket with no key is not addressable.
		assertThatThrownBy(() -> S3Locator.parse("s3://bucket")).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new S3Locator("bucket", "")).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new S3Locator("", "key")).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new S3Locator("bucket/nested", "key")).isInstanceOf(IllegalArgumentException.class);
	}
}
