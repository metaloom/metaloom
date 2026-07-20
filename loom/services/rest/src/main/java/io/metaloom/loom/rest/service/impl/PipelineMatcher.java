package io.metaloom.loom.rest.service.impl;

import java.util.Collection;
import java.util.Comparator;
import java.util.Optional;

import io.metaloom.loom.db.model.pipeline.PipelineVersion;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Selects the pipeline that should automatically process a newly created asset.
 *
 * <p>
 * There is no first-class trigger column on a pipeline. Instead a pipeline opts in to auto-processing by carrying a trigger descriptor in its version
 * {@code meta}:
 * </p>
 *
 * <pre>
 * "meta": { "trigger": { "auto": true, "mimeTypes": ["image/*", "video/*"] } }
 * </pre>
 *
 * <p>
 * A version matches when it is {@link PipelineVersion#isEnabled() enabled}, its trigger is {@code auto}, and one of its {@code mimeTypes} patterns
 * matches the asset mime type. Patterns support a bare star (any type), a {@code type/}-prefixed wildcard, or an exact {@code type/subtype}. When
 * several pipelines match, the one with the highest {@link PipelineVersion#getPriority() priority} wins.
 * </p>
 */
public final class PipelineMatcher {

	public static final String META_TRIGGER = "trigger";
	public static final String META_MIME_TYPES = "mimeTypes";
	public static final String META_AUTO = "auto";

	private PipelineMatcher() {
	}

	/**
	 * Select the highest-priority enabled pipeline version whose trigger matches the given mime type.
	 *
	 * @param versions
	 *            candidate versions (typically the latest version of every pipeline)
	 * @param mimeType
	 *            the mime type of the created asset
	 * @return the winning version, or empty when nothing matches
	 */
	public static Optional<PipelineVersion> selectForMimeType(Collection<PipelineVersion> versions, String mimeType) {
		return versions.stream()
			.filter(v -> v != null && v.isEnabled())
			.filter(v -> isAutoMatch(v, mimeType))
			.max(Comparator.comparingInt(PipelineVersion::getPriority));
	}

	static boolean isAutoMatch(PipelineVersion version, String mimeType) {
		if (mimeType == null) {
			return false;
		}
		JsonObject meta = version.getMeta();
		if (meta == null) {
			return false;
		}
		JsonObject trigger = meta.getJsonObject(META_TRIGGER);
		if (trigger == null || !trigger.getBoolean(META_AUTO, false)) {
			return false;
		}
		JsonArray mimeTypes = trigger.getJsonArray(META_MIME_TYPES);
		if (mimeTypes == null || mimeTypes.isEmpty()) {
			return false;
		}
		for (int i = 0; i < mimeTypes.size(); i++) {
			if (matches(mimeTypes.getString(i), mimeType)) {
				return true;
			}
		}
		return false;
	}

	static boolean matches(String pattern, String mimeType) {
		if (pattern == null) {
			return false;
		}
		String p = pattern.trim().toLowerCase();
		String mt = mimeType.trim().toLowerCase();
		if (p.equals("*") || p.equals("*/*")) {
			return true;
		}
		if (p.endsWith("/*")) {
			return mt.startsWith(p.substring(0, p.length() - 1));
		}
		return p.equals(mt);
	}
}
