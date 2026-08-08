package io.metaloom.cortex.node.filter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import javax.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.vertx.core.json.JsonObject;

/**
 * Routes an item by its size in bytes.
 *
 * <p>
 * <strong>No model, no round trip, no {@code LLMProvider}.</strong> The size comes from
 * {@code LoomMedia.size()}.
 * </p>
 *
 * <p>
 * A bucket's {@code match} is a comma-separated list of thresholds, and the bucket wins if
 * <em>any</em> of them holds:
 * </p>
 * <ul>
 * <li>{@code <10MB}, {@code <=10MB}, {@code >1GB}, {@code >=1GB} — a comparison</li>
 * <li>{@code 1MB..100MB} — a range, lower bound inclusive and upper bound <em>exclusive</em>, so
 * {@code 0..1MB} and {@code 1MB..100MB} tile without an overlap nobody would notice</li>
 * <li>{@code 10MB} — a bare value is an upper bound, i.e. {@code <=10MB}. That is what makes the
 * common ladder read as written: {@code small: 1MB}, {@code medium: 100MB}, {@code large: >100MB},
 * evaluated in declaration order with the first match winning</li>
 * </ul>
 *
 * <p>
 * Units are {@code B}, {@code KB}, {@code MB}, {@code GB}, {@code TB} and are <strong>1024-based</strong>
 * — the numbers a file manager shows, not the disk vendor's. {@code KiB}/{@code MiB}/{@code GiB}/{@code TiB}
 * are accepted as explicit aliases for exactly the same values. A bare number is bytes.
 * </p>
 *
 * <p>
 * A hint that parses as none of the above is reported from {@code configure(...)} through
 * {@link #validateBuckets(List)} rather than silently never matching, because a filter whose every
 * item lands in {@code other} looks exactly like a filter whose data genuinely did not match.
 * </p>
 */
public class SizeFilterStrategy implements FilterStrategy {

	private static final Logger log = LoggerFactory.getLogger(SizeFilterStrategy.class);

	@Inject
	public SizeFilterStrategy() {
	}

	@Override
	public FilterBy filterBy() {
		return FilterBy.SIZE;
	}

	@Override
	public List<String> validateBuckets(List<FilterBucket> buckets) {
		List<String> errors = new ArrayList<>();
		for (FilterBucket bucket : buckets) {
			if (bucket.match() == null) {
				errors.add("bucket '" + bucket.id() + "' needs a size threshold, for example '<10MB', '1MB..100MB' or '>1GB'");
				continue;
			}
			for (String hint : hints(bucket)) {
				if (parse(hint) == null) {
					errors.add("bucket '" + bucket.id() + "': '" + hint
						+ "' is not a size threshold; expected '<10MB', '<=10MB', '>1GB', '>=1GB', '1MB..100MB' or a bare '10MB'");
				}
			}
		}
		return errors;
	}

	@Override
	public Classification classify(FilterItem item, FilterNodeOptions options, List<FilterBucket> buckets)
		throws Exception {
		// Propagates: a size we cannot read is the worker failing to do its job, not a routing answer.
		long size = item.media().size();
		JsonObject detail = new JsonObject().put("size", size);

		for (FilterBucket bucket : buckets) {
			for (String hint : hints(bucket)) {
				Threshold threshold = parse(hint);
				// null only when configure() was bypassed; treating it as "no match" beats an NPE
				// mid-run, and validateBuckets already reports it on the normal path.
				if (threshold != null && threshold.holds(size)) {
					return Classification.of(bucket.id(), 1, detail.put("matched", hint));
				}
			}
		}

		log.debug("Size filter found no bucket for {} ({} bytes)", item.media().absolutePath(), size);
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

	/** A parsed hint. {@code min} is inclusive, {@code max} exclusive; {@code null} means unbounded. */
	record Threshold(Long min, Long max) {

		boolean holds(long size) {
			return (min == null || size >= min) && (max == null || size < max);
		}
	}

	/**
	 * @param hint
	 *            a hint, already trimmed and lowercased
	 * @return the threshold, or {@code null} when the hint is not one
	 */
	static Threshold parse(String hint) {
		int range = hint.indexOf("..");
		if (range >= 0) {
			Long from = bytes(hint.substring(0, range).trim());
			Long to = bytes(hint.substring(range + 2).trim());
			return from == null || to == null ? null : new Threshold(from, to);
		}
		if (hint.startsWith("<=")) {
			Long value = bytes(hint.substring(2).trim());
			// Inclusive upper bound, and max is exclusive, hence the +1. Saturating rather than
			// wrapping so '<=9223372036854775807' does not silently become 'matches nothing'.
			return value == null ? null : new Threshold(null, value == Long.MAX_VALUE ? null : value + 1);
		}
		if (hint.startsWith(">=")) {
			Long value = bytes(hint.substring(2).trim());
			return value == null ? null : new Threshold(value, null);
		}
		if (hint.startsWith("<")) {
			Long value = bytes(hint.substring(1).trim());
			return value == null ? null : new Threshold(null, value);
		}
		if (hint.startsWith(">")) {
			Long value = bytes(hint.substring(1).trim());
			return value == null ? null : new Threshold(value == Long.MAX_VALUE ? null : value + 1, null);
		}
		Long value = bytes(hint);
		return value == null ? null : new Threshold(null, value == Long.MAX_VALUE ? null : value + 1);
	}

	/**
	 * @param value
	 *            a number with an optional unit suffix
	 * @return the byte count, or {@code null} when it is not one
	 */
	static Long bytes(String value) {
		if (value.isEmpty()) {
			return null;
		}
		int digits = 0;
		while (digits < value.length() && (Character.isDigit(value.charAt(digits)) || value.charAt(digits) == '.')) {
			digits++;
		}
		String number = value.substring(0, digits);
		String unit = value.substring(digits).trim();
		if (number.isEmpty()) {
			return null;
		}

		long multiplier = switch (unit) {
			case "", "b" -> 1L;
			case "k", "kb", "kib" -> 1024L;
			case "m", "mb", "mib" -> 1024L * 1024;
			case "g", "gb", "gib" -> 1024L * 1024 * 1024;
			case "t", "tb", "tib" -> 1024L * 1024 * 1024 * 1024;
			default -> -1L;
		};
		if (multiplier < 0) {
			return null;
		}

		try {
			// Parsed as a double so '1.5MB' works; the product is well inside a long for any real file.
			double parsed = Double.parseDouble(number);
			return parsed < 0 ? null : (long) (parsed * multiplier);
		} catch (NumberFormatException e) {
			return null;
		}
	}
}
