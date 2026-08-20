package io.metaloom.loom.mcp.tool.impl.search;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns the date words a person types into an absolute half-open instant range.
 *
 * <p>
 * <b>Why this is server side.</b> "Today" is the single most common narrowing in a media catalogue and the single thing a language model is worst at:
 * it has no clock, and asking it to emit an ISO instant means asking it to guess today's date. Every observed failure of that shape is silent - the
 * model confidently emits a date some months off and the search returns a plausible, wrong, empty result. Resolving here means the model emits the
 * word it read and the server emits the instant, which is the division of labour each side is actually good at.
 * </p>
 *
 * <p>
 * <b>Half-open, always.</b> A range is {@code [from, to)}. "Today" ends at tomorrow's midnight rather than at 23:59:59.999, so an asset created in the
 * last second of the day is not silently outside it. The caller narrows with {@code sort_date >= from} and {@code sort_date <= to}; passing a
 * half-open upper bound to an inclusive comparison over-selects by at most the width of one timestamp tick, which is the safe direction.
 * </p>
 *
 * <p>
 * The zone matters and there is no per-user timezone in Loom, so the caller passes one explicitly and the tool documents which it used. Everything
 * here is pure - no clock is read internally, the reference instant is an argument - which is what makes it testable without freezing time.
 * </p>
 */
public final class DateExpressions {

	/** A resolved half-open range. Either bound may be null, meaning unbounded on that side. */
	public record Range(Instant from, Instant to, String label) {
	}

	private static final Pattern LAST_N_DAYS = Pattern.compile("^(?:last|past)\\s+(\\d{1,4})\\s+days?$");

	private static final Pattern N_DAYS_AGO = Pattern.compile("^(\\d{1,4})\\s+days?\\s+ago$");

	private DateExpressions() {
	}

	/**
	 * Resolve one expression.
	 *
	 * @param expression
	 *            a relative word ({@code today}, {@code yesterday}, {@code this week}, {@code last 7 days}, {@code 3 days ago}), a local date
	 *            ({@code 2026-08-18}) or a full ISO instant ({@code 2026-08-18T09:00:00Z})
	 * @param now
	 *            the reference instant
	 * @param zone
	 *            the zone the relative words are interpreted in
	 * @return the resolved range, or {@code null} when the expression is not understood. A null answer is reported to the model, never guessed at.
	 */
	public static Range resolve(String expression, Instant now, ZoneId zone) {
		if (expression == null || expression.isBlank()) {
			return null;
		}
		String value = expression.trim();
		String key = value.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
		LocalDate today = ZonedDateTime.ofInstant(now, zone).toLocalDate();

		switch (key) {
		case "today":
			return day(today, zone, "today");
		case "yesterday":
			return day(today.minusDays(1), zone, "yesterday");
		case "tomorrow":
			return day(today.plusDays(1), zone, "tomorrow");
		case "today or yesterday", "yesterday or today", "since yesterday", "last 2 days", "past 2 days":
			return between(today.minusDays(1), today.plusDays(1), zone, "yesterday and today");
		case "this week":
			LocalDate weekStart = today.with(DayOfWeek.MONDAY);
			return between(weekStart, weekStart.plusWeeks(1), zone, "this week");
		case "last week":
			LocalDate previous = today.with(DayOfWeek.MONDAY).minusWeeks(1);
			return between(previous, previous.plusWeeks(1), zone, "last week");
		case "this month":
			LocalDate monthStart = today.withDayOfMonth(1);
			return between(monthStart, monthStart.plusMonths(1), zone, "this month");
		case "last month":
			LocalDate lastMonth = today.withDayOfMonth(1).minusMonths(1);
			return between(lastMonth, lastMonth.plusMonths(1), zone, "last month");
		case "this year":
			LocalDate yearStart = today.withDayOfYear(1);
			return between(yearStart, yearStart.plusYears(1), zone, "this year");
		default:
			break;
		}

		Matcher lastN = LAST_N_DAYS.matcher(key);
		if (lastN.matches()) {
			int days = Integer.parseInt(lastN.group(1));
			return between(today.minusDays(days - 1L), today.plusDays(1), zone, "the last " + days + " days");
		}
		Matcher ago = N_DAYS_AGO.matcher(key);
		if (ago.matches()) {
			LocalDate then = today.minusDays(Integer.parseInt(ago.group(1)));
			return day(then, zone, then.toString());
		}

		// An explicit date: the whole of that local day, so "from: 2026-08-18" behaves the way a person
		// means it rather than snapping to midnight and excluding the day itself on the upper bound.
		try {
			LocalDate date = LocalDate.parse(value);
			return day(date, zone, date.toString());
		} catch (DateTimeParseException ignored) {
			// not a local date; try a full instant below
		}
		try {
			Instant instant = Instant.parse(value);
			return new Range(instant, instant, value);
		} catch (DateTimeParseException ignored) {
			return null;
		}
	}

	/**
	 * The words this understands, for an error message. Kept in one place so the message a model reads and the switch above cannot drift.
	 */
	public static String vocabulary() {
		return "today, yesterday, tomorrow, today or yesterday, this week, last week, this month, last month, this year, "
			+ "'last N days', 'N days ago', a date (2026-08-18) or a full ISO instant (2026-08-18T09:00:00Z)";
	}

	private static Range day(LocalDate date, ZoneId zone, String label) {
		return between(date, date.plusDays(1), zone, label);
	}

	private static Range between(LocalDate fromInclusive, LocalDate toExclusive, ZoneId zone, String label) {
		return new Range(
			fromInclusive.atStartOfDay(zone).toInstant(),
			toExclusive.atStartOfDay(zone).toInstant().minus(1, ChronoUnit.MILLIS),
			label);
	}

}
