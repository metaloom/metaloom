package io.metaloom.cortex.s3.event;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class S3EventBufferTest {

	private static final String BUCKET = "media";

	private S3EventBuffer buffer;

	@BeforeEach
	public void setup() {
		buffer = new S3EventBuffer();
	}

	@Test
	public void testRecordedHintsAreDrained() {
		buffer.record(S3ChangeHint.created(BUCKET, "2026/a.mp4"));
		buffer.record(S3ChangeHint.created(BUCKET, "2026/b.mp4"));

		assertThat(buffer.drain(BUCKET, null)).extracting(S3ChangeHint::key)
			.containsExactlyInAnyOrder("2026/a.mp4", "2026/b.mp4");
	}

	@Test
	public void testDrainingEmptiesTheBuffer() {
		buffer.record(S3ChangeHint.created(BUCKET, "2026/a.mp4"));

		buffer.drain(BUCKET, null);

		assertThat(buffer.drain(BUCKET, null)).isEmpty();
		assertThat(buffer.size(BUCKET)).isZero();
	}

	@Test
	public void testPrefixLimitsWhatIsDrained() {
		buffer.record(S3ChangeHint.created(BUCKET, "2026/a.mp4"));
		buffer.record(S3ChangeHint.created(BUCKET, "2025/old.mp4"));

		assertThat(buffer.drain(BUCKET, "2026/")).extracting(S3ChangeHint::key).containsExactly("2026/a.mp4");
		// The non-matching hint is left for whichever selection does cover it.
		assertThat(buffer.size(BUCKET)).isEqualTo(1);
	}

	@Test
	public void testRepeatedHintsForOneKeyCollapse() {
		buffer.record(S3ChangeHint.created(BUCKET, "2026/a.mp4"));
		buffer.record(S3ChangeHint.created(BUCKET, "2026/a.mp4"));

		assertThat(buffer.size(BUCKET)).isEqualTo(1);
	}

	@Test
	public void testALaterHintSupersedesAnEarlierOneForTheSameKey() {
		buffer.record(S3ChangeHint.created(BUCKET, "2026/a.mp4"));
		buffer.record(S3ChangeHint.removed(BUCKET, "2026/a.mp4"));

		assertThat(buffer.drain(BUCKET, null)).singleElement()
			.extracting(S3ChangeHint::removed).isEqualTo(true);
	}

	@Test
	public void testHasHintsIsPrefixAware() {
		buffer.record(S3ChangeHint.created(BUCKET, "2026/a.mp4"));

		assertThat(buffer.hasHints(BUCKET, "2026/")).isTrue();
		assertThat(buffer.hasHints(BUCKET, "2025/")).isFalse();
		assertThat(buffer.hasHints("other", null)).isFalse();
	}

	@Test
	public void testOverflowDegradesInsteadOfDroppingHintsSilently() {
		S3EventBuffer tiny = new S3EventBuffer(2);
		tiny.record(S3ChangeHint.created(BUCKET, "k1"));
		tiny.record(S3ChangeHint.created(BUCKET, "k2"));
		assertThat(tiny.isDegraded(BUCKET)).isFalse();

		tiny.record(S3ChangeHint.created(BUCKET, "k3"));

		// Degraded, not truncated: the next run does a full listing, so nothing stays invisible.
		assertThat(tiny.isDegraded(BUCKET)).isTrue();
		assertThat(tiny.hasHints(BUCKET, null)).isFalse();
	}

	@Test
	public void testDegradedBucketIgnoresFurtherHints() {
		S3EventBuffer tiny = new S3EventBuffer(1);
		tiny.record(S3ChangeHint.created(BUCKET, "k1"));
		tiny.record(S3ChangeHint.created(BUCKET, "k2"));

		tiny.record(S3ChangeHint.created(BUCKET, "k3"));

		assertThat(tiny.size(BUCKET)).isZero();
	}

	@Test
	public void testClearDegradedRestoresTheFastPath() {
		S3EventBuffer tiny = new S3EventBuffer(1);
		tiny.record(S3ChangeHint.created(BUCKET, "k1"));
		tiny.record(S3ChangeHint.created(BUCKET, "k2"));

		tiny.clearDegraded(BUCKET);
		tiny.record(S3ChangeHint.created(BUCKET, "k3"));

		assertThat(tiny.isDegraded(BUCKET)).isFalse();
		assertThat(tiny.hasHints(BUCKET, null)).isTrue();
	}

	@Test
	public void testDegradationIsPerBucket() {
		S3EventBuffer tiny = new S3EventBuffer(1);
		tiny.record(S3ChangeHint.created(BUCKET, "k1"));
		tiny.record(S3ChangeHint.created(BUCKET, "k2"));

		tiny.record(S3ChangeHint.created("other", "k1"));

		assertThat(tiny.isDegraded(BUCKET)).isTrue();
		assertThat(tiny.isDegraded("other")).isFalse();
		assertThat(tiny.hasHints("other", null)).isTrue();
	}
}
