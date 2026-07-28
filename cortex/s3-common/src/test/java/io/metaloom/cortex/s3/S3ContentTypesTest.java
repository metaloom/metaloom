package io.metaloom.cortex.s3;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Paths;

import org.junit.jupiter.api.Test;

public class S3ContentTypesTest {

	@Test
	public void testThumbIsJpeg() {
		// The reason this class exists. ThumbnailNode writes a JPEG under a .thumb name, so
		// octet-stream here would make browsers download the contact sheet instead of showing it,
		// and would put the wrong mimeType on the asset created for it.
		assertThat(S3ContentTypes.of("sheet.thumb")).isEqualTo("image/jpeg");
		assertThat(S3ContentTypes.of(Paths.get("/meta/thumbnail_bin/ab/abc.thumb"))).isEqualTo("image/jpeg");
	}

	@Test
	public void testCommonProducedArtifactTypes() {
		assertThat(S3ContentTypes.of("depth.png")).isEqualTo("image/png");
		assertThat(S3ContentTypes.of("speech.wav")).isEqualTo("audio/wav");
		assertThat(S3ContentTypes.of("clip.mp4")).isEqualTo("video/mp4");
		assertThat(S3ContentTypes.of("meta.json")).isEqualTo("application/json");
	}

	@Test
	public void testCasingIsIgnored() {
		assertThat(S3ContentTypes.of("PHOTO.JPEG")).isEqualTo("image/jpeg");
		assertThat(S3ContentTypes.of("Clip.MP4")).isEqualTo("video/mp4");
	}

	@Test
	public void testDirectoriesAreStripped() {
		assertThat(S3ContentTypes.of("a/b/c/photo.png")).isEqualTo("image/png");
		// A dot in a directory must not be mistaken for the file's extension.
		assertThat(S3ContentTypes.of("v1.2/archive")).isEqualTo(S3ContentTypes.DEFAULT);
	}

	@Test
	public void testUnknownAndMissingExtensionsFallBack() {
		assertThat(S3ContentTypes.of("file.qqq")).isEqualTo(S3ContentTypes.DEFAULT);
		assertThat(S3ContentTypes.of("noextension")).isEqualTo(S3ContentTypes.DEFAULT);
		assertThat(S3ContentTypes.of("trailingdot.")).isEqualTo(S3ContentTypes.DEFAULT);
	}

	@Test
	public void testNullIsSafe() {
		assertThat(S3ContentTypes.of((String) null)).isEqualTo(S3ContentTypes.DEFAULT);
		assertThat(S3ContentTypes.of((java.nio.file.Path) null)).isEqualTo(S3ContentTypes.DEFAULT);
	}
}
