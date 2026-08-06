package io.metaloom.cortex.node.filter;

import static io.metaloom.cortex.media.test.assertj.NodeAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.attribute.FileTime;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Map;

import javax.inject.Provider;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.metaloom.cortex.api.node.NodeInputs;
import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.node.filter.DateFilterStrategy.Range;
import io.metaloom.cortex.pipeline.test.StubLoomMedia;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Date bucketing, against the file's last-modified time. Built with no {@code LLMProvider} in the
 * strategy map, like the other two metadata strategies.
 */
class DateFilterNodeTest {

	@TempDir
	File tempDir;

	private FilterNode node(JsonObject nodeDef) {
		Provider<FilterStrategy> strategy = DateFilterStrategy::new;
		FilterNode node = new FilterNode(null, new CortexOptions().setMetaPath(tempDir.toPath()), new FilterNodeOptions(),
			Map.of(FilterBy.DATE, strategy));
		node.configure(nodeDef);
		return node;
	}

	private static JsonObject nodeDef() {
		return new JsonObject().put("id", "by-date").put("filterBy", "DATE")
			.put("buckets", new JsonArray()
				.add(new JsonObject().put("id", "recent").put("label", "Recent").put("match", "age<30d"))
				.add(new JsonObject().put("id", "archive").put("label", "Archive").put("match", "age>1y")));
	}

	/** A file whose mtime is {@code daysAgo} days back. */
	private StubLoomMedia file(String name, int daysAgo) throws Exception {
		File file = new File(tempDir, name);
		Files.writeString(file.toPath(), "content is irrelevant - the timestamp is the input");
		Files.setLastModifiedTime(file.toPath(), FileTime.from(ZonedDateTime.now().minusDays(daysAgo).toInstant()));
		return StubLoomMedia.ofFile(file);
	}

	private NodeResult run(FilterNode node, StubLoomMedia media) {
		return node.process(NodeContext.create(media, NodeInputs.empty()));
	}

	@Test
	void testARecentFileLandsOnItsBucketPortAndNowhereElse() throws Exception {
		StubLoomMedia media = file("today.txt", 2);

		assertThat(run(node(nodeDef()), media))
			.isSuccess()
			.hasOutput(FilterNode.bucketPort("recent"), media.absolutePath())
			.hasNoOutput(FilterNode.bucketPort("archive"))
			.hasNoOutput(FilterNode.OUT_OTHER)
			.hasOutput(FilterNode.OUT_PASSED, Boolean.TRUE)
			.hasOutput(FilterNode.OUT_BUCKET, "recent");
	}

	@Test
	void testAnOldFileTakesTheOtherBranch() throws Exception {
		StubLoomMedia media = file("ancient.txt", 800);

		assertThat(run(node(nodeDef()), media))
			.isSuccess()
			.hasOutput(FilterNode.bucketPort("archive"), media.absolutePath())
			.hasNoOutput(FilterNode.bucketPort("recent"))
			.hasOutput(FilterNode.OUT_BUCKET, "archive");
	}

	/** Six months old is neither of the two configured buckets — the gap is exactly what 'other' is for. */
	@Test
	void testAnUnmatchedItemGoesToOther() throws Exception {
		StubLoomMedia media = file("middling.txt", 180);

		assertThat(run(node(nodeDef()), media))
			.isSuccess()
			.hasOutput(FilterNode.OUT_OTHER, media.absolutePath())
			.hasNoOutput(FilterNode.bucketPort("recent"))
			.hasNoOutput(FilterNode.bucketPort("archive"))
			.hasOutput(FilterNode.OUT_PASSED, Boolean.FALSE)
			.hasOutput(FilterNode.OUT_BUCKET, "other");
	}

	@Test
	void testAbsoluteDatesRouteToo() throws Exception {
		LocalDate yesterday = LocalDate.now(ZoneId.systemDefault()).minusDays(1);
		JsonObject def = nodeDef().put("buckets", new JsonArray()
			.add(new JsonObject().put("id", "that_day").put("match", yesterday.toString()))
			.add(new JsonObject().put("id", "before").put("match", "<" + yesterday)));

		assertEquals("that_day", run(node(def), file("a.txt", 1)).get(FilterNode.OUT_BUCKET),
			"a bare date is that single day");
		assertEquals("before", run(node(def), file("b.txt", 5)).get(FilterNode.OUT_BUCKET));
	}

