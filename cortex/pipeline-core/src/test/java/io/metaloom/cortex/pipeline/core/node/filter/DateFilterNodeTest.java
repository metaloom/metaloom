package io.metaloom.cortex.pipeline.core.node.filter;

import static io.metaloom.cortex.pipeline.test.assertj.PipelineAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.metaloom.cortex.pipeline.api.NodeResult;
import io.metaloom.cortex.pipeline.api.PipelineResult;
import io.metaloom.cortex.pipeline.api.node.PipelineNode;
import io.metaloom.cortex.pipeline.core.node.filter.DateFilterNode.DateField;
import io.metaloom.cortex.pipeline.test.StubLoomMedia;

class DateFilterNodeTest extends AbstractFilterNodeTest {

	private static final Instant EPOCH_2020 = Instant.parse("2020-01-01T00:00:00Z");
	private static final Instant EPOCH_2021 = Instant.parse("2021-01-01T00:00:00Z");
	private static final Instant EPOCH_2022 = Instant.parse("2022-01-01T00:00:00Z");

	@TempDir
	File tempDir;

	/**
	 * Create a real file and stamp its modification time. Only {@code MODIFIED}
	 * is exercised against real timestamps — on most Linux filesystems
	 * {@code creationTime()} silently falls back to the modification time, so a
	 * test that asserted they differ would be testing the filesystem.
	 */
	private StubLoomMedia mediaModifiedAt(String name, Instant modified) throws IOException {
		StubLoomMedia media = StubLoomMedia.ofBytes(tempDir, name, "content");
		Files.setLastModifiedTime(media.file().toPath(), FileTime.from(modified));
		return media;
	}

	private boolean passed(DateFilterNode filter, StubLoomMedia media) {
		return evaluate(filter, media).<Boolean> getOutput(PipelineNode.FILTER_PASSED);
	}

	@Test
	void testBuildRequiresAtLeastOneBound() {
		assertThatThrownBy(() -> DateFilterNode.builder("date").build())
				.isInstanceOf(IllegalStateException.class)
				.hasMessage("At least one of minDate or maxDate must be set");

		assertThatThrownBy(() -> DateFilterNode.builder("date").dateField(DateField.CREATED).build())
				.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void testTimestampInsideTheRangePasses() throws IOException {
		DateFilterNode filter = DateFilterNode.builder("date")
				.minDate(EPOCH_2020)
				.maxDate(EPOCH_2022)
				.build();

		assertThat(passed(filter, mediaModifiedAt("inside.bin", EPOCH_2021))).isTrue();
	}

	@Test
	void testTimestampBeforeMinIsRejected() throws IOException {
		DateFilterNode filter = DateFilterNode.builder("date").minDate(EPOCH_2021).build();

		assertThat(passed(filter, mediaModifiedAt("old.bin", EPOCH_2020))).isFalse();
	}

	@Test
	void testTimestampAfterMaxIsRejected() throws IOException {
		DateFilterNode filter = DateFilterNode.builder("date").maxDate(EPOCH_2021).build();

		assertThat(passed(filter, mediaModifiedAt("new.bin", EPOCH_2022))).isFalse();
	}

	@Test
	void testBoundsAreInclusive() throws IOException {
		DateFilterNode minFilter = DateFilterNode.builder("date").minDate(EPOCH_2021).build();
		DateFilterNode maxFilter = DateFilterNode.builder("date").maxDate(EPOCH_2021).build();

		assertThat(passed(minFilter, mediaModifiedAt("on-min.bin", EPOCH_2021)))
				.as("timestamp exactly on minDate")
				.isTrue();
		assertThat(passed(maxFilter, mediaModifiedAt("on-max.bin", EPOCH_2021)))
				.as("timestamp exactly on maxDate")
				.isTrue();
	}

	/**
	 * A file that is not on disk yields no timestamp, and the filter fails open
	 * rather than dropping the item.
	 */
	@Test
	void testMissingFilePasses() {
		DateFilterNode filter = DateFilterNode.builder("date").minDate(EPOCH_2021).build();
		StubLoomMedia absent = new StubLoomMedia(new File(tempDir, "does-not-exist.bin").getAbsolutePath());

		assertThat(passed(filter, absent)).isTrue();
	}

	@Test
	void testUpstreamResultsAreIgnored() throws IOException {
		DateFilterNode filter = DateFilterNode.builder("date").minDate(EPOCH_2021).build();
		StubLoomMedia media = mediaModifiedAt("old.bin", EPOCH_2020);

		NodeResult result = evaluate(filter, media,
				upstream("exif", java.util.Map.of("creation_date", EPOCH_2022.toString())));

		assertThat(result.<Boolean> getOutput(PipelineNode.FILTER_PASSED))
				.as("the filter reads the filesystem, not upstream metadata")
				.isFalse();
	}

	@Test
	void testRejectReasonNamesTheDateField() throws IOException {
		DateFilterNode filter = DateFilterNode.builder("date")
				.minDate(EPOCH_2021)
				.dateField(DateField.MODIFIED)
				.build();
		NodeResult result = evaluate(filter, mediaModifiedAt("old.bin", EPOCH_2020));

		assertThat(result.<String> getOutput("filter_reason"))
				.isEqualTo("file date out of range (MODIFIED)");
	}

	@Test
	void testDefaultDateFieldIsModified() throws IOException {
		DateFilterNode filter = DateFilterNode.builder("date").minDate(EPOCH_2021).build();
		NodeResult result = evaluate(filter, mediaModifiedAt("old.bin", EPOCH_2020));

		assertThat(result.<String> getOutput("filter_reason"))
				.isEqualTo("file date out of range (MODIFIED)");
	}

	@Test
	void testPassRoutesToPassBranch() throws IOException {
		DateFilterNode filter = DateFilterNode.builder("date").minDate(EPOCH_2020).build();
		StubLoomMedia media = mediaModifiedAt("recent.bin", Instant.now().truncatedTo(ChronoUnit.SECONDS));

		PipelineResult result = route(media, filter);

		assertThat(result)
				.isSuccess()
				.hasNodeOutput("date", PipelineNode.FILTER_PASSED, true);
		assertThat(result).node(PASS_NODE).isCompleted();
		assertThat(result).node(REJECT_NODE).isSkipped();
	}

	@Test
	void testRejectRoutesToRejectBranch() throws IOException {
		DateFilterNode filter = DateFilterNode.builder("date").minDate(EPOCH_2021).build();
		StubLoomMedia media = mediaModifiedAt("old.bin", EPOCH_2020);

		PipelineResult result = route(media, filter);

		assertThat(result)
				.isSuccess()
				.hasNodeOutput("date", PipelineNode.FILTER_PASSED, false);
		assertThat(result).node(REJECT_NODE).isCompleted();
		assertThat(result).node(PASS_NODE).isSkipped();
	}
}
