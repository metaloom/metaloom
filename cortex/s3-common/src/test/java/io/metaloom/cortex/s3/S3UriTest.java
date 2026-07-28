package io.metaloom.cortex.s3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Paths;

import org.junit.jupiter.api.Test;

public class S3UriTest {

	@Test
	public void testParseRoundTrip() {
		S3Uri uri = S3Uri.parse("s3://media/2026/07/clip.mp4");
		assertThat(uri.bucket()).isEqualTo("media");
		assertThat(uri.key()).isEqualTo("2026/07/clip.mp4");
		assertThat(uri.toReference()).isEqualTo("s3://media/2026/07/clip.mp4");
	}

	@Test
	public void testIsS3() {
		assertThat(S3Uri.isS3("s3://media/a.mp4")).isTrue();
		assertThat(S3Uri.isS3("/mnt/media/a.mp4")).isFalse();
		assertThat(S3Uri.isS3("https://example.com/a.mp4")).isFalse();
		assertThat(S3Uri.isS3(null)).isFalse();
	}

	@Test
	public void testKeysAreKeptVerbatim() {
		// Keys may contain spaces, unicode and repeated slashes. None of that is normalised.
		S3Uri uri = S3Uri.parse("s3://media/a folder/über  clip.mp4");
		assertThat(uri.key()).isEqualTo("a folder/über  clip.mp4");
		assertThat(uri.fileName()).isEqualTo("über  clip.mp4");
	}

	@Test
	public void testRejectsMalformedReferences() {
		assertThatThrownBy(() -> S3Uri.parse("/mnt/a.mp4"))
			.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Not an S3 reference");
		assertThatThrownBy(() -> S3Uri.parse("s3://media"))
			.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("missing an object key");
		assertThatThrownBy(() -> S3Uri.parse("s3://media/"))
			.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("object key must be set");
		assertThatThrownBy(() -> new S3Uri("", "a.mp4"))
			.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("bucket must be set");
	}

	@Test
	public void testExtensionIsPreservedForMediaTypeDetection() {
		// Load-bearing: cortex detects media type from the path extension, so a materialized
		// object that loses its extension would be invisible to every media node.
		assertThat(S3Uri.parse("s3://media/2026/clip.mp4").extension()).isEqualTo(".mp4");
		assertThat(S3Uri.parse("s3://media/a/b/photo.JPEG").extension()).isEqualTo(".JPEG");
	}

	@Test
	public void testExtensionIsEmptyWhenTheKeyHasNone() {
		assertThat(S3Uri.parse("s3://media/2026/clip").extension()).isEmpty();
		assertThat(S3Uri.parse("s3://media/clip.").extension()).isEmpty();
		// A dotted directory must not donate its suffix to an extension-less object.
		assertThat(S3Uri.parse("s3://media/v1.2/clip").extension()).isEmpty();
		// Not a plausible extension - too long, and not alphanumeric.
		assertThat(S3Uri.parse("s3://media/clip.averylongsuffix").extension()).isEmpty();
		assertThat(S3Uri.parse("s3://media/clip.mp 4").extension()).isEmpty();
	}

	@Test
	public void testPathApiCannotCarryAReference() {
		// The reason references are strings rather than Paths: the double slash is collapsed,
		// so a URI cannot survive a round trip through java.nio.file.Path.
		assertThat(Paths.get("s3://media/clip.mp4").toString()).isEqualTo("s3:/media/clip.mp4");
	}
}
