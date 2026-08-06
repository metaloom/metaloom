package io.metaloom.cortex.node.filter;

import java.util.List;
import java.util.Locale;

import javax.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.common.media.MediaContentTypes;
import io.vertx.core.json.JsonObject;

/**
 * Routes an item by its MIME type.
 *
 * <p>
 * <strong>No model, no round trip, no {@code LLMProvider}.</strong> The type comes from the file
 * name through {@link MediaContentTypes}, so a pipeline that only splits images from video needs no
 * sidecar reachable at all — which is the point of having this alongside
 * {@link LanguageFilterStrategy} rather than asking a model what kind of file something is.
 * </p>
 *
 * <p>
 * A bucket's {@code match} is a comma-separated list of patterns, and the bucket wins if
 * <em>any</em> of them matches:
 * </p>
 * <ul>
 * <li>{@code image/png} — exact</li>
 * <li>{@code image/*} — the whole family; a trailing {@code *} is a prefix match anywhere, so
 * {@code application/vnd.*} works too</li>
 * <li>{@code image} — a bare token with no slash is read as {@code image/*}</li>
 * </ul>
 *
 * <p>
 * With no {@code match} at all the bucket <em>id</em> is used the same way, so three buckets called
 * {@code image}, {@code video} and {@code audio} route correctly with nothing typed into the hint
 * column. Buckets are tried in declaration order and the first match wins, which is what makes a
 * narrow {@code image/png} bucket above a broad {@code image/*} one behave as written.
 * </p>
 */
public class MimeFilterStrategy implements FilterStrategy {

	private static final Logger log = LoggerFactory.getLogger(MimeFilterStrategy.class);

	@Inject
	public MimeFilterStrategy() {
	}

	@Override
	public FilterBy filterBy() {
		return FilterBy.MIME;
	}

	@Override
	public Classification classify(NodeContext<LoomMedia> ctx, FilterNodeOptions options, List<FilterBucket> buckets, String text) {
		// The name, not the bytes: probeContentType is platform-dependent and would route the same
		// asset differently on two workers. See MediaContentTypes.
		String mimeType = MediaContentTypes.of(ctx.media().path());
		JsonObject detail = new JsonObject().put("mimeType", mimeType);

		for (FilterBucket bucket : buckets) {
			for (String pattern : patternsOf(bucket)) {
				if (matches(pattern, mimeType)) {
					return Classification.of(bucket.id(), 1, detail.put("matched", pattern));
				}
			}
		}

		// Debug, not warn: with a bucket set of {image, video} every document legitimately lands here.
		log.debug("MIME filter found no bucket for {} ({})", ctx.media().absolutePath(), mimeType);
		return Classification.of(Classification.OTHER, 1, detail);
	}

	/** The bucket's hints, falling back to its id so an unhinted {@code image} bucket still routes. */
	private static List<String> patternsOf(FilterBucket bucket) {
		String hints = bucket.match() == null ? bucket.id() : bucket.match();
		return java.util.Arrays.stream(hints.split(","))
			.map(hint -> hint.trim().toLowerCase(Locale.ROOT))
			.filter(hint -> !hint.isEmpty())
			.toList();
	}

	/**
	 * @param pattern
	 *            a hint, already trimmed and lowercased
	 * @param mimeType
	 *            the item's type, never null
	 */
	static boolean matches(String pattern, String mimeType) {
		if (pattern.equals("*") || pattern.equals("*/*")) {
			// Handled ahead of the bare-token rule below, which would otherwise turn "*" into the
			// prefix "*/" and match nothing - a catch-all bucket that caught nothing.
			return true;
		}
		String normalised = pattern.indexOf('/') < 0 ? pattern + "/*" : pattern;
		if (normalised.endsWith("*")) {
			return mimeType.startsWith(normalised.substring(0, normalised.length() - 1));
		}
		return normalised.equals(mimeType);
	}
}
