package io.metaloom.loom.api.uuid;

import java.security.SecureRandom;
import java.util.UUID;

/**
 * Generates the time ordered (version 7) uuids Loom uses for primary keys.
 *
 * <p>
 * The database generates these itself - every uuid column defaults to <code>uuidv7()</code> since migration V2.104 - so this class exists only for the
 * handful of rows whose id has to be known before the insert is flushed. Those two generators must agree on the format, or the affected tables would
 * sort half by insertion time and half at random.
 * </p>
 *
 * <p>
 * Why version 7 at all: the REST list endpoints page with a keyset cursor over the uuid column, so the uuid <em>is</em> the default sort order. A
 * version 4 uuid is uniformly random, which puts a newly created element at an arbitrary position in the list. A version 7 uuid carries a 48 bit unix
 * millisecond timestamp in its high bits, so byte order is insertion order and new elements land at the end where a caller expects them.
 * </p>
 *
 * <p>
 * Ordering holds <em>within</em> a millisecond too. RFC 9562 leaves the 12 bit <code>rand_a</code> field free for exactly this, so it is used as a
 * counter rather than as noise: without it a burst of inserts inside one millisecond would come back shuffled, which is the same defect at a smaller
 * scale. The counter is seeded randomly low on each new millisecond, leaving room to climb while keeping ids unguessable.
 * </p>
 */
public final class LoomUUID {

	private static final SecureRandom RANDOM = new SecureRandom();

	/** Version 7 marker, occupying bits 15..12 of the most significant long. */
	private static final long VERSION_7 = 0x7000L;

	/** RFC 4122 variant marker (0b10), occupying the top two bits of the least significant long. */
	private static final long VARIANT_RFC4122 = 0x8000_0000_0000_0000L;

	/** Mask clearing the two variant bits so they can be set explicitly. */
	private static final long VARIANT_MASK = 0x3FFF_FFFF_FFFF_FFFFL;

	/** The 48 bit timestamp field. */
	private static final long TIMESTAMP_MASK = 0xFFFF_FFFF_FFFFL;

	/** The 12 bit rand_a field, used here as an intra millisecond counter. */
	private static final int COUNTER_MAX = 0xFFF;

	/**
	 * Upper bound for the per millisecond counter seed. A quarter of the counter space leaves at least 3072 ids of headroom inside one millisecond,
	 * which is far more than any single writer here produces.
	 */
	private static final int COUNTER_SEED_BOUND = 0x400;

	private static long lastTimestamp = -1L;

	private static int counter = 0;

	private LoomUUID() {
	}

	/**
	 * Create a time ordered (version 7) uuid.
	 *
	 * <p>
	 * Successive calls return increasing uuids under the unsigned byte ordering Postgres compares with, which is what makes them usable as a keyset
	 * cursor.
	 * </p>
	 *
	 * @return a fresh uuid
	 */
	public static synchronized UUID timeOrdered() {
		long now = System.currentTimeMillis();
		if (now > lastTimestamp) {
			lastTimestamp = now;
			counter = RANDOM.nextInt(COUNTER_SEED_BOUND);
		} else {
			// Same millisecond, or a clock that stepped backwards - an NTP correction, a suspended VM. Both
			// are handled by ignoring the reading and continuing to climb from the last id issued, because a
			// uuid that goes backwards would corrupt the page cursor, whereas one that runs slightly ahead of
			// the wall clock costs nothing.
			counter++;
			if (counter > COUNTER_MAX) {
				lastTimestamp++;
				counter = RANDOM.nextInt(COUNTER_SEED_BOUND);
			}
		}
		long msb = ((lastTimestamp & TIMESTAMP_MASK) << 16) | VERSION_7 | (counter & COUNTER_MAX);
		long lsb = (RANDOM.nextLong() & VARIANT_MASK) | VARIANT_RFC4122;
		return new UUID(msb, lsb);
	}

	/**
	 * Read back the creation instant encoded in a version 7 uuid.
	 *
	 * <p>
	 * Intended for diagnostics - "when was this row written" without joining anything. Returns -1 for any uuid that is not version 7, which includes
	 * every row created before V2.104.
	 * </p>
	 *
	 * @param uuid the uuid to inspect
	 * @return unix epoch milliseconds, or -1 when the uuid carries no timestamp
	 */
	public static long timestampOf(UUID uuid) {
		if (uuid == null || uuid.version() != 7) {
			return -1L;
		}
		return uuid.getMostSignificantBits() >>> 16 & TIMESTAMP_MASK;
	}
}
