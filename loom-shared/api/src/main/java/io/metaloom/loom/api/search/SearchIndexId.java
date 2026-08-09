package io.metaloom.loom.api.search;

import java.util.Locale;

/**
 * The URL-safe identifier an index is addressed by on the admin routes.
 *
 * <p>
 * <b>Ids are generated here and resolved by lookup, never parsed back.</b> A {@link VectorSpace} is identified by {@code type/model/dimensions}, and a
 * model name routinely contains a slash ({@code sentence-transformers/all-MiniLM-L6-v2}). Carrying that in a path segment means percent-encoding a
 * slash, which stock Vert.x survives but reverse proxies frequently reject or decode early - so the identity that travels over the wire is a slug, and
 * turning a slug back into a space is done by slugging the live space list and matching. That also makes a slug collision detectable at list time
 * instead of silently routing an operation to the wrong index.
 * </p>
 */
public final class SearchIndexId {

	/** The lexical index: the {@code search_document} table itself. */
	public static final String LEXICAL = "lexical";

	/** The perceptual fingerprint similarity index behind near-duplicate detection. */
	public static final String FINGERPRINT = "fingerprint";

	private static final String VECTOR_PREFIX = "vector";

	private SearchIndexId() {
	}

	/**
	 * The id for one vector space, e.g. {@code vector-face-inspireface-r18-512}.
	 */
	public static String of(VectorSpace space) {
		return of(space.type(), space.model(), space.dimensions());
	}

	public static String of(String type, String model, int dimensions) {
		StringBuilder b = new StringBuilder(VECTOR_PREFIX);
		append(b, type);
		append(b, model);
		b.append('-').append(dimensions);
		return b.toString();
	}

	/** The id for one fingerprint algorithm. The default algorithm keeps the bare {@link #FINGERPRINT} id. */
	public static String ofAlgorithm(String algorithm, String defaultAlgorithm) {
		if (algorithm == null || algorithm.isBlank() || algorithm.equals(defaultAlgorithm)) {
			return FINGERPRINT;
		}
		StringBuilder b = new StringBuilder(FINGERPRINT);
		append(b, algorithm);
		return b.toString();
	}

	private static void append(StringBuilder b, String part) {
		String slug = slug(part);
		if (!slug.isEmpty()) {
			b.append('-').append(slug);
		}
	}

	/**
	 * Lowercase, every run of non-alphanumerics collapsed to a single dash, dashes trimmed from both ends.
	 *
	 * <p>
	 * An empty model - which {@link VectorSpace} normalises {@code null} to, mirroring {@code embedding.model NOT NULL DEFAULT ''} - contributes
	 * nothing rather than a dangling dash, so {@code vector-face-512} is the id of a space whose producing model was never recorded.
	 * </p>
	 */
	public static String slug(String value) {
		if (value == null) {
			return "";
		}
		StringBuilder b = new StringBuilder(value.length());
		boolean pendingDash = false;
		for (char c : value.toLowerCase(Locale.ROOT).toCharArray()) {
			if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
				if (pendingDash && b.length() > 0) {
					b.append('-');
				}
				pendingDash = false;
				b.append(c);
			} else {
				pendingDash = true;
			}
		}
		return b.toString();
	}
}
