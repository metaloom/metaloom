package io.metaloom.loom.rest.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Reading the seek landing point out of an ffmpeg {@code framemd5} report.
 *
 * <p>
 * This is the number the video player uses as the origin of its clock, so getting it wrong by a factor is not a rendering glitch - it is a
 * transcript highlighting the wrong line for the rest of the episode. The timebase header is the part that is easy to drop: the raw timestamp
 * of a seek to ten minutes into a 23.976fps file is 14422, and only the {@code 1001/24000} beside it makes that 601 seconds.
 * </p>
 */
public class FrameMd5PtsTest {

	/** A real report, from a seek into a 1080p 23.976fps Matroska episode. */
	private static final String REPORT = """
		#format: frame checksums
		#version: 2
		#hash: MD5
		#stream#, dts,        pts, duration,     size, hash
		#tb 0: 1001/24000
		#media_type 0: video
		#codec_id 0: rawvideo
		0,      14422,      14422,        1,  3110400, 94be1e9970a5db6836dce86607860116
		""";

	@Test
	@DisplayName("The timestamp is scaled by the timebase, not taken raw")
	public void shouldApplyTheTimebase() {
		assertThat(AssetMediaEndpointService.firstFramePts(REPORT))
			.isNotNull()
			.isCloseTo(601.518d, within(0.001d));
	}

	@Test
	@DisplayName("Only the first frame counts, however many the report carries")
	public void shouldReadTheFirstFrameOnly() {
		String twoFrames = REPORT + "0,      14424,      14424,        1,  3110400, aaaa\n";
		assertThat(AssetMediaEndpointService.firstFramePts(twoFrames)).isCloseTo(601.518d, within(0.001d));
	}

	@Test
	@DisplayName("A report with no frames in it is not an answer")
	public void shouldRejectAnEmptyReport() {
		// A seek past the end produces exactly this: headers and nothing else. The caller falls
		// back to the requested offset, which is what the player would have assumed anyway.
		assertThat(AssetMediaEndpointService.firstFramePts("#format: frame checksums\n#tb 0: 1001/24000\n")).isNull();
		assertThat(AssetMediaEndpointService.firstFramePts("")).isNull();
	}

	@Test
	@DisplayName("A frame with no timebase to scale it by is not an answer either")
	public void shouldRejectAFrameWithoutATimebase() {
		// Better null than a raw tick count: 14422 would be read as four hours into the file.
		assertThat(AssetMediaEndpointService.firstFramePts("0,  14422,  14422,  1,  3110400, abc\n")).isNull();
	}

	@Test
	@DisplayName("A timebase of one second a tick is still a timebase")
	public void shouldHandleAWholeSecondTimebase() {
		assertThat(AssetMediaEndpointService.firstFramePts("#tb 0: 1/1000\n0,  601518,  601518,  1,  100, abc\n"))
			.isCloseTo(601.518d, within(0.001d));
	}
}
