package io.metaloom.cortex.s3;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

public class S3ObjectRefTest {

	@Test
	public void testEtagQuotesAreStripped() {
		// S3 returns ETags wrapped in literal quotes; a raw comparison against a stored value
		// would otherwise report every object as modified forever.
		assertThat(new S3ObjectRef("b", "k", "\"abc123\"", 1, 0).etag()).isEqualTo("abc123");
		assertThat(new S3ObjectRef("b", "k", "W/\"abc123\"", 1, 0).etag()).isEqualTo("abc123");
		assertThat(new S3ObjectRef("b", "k", "abc123", 1, 0).etag()).isEqualTo("abc123");
	}

	@Test
	public void testBlankEtagBecomesNull() {
		assertThat(new S3ObjectRef("b", "k", "  ", 1, 0).etag()).isNull();
		assertThat(new S3ObjectRef("b", "k", "\"\"", 1, 0).etag()).isNull();
		assertThat(new S3ObjectRef("b", "k", null, 1, 0).etag()).isNull();
	}

	@Test
	public void testMultipartEtagSurvivesIntact() {
		// A multipart ETag is <md5-of-md5s>-<partcount>. It is only ever an opaque change token,
		// never an MD5, so the suffix must not be stripped.
		S3ObjectRef ref = new S3ObjectRef("b", "k", "\"d41d8cd98f00b204e9800998ecf8427e-7\"", 1, 0);
		assertThat(ref.etag()).isEqualTo("d41d8cd98f00b204e9800998ecf8427e-7");
	}

	@Test
	public void testDiffersFrom() {
		S3ObjectRef ref = new S3ObjectRef("b", "k", "etag-1", 100, 0);
		assertThat(ref.differsFrom("etag-1", 100)).isFalse();
		assertThat(ref.differsFrom("etag-2", 100)).isTrue();
		assertThat(ref.differsFrom("etag-1", 200)).isTrue();
	}

	@Test
	public void testDiffersFromDegradesToSizeWhenEtagIsUnavailable() {
		// A gateway that returns no ETag must degrade to size-only detection rather than
		// reporting every object as modified on every run.
		S3ObjectRef noEtag = new S3ObjectRef("b", "k", null, 100, 0);
		assertThat(noEtag.differsFrom(null, 100)).isFalse();
		assertThat(noEtag.differsFrom("etag-1", 100)).isFalse();
		assertThat(noEtag.differsFrom(null, 200)).isTrue();
	}

	@Test
	public void testReference() {
		assertThat(new S3ObjectRef("media", "2026/clip.mp4", "e", 1, 0).reference())
			.isEqualTo("s3://media/2026/clip.mp4");
	}
}
