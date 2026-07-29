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

import io.metaloom.cortex.api.node.InputPort;
import io.metaloom.cortex.pipeline.api.PipelineResult;
import io.metaloom.cortex.pipeline.core.node.filter.DateFilterNode.DateField;
import io.metaloom.cortex.pipeline.test.StubLoomMedia;
import io.metaloom.loom.nodes.spec.ContentTypeRegistry;

class DateFilterNodeTest extends AbstractFilterNodeTest {

	private static final Instant EPOCH_2020 = Instant.parse("2020-01-01T00:00:00Z");
	private static final Instant EPOCH_2021 = Instant.parse("2021-01-01T00:00:00Z");
	private static final Instant EPOCH_2022 = Instant.parse("2022-01-01T00:00:00Z");

	/**
	 * A port this filter does not declare. Used to prove that wiring metadata into the
	 * node changes nothing — the filter has no input ports at all and reads the
	 * filesystem itself.
	 */
	private static final InputPort<String> IN_EXIF_DATE =
		InputPort.one("creation_date", ContentTypeRegistry.SCALAR_STRING, String.class);

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
		return passed(evaluate(filter, media));
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

	/**
	 * The filter declares no input ports, so nothing an upstream node emits can move
	 * its verdict — not even a payload that looks exactly like the date it checks.
	 */
	@Test
	void testWiredMetadataIsIgnored() throws IOException {
		DateFilterNode filter = DateFilterNode.builder("date").minDate(EPOCH_2021).build();
		StubLoomMedia media = mediaModifiedAt("old.bin", EPOCH_2020);

		assertThat(passed(evaluate(filter, media, input(IN_EXIF_DATE, EPOCH_2022.toString()))))
				.as("the filter reads the filesystem, not wired metadata")
				.isFalse();
	}

	/**
	 * {@code MODIFIED} is the default field, so an unconfigured filter must decide
	 * exactly as an explicitly configured one does. Asserted through the verdict
	 * because the field name only ever reached the reject-reason log line.
	 */
	@Test
	void testDefaultDateFieldIsModified() throws IOException {
		DateFilterNode explicitField = DateFilterNode.builder("date")
				.minDate(EPOCH_2021)
				.dateField(DateField.MODIFIED)
				.build();
		DateFilterNode defaultField = DateFilterNode.builder("date").minDate(EPOCH_2021).build();

		StubLoomMedia old = mediaModifiedAt("old.bin", EPOCH_2020);
		StubLoomMedia recent = mediaModifiedAt("recent.bin", EPOCH_2022);

		assertThat(passed(defaultField, old)).isEqualTo(passed(explicitField, old)).isFalse();
		assertThat(passed(defaultField, recent)).isEqualTo(passed(explicitField, recent)).isTrue();
	}

	@Test
	void testPassRoutesToPassBranch() throws IOException {
		DateFilterNode filter = DateFilterNode.builder("date").minDate(EPOCH_2020).build();
		StubLoomMedia media = mediaModifiedAt("recent.bin", Instant.now().truncatedTo(ChronoUnit.SECONDS));

		PipelineResult result = route(media, filter);

		assertThat(result)
				.isSuccess()
				.hasNodeOutput("date", AbstractFilterNode.OUT_PASSED, true);
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
				.hasNodeOutput("date", AbstractFilterNode.OUT_PASSED, false);
		assertThat(result).node(REJECT_NODE).isCompleted();
		assertThat(result).node(PASS_NODE).isSkipped();
	}
}
