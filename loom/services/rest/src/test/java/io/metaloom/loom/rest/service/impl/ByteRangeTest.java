package io.metaloom.loom.rest.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.rest.service.impl.AssetBinaryEndpointService.ByteRange;

/**
 * Range header parsing for the binary download route.
 *
 * <p>
 * This is what lets a browser seek inside a video instead of refetching it from byte 0 on every scrub, so the edge cases here are the ones a
 * {@code <video>} element actually sends: an open-ended probe ({@code bytes=0-}), a suffix request, and a seek past the end.
 * </p>
 */
public class ByteRangeTest {

	private static final long TOTAL = 1000;

	@Test
	public void shouldSendTheWholeEntityWithoutARangeHeader() {
		assertThat(ByteRange.parse(null, TOTAL)).isNull();
	}

	@Test
	public void shouldParseAClosedRange() {
		ByteRange range = ByteRange.parse("bytes=0-499", TOTAL);
		assertThat(range).isNotNull();
		assertThat(range.unsatisfiable()).isFalse();
		assertThat(range.start()).isZero();
		assertThat(range.end()).isEqualTo(499);
		assertThat(range.length()).isEqualTo(500);
	}

	@Test
	public void shouldParseAnOpenEndedRange() {
		// The probe a video element opens with.
		ByteRange range = ByteRange.parse("bytes=0-", TOTAL);
		assertThat(range.end()).isEqualTo(TOTAL - 1);
		assertThat(range.length()).isEqualTo(TOTAL);
	}

	@Test
	public void shouldParseASuffixRange() {
		// "the last 100 bytes" - how players find an MP4 moov atom stored at the end.
		ByteRange range = ByteRange.parse("bytes=-100", TOTAL);
		assertThat(range.start()).isEqualTo(900);
		assertThat(range.end()).isEqualTo(999);
		assertThat(range.length()).isEqualTo(100);
	}

	@Test
	public void shouldClampAnEndBeyondTheEntity() {
		ByteRange range = ByteRange.parse("bytes=990-5000", TOTAL);
		assertThat(range.end()).isEqualTo(999);
		assertThat(range.length()).isEqualTo(10);
	}

	@Test
	public void shouldMarkAStartBeyondTheEntityUnsatisfiable() {
		// Answered with 416 plus "Content-Range: bytes */total", not with a truncated body.
		assertThat(ByteRange.parse("bytes=1000-1100", TOTAL).unsatisfiable()).isTrue();
		assertThat(ByteRange.parse("bytes=500-499", TOTAL).unsatisfiable()).isTrue();
		assertThat(ByteRange.parse("bytes=-0", TOTAL).unsatisfiable()).isTrue();
	}

	@Test
	public void shouldIgnoreRangesItWillNotSatisfy() {
		// Multi-range: legal to answer with the full entity, and no browser media element asks for it.
		assertThat(ByteRange.parse("bytes=0-99,200-299", TOTAL)).isNull();
		// Unknown unit, malformed number, missing dash - all ignored rather than rejected, per RFC 9110.
		assertThat(ByteRange.parse("items=0-99", TOTAL)).isNull();
		assertThat(ByteRange.parse("bytes=abc-def", TOTAL)).isNull();
		assertThat(ByteRange.parse("bytes=100", TOTAL)).isNull();
	}

	@Test
	public void shouldIgnoreRangesWhenTheSizeIsUnknown() {
		// A backend that cannot report a size cannot honour a range; sending the whole entity is the
		// only correct answer, and Content-Range would be unconstructable anyway.
		assertThat(ByteRange.parse("bytes=0-499", -1)).isNull();
	}
}
