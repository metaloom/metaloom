package io.metaloom.cortex.node.sink.s3;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Renders the object key an artifact is stored under.
 *
 * <p>Pure: no S3, no filesystem, no clock. Parsed once per configured node and rendered per
 * artifact.</p>
 *
 * <h2>Placeholders</h2>
 *
 * <table>
 * <caption>Available placeholders</caption>
 * <tr><td>{@code {sha512}}</td><td>hex SHA-512 of the <em>artifact</em></td></tr>
 * <tr><td>{@code {sha512:N}}</td><td>its first N hex chars; {@code {sha512:4}} matches the shard
 * level the local {@code *_bin} caches use</td></tr>
 * <tr><td>{@code {sourceSha512}}, {@code {sourceSha512:N}}</td><td>the same for the source media</td></tr>
 * <tr><td>{@code {nodeId}}</td><td>this sink instance's graph id</td></tr>
 * <tr><td>{@code {sourceNode}}, {@code {sourceKey}}</td><td>the producing node and output key</td></tr>
 * <tr><td>{@code {ext}}</td><td>file extension <em>including</em> the dot, or empty</td></tr>
 * <tr><td>{@code {filename}}, {@code {basename}}</td><td>local file name, with and without extension</td></tr>
 * <tr><td>{@code {assetUuid}}</td><td>the source asset's uuid</td></tr>
 * <tr><td>{@code {index}}</td><td>position within a multi-valued output, {@code 0} otherwise</td></tr>
 * <tr><td>{@code {indexSuffix}}</td><td>empty when single-valued, {@code -N} when not</td></tr>
 * </table>
 *
 * <p><b>An unresolvable placeholder fails the artifact; it is never substituted with a blank or
 * the string "null".</b> A key containing {@code null} is the worst possible outcome, because every
 * asset would collide onto it and silently overwrite the previous one.</p>
 */
public final class S3KeyTemplate {

	/** S3's hard limit on a key, in UTF-8 bytes. */
	public static final int MAX_KEY_BYTES = 1024;

