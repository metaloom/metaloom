package io.metaloom.loom.nodes.spec;

import java.util.Collection;

/**
 * Ordering for the {@code version} a Cortex worker announces with a {@link NodeDescriptor}.
 *
 * <p>
 * Deliberately ~100 lines of string handling rather than a Maven artifact-resolution dependency. The
 * only question ever asked of a version here is "which of these contracts is lowest", and the answer
 * has to be computable on both sides of the wire without pulling a resolver onto every worker.
 * </p>
 *
 * <p>
 * The grammar is dot-separated numeric segments with an optional {@code -qualifier}. A qualifier sorts
 * <strong>below</strong> the same numeric prefix, following the Maven convention that
 * {@code 1.0.0-SNAPSHOT} precedes the {@code 1.0.0} release. Among two qualifiers the comparison is
 * case-insensitive lexicographic — arbitrary, but deterministic and stated, which is what matters: the
 * alternative is a fleet where the active contract depends on connection order.
 * </p>
 *
 * <p>
 * Anything that does not parse is <strong>not ordered at all</strong>. Callers must check
 * {@link #isParseable(String)} first and treat the unparseable case as skew rather than guessing —
 * guessing here silently picks a contract for the whole fleet.
 * </p>
 */
public final class NodeVersions {

	private NodeVersions() {
	}

	/**
	 * Whether this version string can participate in an ordering.
	 *
	 * @param version
	 *            the version, may be null
	 * @return false for null, blank, or anything whose numeric part is not dot-separated digits
	 */
	public static boolean isParseable(String version) {
		if (version == null || version.isBlank()) {
			return false;
		}
		String numeric = numericPart(version);
		if (numeric.isEmpty()) {
			return false;
		}
		for (String segment : numeric.split("\\.", -1)) {
			if (segment.isEmpty()) {
				return false;
			}
			for (int i = 0; i < segment.length(); i++) {
				if (!Character.isDigit(segment.charAt(i))) {
					return false;
				}
			}
		}
		return true;
	}

	/** Whether this is a {@code -SNAPSHOT} version, which Maven convention makes mutable. */
	public static boolean isSnapshot(String version) {
		return version != null && version.toUpperCase().endsWith("-SNAPSHOT");
	}

	/**
	 * Compare two parseable versions.
	 *
	 * @throws IllegalArgumentException
	 *             if either side is not {@link #isParseable(String) parseable} — an unordered version
	 *             must be handled as skew by the caller, never silently coerced into an order
	 */
	public static int compare(String a, String b) {
		if (!isParseable(a) || !isParseable(b)) {
			throw new IllegalArgumentException("Cannot order unparseable versions: '" + a + "' / '" + b + "'");
		}
		String[] left = numericPart(a).split("\\.", -1);
		String[] right = numericPart(b).split("\\.", -1);
		int len = Math.max(left.length, right.length);
		for (int i = 0; i < len; i++) {
			// A missing segment is zero, so 1.0 and 1.0.0 are the same contract.
			long l = i < left.length ? parseSegment(left[i]) : 0L;
			long r = i < right.length ? parseSegment(right[i]) : 0L;
			if (l != r) {
				return Long.compare(l, r);
			}
		}
		String lq = qualifierPart(a);
		String rq = qualifierPart(b);
		if (lq.isEmpty() && rq.isEmpty()) {
			return 0;
		}
		// A release outranks any qualifier of the same numeric version.
		if (lq.isEmpty()) {
			return 1;
		}
		if (rq.isEmpty()) {
			return -1;
		}
		return lq.compareToIgnoreCase(rq);
	}

	/**
	 * The lowest of the given versions — the active contract for a node offered by several workers.
	 *
	 * @param versions
	 *            candidate versions
	 * @return the lowest, or {@code null} when the collection is empty or holds anything unparseable.
	 *         A null return means "these cannot be ordered", not "no version"
	 */
	public static String lowest(Collection<String> versions) {
		if (versions == null || versions.isEmpty()) {
			return null;
		}
		String lowest = null;
		for (String version : versions) {
			if (!isParseable(version)) {
				return null;
			}
			if (lowest == null || compare(version, lowest) < 0) {
				lowest = version;
			}
		}
		return lowest;
	}

	/** Whether every version in the collection is parseable and equal to every other. */
	public static boolean allEqual(Collection<String> versions) {
		if (versions == null || versions.isEmpty()) {
			return true;
		}
		String first = null;
		for (String version : versions) {
			if (!isParseable(version)) {
				return false;
			}
			if (first == null) {
				first = version;
			} else if (compare(first, version) != 0) {
				return false;
			}
		}
		return true;
	}

	private static long parseSegment(String segment) {
		try {
			return Long.parseLong(segment);
		} catch (NumberFormatException e) {
			// Unreachable for a parseable version; a segment longer than a long is treated as huge.
			return Long.MAX_VALUE;
		}
	}

	private static String numericPart(String version) {
		int dash = version.indexOf('-');
		return dash < 0 ? version : version.substring(0, dash);
	}

	private static String qualifierPart(String version) {
		int dash = version.indexOf('-');
		return dash < 0 ? "" : version.substring(dash + 1);
	}
}
