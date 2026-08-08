package io.metaloom.cortex.node.filter;

import java.nio.file.Files;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import javax.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.vertx.core.json.JsonObject;

/**
 * Routes an item by its last-modified time.
 *
 * <p>
 * <strong>No model, no round trip, no {@code LLMProvider}.</strong> The timestamp is the file's
 * modification time. It is deliberately <em>not</em> a capture date read out of EXIF: that would
 * make the node's answer depend on a metadata extractor having run first, and would leave every
 * item without EXIF — every video, every document — in {@code other} for a reason the author cannot
 * see from the graph. A pipeline that wants the capture date wires the metadata node's output into a
 * {@code tag} rule instead.
 * </p>
 *
 * <p>
 * A bucket's {@code match} is a comma-separated list of conditions, and the bucket wins if
 * <em>any</em> of them holds:
 * </p>
 * <ul>
 * <li>{@code >=2024-01-01}, {@code <2020-01-01}, {@code >2024-06-30}, {@code <=2024-12-31} — a
 * comparison against an ISO date. The bound is a whole day: {@code <=2024-12-31} includes every
 * moment of 31 December, and {@code >2024-06-30} starts at midnight on 1 July</li>
 * <li>{@code 2024-01-01..2024-12-31} — a range, <strong>both ends inclusive of the whole day</strong>,
 * which is how the two dates read to the person typing them</li>
 * <li>{@code 2024-03-17} — a bare date is that single day</li>
 * <li>{@code age<30d}, {@code age>=1y} — an age relative to now, in {@code h}, {@code d}, {@code w},
 * {@code m} (months) or {@code y}. The {@code age} prefix is required, because {@code <30d} would
 * read as "before" while meaning "newer than" and the two are opposites</li>
 * </ul>
 *
 * <p>
 * Dates are resolved in the worker's default time zone, and buckets are tried in declaration order
 * with the first match winning. A hint that parses as none of the above is reported from
 * {@code configure(...)} through {@link #validateBuckets(List)}.
 * </p>
 */
public class DateFilterStrategy implements FilterStrategy {

	private static final Logger log = LoggerFactory.getLogger(DateFilterStrategy.class);

	@Inject
	public DateFilterStrategy() {
	}

	@Override
	public FilterBy filterBy() {
		return FilterBy.DATE;
	}

	@Override
	public List<String> validateBuckets(List<FilterBucket> buckets) {
		ZonedDateTime now = ZonedDateTime.now();
		List<String> errors = new ArrayList<>();
		for (FilterBucket bucket : buckets) {
			if (bucket.match() == null) {
				errors.add("bucket '" + bucket.id() + "' needs a date condition, for example '>=2024-01-01', "
					+ "'2024-01-01..2024-12-31' or 'age<30d'");
				continue;
			}
			for (String hint : hints(bucket)) {
				if (parse(hint, now) == null) {
					errors.add("bucket '" + bucket.id() + "': '" + hint
						+ "' is not a date condition; expected '<2020-01-01', '>=2024-01-01', '2024-01-01..2024-12-31', "
						+ "a bare '2024-03-17' or an age such as 'age<30d'");
				}
			}
		}
		return errors;
	}

	@Override
	public Classification classify(FilterItem item, FilterNodeOptions options, List<FilterBucket> buckets)
		throws Exception {
		// Propagates: a timestamp we cannot read is the worker failing, not a routing answer.
		long modified = Files.getLastModifiedTime(item.media().path()).toMillis();
		ZonedDateTime now = ZonedDateTime.now();
		JsonObject detail = new JsonObject().put("modified", Instant.ofEpochMilli(modified).toString());

		for (FilterBucket bucket : buckets) {
			for (String hint : hints(bucket)) {
				Range range = parse(hint, now);
				// null only when configure() was bypassed; validateBuckets reports it on the normal path.
				if (range != null && range.holds(modified)) {
					return Classification.of(bucket.id(), 1, detail.put("matched", hint));
				}
			}
		}

		log.debug("Date filter found no bucket for {} (modified {})", item.media().absolutePath(), detail.getString("modified"));
		return Classification.of(Classification.OTHER, 1, detail);
	}

	private static List<String> hints(FilterBucket bucket) {
		if (bucket.match() == null) {
			return List.of();
		}
		return Arrays.stream(bucket.match().split(","))
			.map(hint -> hint.trim().toLowerCase(Locale.ROOT))
			.filter(hint -> !hint.isEmpty())
			.toList();
	}

