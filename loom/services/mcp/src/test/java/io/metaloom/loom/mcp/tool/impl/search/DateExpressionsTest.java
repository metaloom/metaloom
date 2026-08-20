package io.metaloom.loom.mcp.tool.impl.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.mcp.tool.impl.search.DateExpressions.Range;

/**
 * The date words, resolved against a frozen clock.
 *
 * <p>
 * This is the half of natural-language search a model cannot do: it has no clock, so asked for "yesterday" it emits an ISO date it guessed. Every
 * assertion here is about the boundary rather than the happy path, because an off-by-one day is exactly the failure that returns a plausible, wrong,
 * empty result.
 * </p>
 */
public class DateExpressionsTest {

	private static final ZoneId VIENNA = ZoneId.of("Europe/Vienna");

	/** 2026-08-19T10:15:00Z is 12:15 local on a Wednesday in Vienna (CEST, UTC+2). */
	private static final Instant NOW = Instant.parse("2026-08-19T10:15:00Z");

	@Test
	public void testToday() {
		Range range = DateExpressions.resolve("today", NOW, VIENNA);
		assertNotNull(range);
		// Local midnight, not UTC midnight - the whole point of taking a zone.
		assertEquals(Instant.parse("2026-08-18T22:00:00Z"), range.from());
		assertEquals(Instant.parse("2026-08-19T21:59:59.999Z"), range.to());
		assertEquals("today", range.label());
	}

	@Test
	public void testYesterday() {
		Range range = DateExpressions.resolve("yesterday", NOW, VIENNA);
		assertEquals(Instant.parse("2026-08-17T22:00:00Z"), range.from());
		assertEquals(Instant.parse("2026-08-18T21:59:59.999Z"), range.to());
	}

	@Test
	public void testTodayOrYesterdaySpansBothDays() {
		Range range = DateExpressions.resolve("today or yesterday", NOW, VIENNA);
		assertEquals(Instant.parse("2026-08-17T22:00:00Z"), range.from());
		assertEquals(Instant.parse("2026-08-19T21:59:59.999Z"), range.to());
	}

	@Test
	public void testCaseAndSpacingAreIrrelevant() {
		// A model will send "Today" and "today  or   yesterday"; neither is a reason to refuse.
		assertNotNull(DateExpressions.resolve("Today", NOW, VIENNA));
		assertEquals(DateExpressions.resolve("today or yesterday", NOW, VIENNA).from(),
			DateExpressions.resolve("Today  or   Yesterday", NOW, VIENNA).from());
	}

	@Test
	public void testThisWeekStartsOnMonday() {
		// 2026-08-19 is a Wednesday, so the week opened on the 17th.
		Range range = DateExpressions.resolve("this week", NOW, VIENNA);
		assertEquals(Instant.parse("2026-08-16T22:00:00Z"), range.from());
	}

	@Test
	public void testLastWeekIsTheWeekBefore() {
		Range range = DateExpressions.resolve("last week", NOW, VIENNA);
		assertEquals(Instant.parse("2026-08-09T22:00:00Z"), range.from());
		assertEquals(Instant.parse("2026-08-16T21:59:59.999Z"), range.to());
	}

	@Test
	public void testLastNDaysIncludesToday() {
		// "The last 7 days" means the last week up to and including now, not a window ending yesterday.
		Range range = DateExpressions.resolve("last 7 days", NOW, VIENNA);
		assertEquals(Instant.parse("2026-08-12T22:00:00Z"), range.from());
		assertEquals(Instant.parse("2026-08-19T21:59:59.999Z"), range.to());
	}

	@Test
	public void testNDaysAgoIsThatDayOnly() {
		Range range = DateExpressions.resolve("3 days ago", NOW, VIENNA);
		assertEquals(Instant.parse("2026-08-15T22:00:00Z"), range.from());
		assertEquals(Instant.parse("2026-08-16T21:59:59.999Z"), range.to());
	}

	@Test
	public void testMonthAndYear() {
		assertEquals(Instant.parse("2026-07-31T22:00:00Z"), DateExpressions.resolve("this month", NOW, VIENNA).from());
		assertEquals(Instant.parse("2026-06-30T22:00:00Z"), DateExpressions.resolve("last month", NOW, VIENNA).from());
		assertEquals(Instant.parse("2025-12-31T23:00:00Z"), DateExpressions.resolve("this year", NOW, VIENNA).from());
	}

	@Test
	public void testExplicitDateCoversTheWholeDay() {
		// "createdTo: 2026-08-18" must include the 18th, not stop at its first instant.
		Range range = DateExpressions.resolve("2026-08-18", NOW, VIENNA);
		assertEquals(Instant.parse("2026-08-17T22:00:00Z"), range.from());
		assertEquals(Instant.parse("2026-08-18T21:59:59.999Z"), range.to());
	}

	@Test
	public void testFullInstantIsTakenLiterally() {
		Range range = DateExpressions.resolve("2026-08-18T09:00:00Z", NOW, VIENNA);
		assertEquals(Instant.parse("2026-08-18T09:00:00Z"), range.from());
		assertEquals(Instant.parse("2026-08-18T09:00:00Z"), range.to());
	}

	@Test
	public void testZoneChangesTheAnswer() {
		Range vienna = DateExpressions.resolve("today", NOW, VIENNA);
		Range utc = DateExpressions.resolve("today", NOW, ZoneId.of("UTC"));
		assertTrue(vienna.from().isBefore(utc.from()), "Vienna's day starts two hours before UTC's");
	}

	@Test
	public void testUnknownExpressionIsNullRatherThanAGuess() {
		// Answering null lets the tool report what it accepts. Guessing "now" would silently narrow to
		// an empty window and read as "nothing matched".
		assertNull(DateExpressions.resolve("some time around the summer", NOW, VIENNA));
		assertNull(DateExpressions.resolve("", NOW, VIENNA));
		assertNull(DateExpressions.resolve(null, NOW, VIENNA));
		assertNull(DateExpressions.resolve("18/08/2026", NOW, VIENNA));
	}

	@Test
	public void testVocabularyListsWhatIsAccepted() {
		String vocabulary = DateExpressions.vocabulary();
		assertTrue(vocabulary.contains("today"));
		assertTrue(vocabulary.contains("last week"));
		assertTrue(vocabulary.contains("last N days"));
	}

}
