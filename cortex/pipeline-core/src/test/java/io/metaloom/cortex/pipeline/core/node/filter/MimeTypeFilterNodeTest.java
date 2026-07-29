package io.metaloom.cortex.pipeline.core.node.filter;

import static io.metaloom.cortex.pipeline.test.assertj.PipelineAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.pipeline.api.PipelineResult;
import io.metaloom.cortex.pipeline.test.StubLoomMedia;

class MimeTypeFilterNodeTest extends AbstractFilterNodeTest {

	@TempDir
	File tempDir;

	private static StubLoomMedia video(String path) {
		return new StubLoomMedia(path, true, false, false, false);
	}

	private static StubLoomMedia image(String path) {
		return new StubLoomMedia(path, false, true, false, false);
	}

	private boolean passed(MimeTypeFilterNode filter, LoomMedia media) {
		return passed(evaluate(filter, media));
	}

	@Test
	void testNoCategoryFlagsAllowsEverything() {
		MimeTypeFilterNode filter = MimeTypeFilterNode.builder("mime").build();

		assertThat(passed(filter, video("/media/clip.mp4"))).isTrue();
		assertThat(passed(filter, image("/media/photo.jpg"))).isTrue();
		assertThat(passed(filter, new StubLoomMedia("/media/unknown.bin"))).isTrue();
	}

	@Test
	void testCategoryFlagSelectsMatchingMediaOnly() {
		MimeTypeFilterNode filter = MimeTypeFilterNode.builder("mime").allowVideo(true).build();

		assertThat(passed(filter, video("/media/clip.mp4"))).isTrue();
		assertThat(passed(filter, image("/media/photo.jpg"))).isFalse();
	}

	@Test
	void testCategoryFlagsAreOred() {
		MimeTypeFilterNode filter = MimeTypeFilterNode.builder("mime")
				.allowVideo(true)
				.allowAudio(true)
				.build();

		StubLoomMedia audio = new StubLoomMedia("/media/track.mp3", false, false, true, false);
		assertThat(passed(filter, audio)).isTrue();
		assertThat(passed(filter, video("/media/clip.mp4"))).isTrue();
		assertThat(passed(filter, image("/media/photo.jpg"))).isFalse();
	}

	@Test
	void testExtensionCheckIsCaseInsensitiveOnThePath() {
		MimeTypeFilterNode filter = MimeTypeFilterNode.builder("mime")
				.allowedExtensions(Set.of("mp4", "mkv"))
				.build();

		assertThat(passed(filter, video("/media/CLIP.MP4"))).isTrue();
		assertThat(passed(filter, video("/media/clip.avi"))).isFalse();
	}

	@Test
	void testPathWithoutExtensionIsRejectedWhenExtensionsAreConfigured() {
		MimeTypeFilterNode filter = MimeTypeFilterNode.builder("mime")
				.allowedExtensions(Set.of("mp4"))
				.build();

		assertThat(passed(filter, video("/media/clip"))).isFalse();
	}

	@Test
	void testEmptyExtensionSetSkipsTheExtensionCheck() {
		MimeTypeFilterNode filter = MimeTypeFilterNode.builder("mime")
				.allowedExtensions(Set.of())
				.build();

		assertThat(passed(filter, video("/media/clip.avi"))).isTrue();
	}

	@Test
	void testCategoryIsCheckedBeforeExtension() {
		// Extension matches, but the category does not — reject wins.
		MimeTypeFilterNode filter = MimeTypeFilterNode.builder("mime")
				.allowVideo(true)
				.allowedExtensions(Set.of("mp4"))
				.build();

		assertThat(passed(filter, image("/media/photo.mp4"))).isFalse();
	}

	@Test
	void testPassRoutesToPassBranch() {
		LoomMedia media = StubLoomMedia.ofBytes(tempDir, "clip.mp4", "video-bytes");
		MimeTypeFilterNode filter = MimeTypeFilterNode.builder("mime")
				.allowedExtensions(Set.of("mp4"))
				.build();

		PipelineResult result = route(media, filter);

		assertThat(result)
				.isSuccess()
				.hasCompletedNode("mime")
				.hasNodeOutput("mime", AbstractFilterNode.OUT_PASSED, true);
		assertThat(result).node(PASS_NODE).isCompleted();
		assertThat(result).node(REJECT_NODE).isSkipped();
	}

	@Test
	void testRejectRoutesToRejectBranch() {
		LoomMedia media = StubLoomMedia.ofBytes(tempDir, "clip.avi", "video-bytes");
		MimeTypeFilterNode filter = MimeTypeFilterNode.builder("mime")
				.allowedExtensions(Set.of("mp4"))
				.build();

		PipelineResult result = route(media, filter);

		assertThat(result)
				.isSuccess()
				.hasCompletedNode("mime")
				.hasNodeOutput("mime", AbstractFilterNode.OUT_PASSED, false);
		assertThat(result).node(REJECT_NODE).isCompleted();
		assertThat(result).node(PASS_NODE).isSkipped();
	}
}