	/** An epoch-millis window. {@code min} is inclusive, {@code max} exclusive; {@code null} is unbounded. */
	record Range(Long min, Long max) {

		boolean holds(long millis) {
			return (min == null || millis >= min) && (max == null || millis < max);
		}
	}

	/**
	 * @param hint
	 *            a hint, already trimmed and lowercased
	 * @param now
	 *            the reference point for an {@code age} hint, so a test can pin it
	 * @return the window, or {@code null} when the hint is not a date condition
	 */
	static Range parse(String hint, ZonedDateTime now) {
		if (hint.startsWith("age")) {
			return age(hint.substring(3).trim(), now);
		}

		int range = hint.indexOf("..");
		if (range >= 0) {
			LocalDate from = date(hint.substring(0, range).trim());
			LocalDate to = date(hint.substring(range + 2).trim());
			// The upper end is inclusive of its whole day: '2024-01-01..2024-12-31' is the year, not
			// the year minus its last day. That is what the two dates mean to whoever typed them.
			return from == null || to == null ? null : new Range(startOf(from, now), startOf(to.plusDays(1), now));
		}

		if (hint.startsWith("<=")) {
			LocalDate value = date(hint.substring(2).trim());
			return value == null ? null : new Range(null, startOf(value.plusDays(1), now));
		}
		if (hint.startsWith(">=")) {
			LocalDate value = date(hint.substring(2).trim());
			return value == null ? null : new Range(startOf(value, now), null);
		}
		if (hint.startsWith("<")) {
			LocalDate value = date(hint.substring(1).trim());
			return value == null ? null : new Range(null, startOf(value, now));
		}
		if (hint.startsWith(">")) {
			LocalDate value = date(hint.substring(1).trim());
			return value == null ? null : new Range(startOf(value.plusDays(1), now), null);
		}

		LocalDate value = date(hint);
		return value == null ? null : new Range(startOf(value, now), startOf(value.plusDays(1), now));
	}

	/**
	 * {@code <30d} and {@code <=30d} are the same window here, and so are {@code >} and {@code >=}: a
	 * boundary an instant wide has no meaning against a file timestamp, and pretending otherwise would
	 * only invite someone to test it.
	 */
	private static Range age(String condition, ZonedDateTime now) {
		String operator = condition.startsWith("<=") || condition.startsWith(">=")
			? condition.substring(0, 2)
			: condition.isEmpty() ? "" : condition.substring(0, 1);
		if (!operator.startsWith("<") && !operator.startsWith(">")) {
			return null;
		}

		ZonedDateTime boundary = minus(now, condition.substring(operator.length()).trim());
		if (boundary == null) {
			return null;
		}
		long millis = boundary.toInstant().toEpochMilli();
		// "younger than 30d" is "modified after now-30d"; "older than 1y" is "modified before now-1y".
		return operator.startsWith("<") ? new Range(millis, null) : new Range(null, millis);
	}

	/**
	 * @param duration
	 *            a whole number with an {@code h}/{@code d}/{@code w}/{@code m}/{@code y} suffix
	 * @return {@code now} moved back by it, or {@code null} when it is not a duration
	 */
	private static ZonedDateTime minus(ZonedDateTime now, String duration) {
		if (duration.length() < 2) {
			return null;
		}
		char unit = duration.charAt(duration.length() - 1);
		long amount;
		try {
			amount = Long.parseLong(duration.substring(0, duration.length() - 1));
		} catch (NumberFormatException e) {
			return null;
		}
		if (amount < 0) {
			return null;
		}
		// Months and years go through the calendar rather than a fixed number of days, so 'age>1y'
		// means the same thing in a leap year as outside one.
		return switch (unit) {
			case 'h' -> now.minusHours(amount);
			case 'd' -> now.minusDays(amount);
			case 'w' -> now.minusWeeks(amount);
			case 'm' -> now.minusMonths(amount);
			case 'y' -> now.minusYears(amount);
			default -> null;
		};
	}

	private static LocalDate date(String value) {
		try {
			return LocalDate.parse(value);
		} catch (DateTimeParseException e) {
			return null;
		}
	}

	/** Midnight of the given day, in the same zone the reference point is expressed in. */
	private static long startOf(LocalDate day, ZonedDateTime now) {
		ZoneId zone = now.getZone();
		return day.atStartOfDay(zone).toInstant().toEpochMilli();
	}
}
