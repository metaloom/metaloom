package io.metaloom.cortex.node.metadata;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Layer 1 of the ingest: the lossless, <em>source-qualified</em> key/value view of everything a file
 * says about itself.
 *
 * <p>
 * Every key carries the standard it came from as its prefix - {@code exif:}, {@code iptc:},
 * {@code xmp:}, {@code container:}, {@code metaloom:}. That prefix is not decoration: the precedence
 * rules in {@link MetadataMapper} are stated per source, and a JPEG out of a photo editor routinely
 * carries the same caption in three of them with two different values. Flattening the sources
 * together - which is what Tika's own image extraction does - makes those rules unstateable.
 * </p>
 *
 * <p>
 * Values are ordered and repeatable: {@code xmp:dc:subject} is a bag and {@code xmp:dc:creator} is a
 * sequence, so a single-value map would silently keep only the last keyword.
 * </p>
 */
public class RawMetadata {

	/** EXIF, including the GPS IFD. Camera truth. */
	public static final String EXIF = "exif";

	/** IPTC-IIM, from the Photoshop image resource block. Newsroom fields. */
	public static final String IPTC = "iptc";

	/** An XMP packet embedded in the file. */
	public static final String XMP = "xmp";

	/** An XMP packet from a {@code <asset>.xmp} sidecar next to the file. */
	public static final String SIDECAR = "sidecar";

	/** Container or document properties: PDF Info, OOXML/ODF properties, ID3, MP4 ilst. */
	public static final String CONTAINER = "container";

	/**
	 * Fields MetaLoom itself wrote back into the file. Ingested at the lowest rank so the
	 * write, re-ingest, re-write loop cannot promote a machine value to authored ground truth.
	 */
	public static final String METALOOM = "metaloom";

	private final Map<String, List<String>> values = new LinkedHashMap<>();

	private final Set<String> sources = new LinkedHashSet<>();

	/**
	 * Record one value under a source-qualified key, and note that the source contributed.
	 *
	 * @param source one of the constants on this class
	 * @param key    the key within that source, e.g. {@code DateTimeOriginal} or {@code dc:title}
	 * @param value  the value; null or blank is ignored
	 */
	public RawMetadata put(String source, String key, String value) {
		if (store(source, key, value)) {
			sources.add(source);
		}
		return this;
	}

	/**
	 * Record a value without claiming the source contributed anything.
	 *
	 * <p>
	 * For the bookkeeping that is not really metadata about the content: the detected MIME type, the
	 * unmapped tag dump, Tika's own {@code X-TIKA:*} bookkeeping. {@link #sources()} is reported in
	 * the envelope as "which standards this file speaks", and a file whose only {@code container:}
	 * entry is its own MIME type does not speak any.
	 * </p>
	 */
	public RawMetadata putQuiet(String source, String key, String value) {
		store(source, key, value);
		return this;
	}

	/**
	 * Blank values are dropped rather than stored: absent and empty are the same thing to every rule
	 * in the precedence table, and keeping {@code ""} around would let an empty EXIF field outrank a
	 * populated XMP one.
	 */
	private boolean store(String source, String key, String value) {
		if (value == null) {
			return false;
		}
		String trimmed = value.trim();
		if (trimmed.isEmpty()) {
			return false;
		}
		values.computeIfAbsent(source + ":" + key, k -> new ArrayList<>()).add(trimmed);
		return true;
	}

	/**
	 * Return the first value recorded for the fully qualified key, or null.
	 */
	public String first(String qualifiedKey) {
		List<String> list = values.get(qualifiedKey);
		return list == null || list.isEmpty() ? null : list.get(0);
	}

	/**
	 * Return the first non-null value across the given keys, in the order given - the primitive the
	 * whole precedence table is written in.
	 */
	public String firstOf(String... qualifiedKeys) {
		for (String key : qualifiedKeys) {
			String value = first(key);
			if (value != null) {
				return value;
			}
		}
		return null;
	}

	/**
	 * Return every value recorded for the fully qualified key, in insertion order. Never null.
	 */
	public List<String> all(String qualifiedKey) {
		List<String> list = values.get(qualifiedKey);
		return list == null ? List.of() : Collections.unmodifiableList(list);
	}

	/**
	 * Every key/value pair, in insertion order. Repeated keys appear once with their values joined by
	 * {@code "; "} - the {@code raw} block of the envelope is a diagnostic dump, not a parse target.
	 */
	public Map<String, String> flat() {
		Map<String, String> flat = new LinkedHashMap<>();
		values.forEach((key, list) -> flat.put(key, String.join("; ", list)));
		return flat;
	}

	/**
	 * The sources that contributed at least one value.
	 */
	public Set<String> sources() {
		return Collections.unmodifiableSet(sources);
	}

	/**
	 * Record that a source was consulted even though it carried nothing usable. Only relevant for
	 * reporting - a file may legitimately hold an empty XMP packet.
	 */
	public RawMetadata touch(String source) {
		sources.add(source);
		return this;
	}

	/**
	 * Drop the fully qualified keys, before any normalisation sees them - the {@code excludeKeys}
	 * deny list. Applied here rather than during mapping so an excluded key cannot leak through the
	 * {@code raw} block either.
	 */
	public RawMetadata remove(Iterable<String> qualifiedKeys) {
		if (qualifiedKeys != null) {
			qualifiedKeys.forEach(values::remove);
		}
		return this;
	}

	public boolean isEmpty() {
		return values.isEmpty();
	}

	public int size() {
		return values.size();
	}
}
