package io.metaloom.cortex.node.metadata;

import java.util.Locale;

/**
 * What to do with a coordinate found inside a file.
 *
 * <p>
 * This is a first-class option rather than a footnote because an EXIF GPS tag is frequently a home
 * address. A shared or public pool wants less precision than an internal archive, and the policy
 * belongs on the <em>pipeline</em> so one library can round while another keeps full precision.
 * </p>
 *
 * <p>
 * <b>{@link #ROUND} is not a compliance control.</b> Rounding on ingest destroys the data
 * irreversibly, in the database, for every consumer - including the ones that were entitled to it.
 * The durable answer is full precision stored and redaction applied on <em>export</em>. Reach for
 * {@link #ROUND} or {@link #DROP} when the ingesting worker genuinely must not learn the position,
 * not as a way of controlling who sees it later.
 * </p>
 */
public enum GpsPolicy {

	/** Store the coordinate exactly as the file states it. The default: a DAM's job is to keep what the file says. */
	KEEP,

	/** Round to {@code gpsRoundDecimals} places. Two places is roughly 1.1 km at the equator. */
	ROUND,

	/** Ignore coordinates entirely. No geo component is written and the geo port stays empty. */
	DROP;

	public static GpsPolicy parse(String value) {
		if (value == null || value.isBlank()) {
			return KEEP;
		}
		try {
			return valueOf(value.trim().toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException e) {
			throw new IllegalArgumentException("Unknown gpsPolicy '" + value + "'; expected KEEP, ROUND or DROP");
		}
	}
}
