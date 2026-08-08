package io.metaloom.cortex.node.filter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import javax.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.loom.rest.model.tag.TagReference;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Routes an item by the tags on it.
 *
 * <p>
 * <strong>No model, and no round trip of its own.</strong> Tags ride along on the
 * {@code AssetResponse} that {@code AbstractMediaNode} already loaded for every item, so this is as
 * cheap as {@link MimeFilterStrategy} once Loom is reachable at all.
 * </p>
 *
 * <p>
 * A bucket's {@code match} is a comma-separated list of hints:
 * </p>
 * <ul>
 * <li>{@code hero} — carries a tag named exactly that</li>
 * <li>{@code person/*}, {@code *} — a prefix glob; a trailing {@code *} is the only wildcard</li>
 * <li>{@code !archive}, {@code !person/*} — a veto: the asset must <em>not</em> carry it</li>
 * <li>{@code untagged} — carries no tags at all</li>
 * </ul>
 *
 * <p>
 * A bucket matches when <strong>at least one positive hint matches and no negated hint does</strong>
 * — so {@code hero, !archive} reads as "hero but not archive", which is the only thing anyone means
 * by it. Treating {@code !} as another alternative would make such a bucket match nearly everything.
 * A bucket of <em>only</em> negations matches when none of them are present, which is the useful
 * "not reviewed yet" branch and needs no positive term.
 * </p>
 *
 * <p>
 * Like {@link MimeFilterStrategy}, a bucket with no {@code match} falls back to its own id — three
 * buckets called {@code hero}, {@code archive} and {@code trash} need no hints at all. That is why
 * {@link #validateBuckets(List)} cannot reject an empty match column here, unlike the size and rating
 * strategies, where a bucket id is never a threshold.
 * </p>
 *
 * <p>
 * An asset Loom does not know is routed to {@code other} rather than treated as untagged: we have no
 * idea what is on it, and "no tags" is a claim we cannot make.
 * </p>
 */
public class TagFilterStrategy implements FilterStrategy {

	private static final Logger log = LoggerFactory.getLogger(TagFilterStrategy.class);

	/** The hint that matches an asset carrying no tags. */
	static final String UNTAGGED = "untagged";

	private static final String NEGATION = "!";

	private static final String MANUAL_NODE_KIND = "manual";

	@Inject
	public TagFilterStrategy() {
	}

	@Override
	public FilterBy filterBy() {
		return FilterBy.TAG;
	}

	@Override
	public List<String> validateBuckets(List<FilterBucket> buckets) {
		List<String> errors = new ArrayList<>();
		for (FilterBucket bucket : buckets) {
			for (String hint : hints(bucket)) {
				// Every word is a legal tag name, so the only unusable hint is a bare '!' - a veto
				// against nothing, which silently matches everything it was meant to exclude.
				if (NEGATION.equals(hint)) {
					errors.add("bucket '" + bucket.id() + "': '!' negates nothing; write the tag to exclude, for example '!archive'");
				}
			}
		}
		return errors;
	}

	@Override
	public Classification classify(FilterItem item, FilterNodeOptions options, List<FilterBucket> buckets) {
		if (item.asset() == null) {
			return Classification.other("asset is not known to Loom");
		}

		TagSource source = options.getTagSource() == null ? TagSource.ANY : options.getTagSource();
		List<String> names = names(item.tags(), source);

		JsonObject detail = new JsonObject()
			.put("tags", new JsonArray(names))
			.put("tagSource", source.name());

		for (FilterBucket bucket : buckets) {
			if (matches(names, hints(bucket))) {
				return Classification.of(bucket.id(), 1, detail.put("matched", bucket.id()));
			}
		}

		log.debug("Tag filter found no bucket for {} (tags {})", item.media().absolutePath(), names);
		return Classification.of(Classification.OTHER, 1, detail);
	}

	/** The names of the tags this node is willing to look at, lowercased for comparison. */
	private static List<String> names(List<TagReference> tags, TagSource source) {
		return tags.stream()
			.filter(tag -> counts(tag, source))
			.map(TagReference::getName)
			.filter(name -> name != null)
			.map(name -> name.toLowerCase(Locale.ROOT))
			.toList();
	}

	private static boolean counts(TagReference tag, TagSource source) {
		return switch (source) {
			case ANY -> true;
			// An absent node kind is a person: the column defaults to 'manual' on purpose.
			case MANUAL -> tag.getNodeKind() == null || MANUAL_NODE_KIND.equals(tag.getNodeKind());
			case MACHINE -> tag.getNodeKind() != null && !MANUAL_NODE_KIND.equals(tag.getNodeKind());
		};
	}

	/**
	 * Whether a bucket's hints hold for a set of tag names.
	 *
	 * <p>
	 * Package-private so the tests can reach the rule directly rather than only through a routed
	 * item.
	 * </p>
	 */
	static boolean matches(List<String> names, List<String> hints) {
		boolean positiveSeen = false;
		boolean positiveHit = false;
		for (String hint : hints) {
			if (hint.startsWith(NEGATION)) {
				if (holds(names, hint.substring(1))) {
					// One veto is enough, whatever else matched.
					return false;
				}
				continue;
			}
			positiveSeen = true;
			positiveHit |= holds(names, hint);
		}
		// No positive hint at all means the bucket is defined purely by what it excludes.
		return positiveSeen ? positiveHit : true;
	}

	private static boolean holds(List<String> names, String hint) {
		if (hint.isEmpty()) {
			return false;
		}
		if (UNTAGGED.equals(hint)) {
			return names.isEmpty();
		}
		if (hint.endsWith("*")) {
			String prefix = hint.substring(0, hint.length() - 1);
			return names.stream().anyMatch(name -> name.startsWith(prefix));
		}
		return names.contains(hint);
	}

	private static List<String> hints(FilterBucket bucket) {
		// Falls back to the bucket id, like MIME: a bucket called 'hero' routing assets tagged 'hero'
		// is the single most useful default, and asking the author to type the word twice earns nothing.
		String match = bucket.match() == null ? bucket.id() : bucket.match();
		if (match == null) {
			return List.of();
		}
		return Arrays.stream(match.split(","))
			.map(hint -> hint.trim().toLowerCase(Locale.ROOT))
			.filter(hint -> !hint.isEmpty())
			.toList();
	}
}
