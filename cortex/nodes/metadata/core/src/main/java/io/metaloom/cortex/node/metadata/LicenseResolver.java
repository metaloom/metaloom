package io.metaloom.cortex.node.metadata;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a licence <em>URL</em> into an SPDX-style identifier.
 *
 * <p>
 * Deliberately a lookup table over well-known URLs and nothing else. A licence is the field a
 * "what may I republish" query trusts, so a wrong {@code CC-BY} is materially worse than a null:
 * the null sends someone to read the rights statement, the wrong value does not. Free text is
 * therefore never guessed at - if no URL matched, {@code licenseId} stays null and
 * {@code licenseUrl} carries the truth.
 * </p>
 */
public final class LicenseResolver {

	/**
	 * A bare URL found inside a rights statement. Used only to recover a {@code licenseUrl} that an
	 * editor wrote into {@code dc:rights} instead of {@code cc:license}; the identifier still comes
	 * from the table below, so a URL that is not a known licence yields no identifier.
	 */
	private static final Pattern URL = Pattern.compile("https?://[\\w./%+#?=&,~-]*[\\w/]");

	/** Path fragment (host-independent, version-bearing) to SPDX identifier. */
	private static final Map<String, String> BY_PATH = new LinkedHashMap<>();

	static {
		for (String version : new String[] { "4.0", "3.0", "2.5", "2.0", "1.0" }) {
			BY_PATH.put("/licenses/by/" + version, "CC-BY-" + version);
			BY_PATH.put("/licenses/by-sa/" + version, "CC-BY-SA-" + version);
			BY_PATH.put("/licenses/by-nd/" + version, "CC-BY-ND-" + version);
			BY_PATH.put("/licenses/by-nc/" + version, "CC-BY-NC-" + version);
			BY_PATH.put("/licenses/by-nc-sa/" + version, "CC-BY-NC-SA-" + version);
			BY_PATH.put("/licenses/by-nc-nd/" + version, "CC-BY-NC-ND-" + version);
		}
		BY_PATH.put("/publicdomain/zero/1.0", "CC0-1.0");
		BY_PATH.put("/publicdomain/mark/1.0", "CC-PDM-1.0");
	}

	private LicenseResolver() {
	}

	/**
	 * Return the SPDX-style identifier for the licence URL, or null when it is not one this resolver
	 * recognises.
	 */
	public static String resolve(String licenseUrl) {
		if (licenseUrl == null) {
			return null;
		}
		String normalized = licenseUrl.trim().toLowerCase(Locale.ROOT);
		// Creative Commons URLs are conventionally written with a trailing slash; a deed suffix
		// (".../by/4.0/deed.en") is the same licence.
		for (Map.Entry<String, String> entry : BY_PATH.entrySet()) {
			if (normalized.contains(entry.getKey())) {
				return entry.getValue();
			}
		}
		return null;
	}

	/**
	 * Return the first URL embedded in a free-text rights statement, or null.
	 */
	public static String findUrl(String text) {
		if (text == null) {
			return null;
		}
		Matcher matcher = URL.matcher(text);
		return matcher.find() ? matcher.group() : null;
	}
}
