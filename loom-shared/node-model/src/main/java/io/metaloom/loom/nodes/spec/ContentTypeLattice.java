package io.metaloom.loom.nodes.spec;

/**
 * The single Java implementation of content-type assignability.
 *
 * <p>
 * A content type id is always <code>family/subtype</code>; <code>family/&#42;</code> is the family root. The whole rule is:
 * </p>
 *
 * <pre>
 * assignable(actual, declared) :=
 *      actual == declared                       // exact
 *   || declared == family(actual) + "/*"        // the consumer accepts the whole family
 *   || actual   == family(declared) + "/*"      // the producer is unspecific (a source emits media/*)
 * </pre>
 *
 * <p>
 * The third arm exists for source nodes, which emit <code>media/&#42;</code> because the concrete mime type is unknown when the graph is drawn.
 * Save-time validation therefore treats wildcard-into-subtype as <em>provisionally</em> valid; the runtime boundary check decides with the real file
 * in hand.
 * </p>
 *
 * <p>
 * Assignability deliberately never crosses families: a <code>hash/md5</code> does not satisfy a <code>scalar/string</code> input even though both are
 * carried as a Java {@code String}. Wiring a hash into a generic string consumer is almost always a mistake, and forbidding it keeps this rule free of
 * special cases — which is what lets the editor mirror it in five lines of TypeScript.
 * </p>
 *
 * <p>
 * Mirrored in <code>loom-ui/src/features/pipeline/contentTypes.ts</code>; {@code ContentTypeLatticeTest} exports the fixture that pins the two
 * implementations together.
 * </p>
 */
public final class ContentTypeLattice {

	private static final String WILDCARD_SUFFIX = "/*";

	private ContentTypeLattice() {
	}

	/**
	 * The family part of a content type id, i.e. everything before the slash.
	 *
	 * @param contentType
	 *            content type id, e.g. {@code detection/face}
	 * @return the family, e.g. {@code detection}; {@code null} when the id is null or has no slash
	 */
	public static String family(String contentType) {
		if (contentType == null) {
			return null;
		}
		int slash = contentType.indexOf('/');
		return slash < 0 ? null : contentType.substring(0, slash);
	}

	/**
	 * Whether the id is a family wildcard such as {@code media/*}.
	 */
	public static boolean isWildcard(String contentType) {
		return contentType != null && contentType.endsWith(WILDCARD_SUFFIX);
	}

	/**
	 * The wildcard for a content type's family, e.g. {@code detection/face} → {@code detection/*}.
	 */
	public static String wildcardOf(String contentType) {
		String family = family(contentType);
		return family == null ? null : family + WILDCARD_SUFFIX;
	}

	/**
	 * Whether a value declared as {@code actual} may be fed into a port declared as {@code declared}.
	 *
	 * @param actual
	 *            the producing port's content type
	 * @param declared
	 *            the consuming port's content type
	 */
	public static boolean isAssignable(String actual, String declared) {
		if (actual == null || declared == null) {
			return false;
		}
		if (actual.equals(declared)) {
			return true;
		}
		String actualFamily = family(actual);
		String declaredFamily = family(declared);
		if (actualFamily == null || declaredFamily == null || !actualFamily.equals(declaredFamily)) {
			return false;
		}
		return isWildcard(declared) || isWildcard(actual);
	}

	/**
	 * Whether the producer side is only provisionally compatible — it declares a family wildcard while the consumer wants a concrete subtype, so the
	 * real verdict can only be reached at runtime.
	 */
	public static boolean isProvisional(String actual, String declared) {
		return isAssignable(actual, declared) && isWildcard(actual) && !isWildcard(declared);
	}
}