	private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-zA-Z0-9]+)(?::(\\d+))?\\}");

	private static final List<String> SIMPLE_NAMES = List.of(
		"sha512", "sourceSha512", "nodeId", "sourceNode", "sourceKey",
		"ext", "filename", "basename", "assetUuid", "index", "indexSuffix");

	/** Names that accept a {@code :N} truncation. */
	private static final List<String> TRUNCATABLE = List.of("sha512", "sourceSha512");

	private final String template;

	private S3KeyTemplate(String template) {
		this.template = template;
	}

	/**
	 * Parse and validate a template.
	 *
	 * @param template the raw template
	 * @return the parsed template
	 * @throws IllegalArgumentException naming the offending placeholder
	 */
	public static S3KeyTemplate parse(String template) {
		if (template == null || template.isBlank()) {
			throw new IllegalArgumentException("must not be empty");
		}
		Matcher matcher = PLACEHOLDER.matcher(template);
		while (matcher.find()) {
			String name = matcher.group(1);
			String width = matcher.group(2);
			if (!SIMPLE_NAMES.contains(name)) {
				throw new IllegalArgumentException("unknown placeholder '{" + name + "}' (known: "
					+ String.join(", ", SIMPLE_NAMES) + ")");
			}
			if (width != null) {
				if (!TRUNCATABLE.contains(name)) {
					throw new IllegalArgumentException("placeholder '{" + name + "}' does not take a length");
				}
				int n = Integer.parseInt(width);
				if (n < 1 || n > 128) {
					throw new IllegalArgumentException("'{" + name + ":" + width + "}' length must be 1..128");
				}
			}
		}
		// A template consisting only of separators would render to an empty or slash-only key.
		if (PLACEHOLDER.matcher(template).replaceAll("").equals(template) && template.isBlank()) {
			throw new IllegalArgumentException("must not be empty");
		}
		return new S3KeyTemplate(template);
	}

	/**
	 * The values a render draws on. Any field may be null; using a placeholder whose value is null
	 * fails the render rather than producing a degenerate key.
	 */
	public record Values(String sha512, String sourceSha512, String nodeId, String sourceNode,
		String sourceKey, String ext, String fileName, String baseName, String assetUuid,
		int index, boolean multiValued) {
	}

	/**
	 * @param values the substitutions
	 * @return the object key
	 * @throws IllegalStateException when a used placeholder has no value, or the result is not a
	 *         usable S3 key
	 */
	public String render(Values values) {
		Matcher matcher = PLACEHOLDER.matcher(template);
		StringBuilder out = new StringBuilder();
		while (matcher.find()) {
			String name = matcher.group(1);
			String width = matcher.group(2);
			String value = resolve(name, values);
			if (value == null) {
				throw new IllegalStateException("key template placeholder '{" + name + "}' has no value"
					+ ("sha512".equals(name) ? " - an upstream sha512 node is required to use it" : ""));
			}
			if (width != null) {
				int n = Integer.parseInt(width);
				value = value.length() <= n ? value : value.substring(0, n);
			}
			matcher.appendReplacement(out, Matcher.quoteReplacement(value));
		}
		matcher.appendTail(out);
		return normalize(out.toString());
	}

	private static String resolve(String name, Values v) {
		return switch (name) {
			case "sha512" -> v.sha512();
			case "sourceSha512" -> v.sourceSha512();
			case "nodeId" -> v.nodeId();
			case "sourceNode" -> v.sourceNode();
			case "sourceKey" -> v.sourceKey();
			// An extension-less artifact legitimately renders an empty string rather than failing.
			case "ext" -> v.ext() == null ? "" : v.ext();
			case "filename" -> v.fileName();
			case "basename" -> v.baseName();
			case "assetUuid" -> v.assetUuid();
			case "index" -> String.valueOf(v.index());
			case "indexSuffix" -> v.multiValued() ? "-" + v.index() : "";
			default -> null;
		};
	}

	/**
	 * Tidy the rendered key and refuse anything S3 or the reader side cannot handle.
	 */
	static String normalize(String raw) {
		String key = raw.replaceAll("/{2,}", "/");
		while (key.startsWith("/")) {
			// S3 accepts a leading slash but it creates an empty first "folder" that every console
			// and every `mc ls` renders badly.
			key = key.substring(1);
		}
		if (key.isBlank()) {
			throw new IllegalStateException("key template rendered an empty key");
		}
		if (key.endsWith("/")) {
			// AwsS3ObjectStore.list filters keys ending in a slash out as directory placeholders,
			// so an object stored under one would be invisible to s3-source forever.
			throw new IllegalStateException("key '" + key + "' ends in '/', which S3 treats as a directory placeholder");
		}
		for (String segment : key.split("/")) {
			if (segment.equals(".") || segment.equals("..")) {
				throw new IllegalStateException("key '" + key + "' contains a '" + segment + "' segment");
			}
		}
		for (int i = 0; i < key.length(); i++) {
			if (Character.isISOControl(key.charAt(i))) {
				throw new IllegalStateException("key contains a control character at position " + i);
			}
		}
		int bytes = key.getBytes(StandardCharsets.UTF_8).length;
		if (bytes > MAX_KEY_BYTES) {
			throw new IllegalStateException("key is " + bytes + " bytes, over S3's " + MAX_KEY_BYTES + " byte limit");
		}
		return key;
	}

	/**
	 * @return the placeholder names this template uses, in order of first appearance
	 */
	public List<String> placeholders() {
		List<String> names = new ArrayList<>();
		Matcher matcher = PLACEHOLDER.matcher(template);
		while (matcher.find()) {
			if (!names.contains(matcher.group(1))) {
				names.add(matcher.group(1));
			}
		}
		return names;
	}

	@Override
	public String toString() {
		return template;
	}
}