	@Test
	void testAbsoluteConditionForms() {
		ZonedDateTime now = LocalDate.of(2025, 6, 1).atStartOfDay(ZoneId.systemDefault());
		long newYearsDay2024 = LocalDate.of(2024, 1, 1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
		long lastMomentOf2024 = LocalDate.of(2025, 1, 1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() - 1;

		// Both ends of a range cover their whole day - that is what the two dates mean to whoever typed them.
		Range year = DateFilterStrategy.parse("2024-01-01..2024-12-31", now);
		assertTrue(year.holds(newYearsDay2024));
		assertTrue(year.holds(lastMomentOf2024), "31 December is in '..2024-12-31', not excluded by it");
		assertFalse(year.holds(lastMomentOf2024 + 1));

		assertTrue(DateFilterStrategy.parse(">=2024-01-01", now).holds(newYearsDay2024));
		assertFalse(DateFilterStrategy.parse(">2024-01-01", now).holds(newYearsDay2024), "'after 1 Jan' starts on 2 Jan");
		assertTrue(DateFilterStrategy.parse("<=2024-12-31", now).holds(lastMomentOf2024));
		assertFalse(DateFilterStrategy.parse("<2024-01-01", now).holds(newYearsDay2024));
	}

	@Test
	void testAgeConditionForms() {
		ZonedDateTime now = ZonedDateTime.now();
		long tenDaysAgo = now.minusDays(10).toInstant().toEpochMilli();
		long twoYearsAgo = now.minusYears(2).toInstant().toEpochMilli();

		assertTrue(DateFilterStrategy.parse("age<30d", now).holds(tenDaysAgo));
		assertFalse(DateFilterStrategy.parse("age<30d", now).holds(twoYearsAgo));
		assertTrue(DateFilterStrategy.parse("age>1y", now).holds(twoYearsAgo));
		assertFalse(DateFilterStrategy.parse("age>1y", now).holds(tenDaysAgo));
		assertTrue(DateFilterStrategy.parse("age<2w", now).holds(tenDaysAgo));
		assertTrue(DateFilterStrategy.parse("age>6h", now).holds(tenDaysAgo));
		assertTrue(DateFilterStrategy.parse("age>=6m", now).holds(twoYearsAgo));
	}

	/**
	 * {@code <30d} without the prefix is refused on purpose: it reads as "before" and would mean
	 * "newer than", and the two are opposites. Silently guessing would route a whole run backwards.
	 */
	@Test
	void testABareDurationIsNotADateCondition() {
		ZonedDateTime now = ZonedDateTime.now();
		assertNull(DateFilterStrategy.parse("<30d", now));
		assertNull(DateFilterStrategy.parse("30d", now));
		assertNull(DateFilterStrategy.parse("age30d", now), "an age still needs a comparison");
		assertNull(DateFilterStrategy.parse("last month", now));
	}

	@Test
	void testAnUnparseableHintIsRejectedAtConfigureTime() {
		IllegalStateException e = assertThrows(IllegalStateException.class,
			() -> node(nodeDef().put("buckets", new JsonArray().add(new JsonObject().put("id", "recent").put("match", "last month")))));

		assertTrue(e.getMessage().contains("last month"), e.getMessage());
		assertTrue(e.getMessage().contains("recent"), e.getMessage());
	}

	@Test
	void testABucketWithNoHintIsRejectedAtConfigureTime() {
		IllegalStateException e = assertThrows(IllegalStateException.class,
			() -> node(nodeDef().put("buckets", new JsonArray().add(new JsonObject().put("id", "recent")))));

		assertTrue(e.getMessage().contains("needs a date condition"), e.getMessage());
	}

	@Test
	void testTheProducerVersionNamesTheStrategy() {
		assertTrue(node(nodeDef()).producerVersion().startsWith("filter/1:DATE:"));
	}
}
