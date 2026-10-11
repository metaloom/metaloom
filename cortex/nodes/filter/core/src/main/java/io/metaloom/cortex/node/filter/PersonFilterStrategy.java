package io.metaloom.cortex.node.filter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import javax.inject.Inject;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Routes an item by the known persons in it - who was seen, who was heard.
 *
 * <p>
 * <strong>No model, one Loom call per item.</strong> The persons come from the asset's face and speaker clusters: the person a reviewer confirmed each
 * to be, and - when {@link PersonEvidence#SUGGESTED} is chosen - the person matching suggested. {@code facedetect} and {@code voiceprint} write those
 * clusters; this only reads them, which is why one filter serves faces and voices alike.
 * </p>
 *
 * <p>
 * A bucket's {@code match} is a comma-separated list of hints, with the grammar {@link TagFilterStrategy} established:
 * </p>
 * <ul>
 * <li>{@code Anna Meyer}, {@code anna} - a person whose alias or first-and-last name is exactly that, ignoring case</li>
 * <li>a person uuid - that person, whatever they are called</li>
 * <li>{@code *} - any known person</li>
 * <li>{@code !anna} - a veto: the person must <em>not</em> be in the item</li>
 * <li>{@code nobody} - no known person at all</li>
 * </ul>
 *
 * <p>
 * A bucket matches when at least one positive hint matches and no veto does; a bucket of only vetoes matches when none of them is present. A bucket
 * with no {@code match} falls back to its id, as with tags.
 * </p>
 *
 * <p>
 * <strong>Never cached.</strong> The answer for the same file changes the moment a reviewer confirms a cluster, and the node's verdict cache lives as
 * long as the worker. A cached "nobody" would keep routing an item down the wrong branch for days after somebody named the face in it.
 * </p>
 *
 * <p>
 * An asset Loom does not know, or whose clusters could not be read, goes to {@code other}: "nobody is in it" is a claim this cannot make without
 * having looked.
 * </p>
 */
public class PersonFilterStrategy implements FilterStrategy {

	/** The hint that matches an item in which no known person was found. */
	static final String NOBODY = "nobody";

	static final String ANYONE = "*";

	private static final String NEGATION = "!";

	@Inject
	public PersonFilterStrategy() {
	}

	@Override
	public FilterBy filterBy() {
		return FilterBy.PERSON;
	}

	@Override
	public boolean needsPersons() {
		return true;
	}

	@Override
	public boolean cacheable() {
		return false;
	}

	@Override
	public List<String> validateBuckets(List<FilterBucket> buckets) {
		List<String> errors = new ArrayList<>();
		for (FilterBucket bucket : buckets) {
			for (String hint : hints(bucket)) {
				if (hint.startsWith(NEGATION) && hint.substring(1).isBlank()) {
					errors.add("bucket '" + bucket.id() + "': '!' negates nobody; write the person to exclude, for example '!anna'");
				}
				if (hint.equals(NEGATION + NOBODY) || hint.equals(NEGATION + ANYONE)) {
					errors.add("bucket '" + bucket.id() + "': write '*' for \"somebody known\" and 'nobody' for \"nobody known\" rather than negating them");
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
		if (!item.personsAvailable()) {
			return Classification.other("persons unavailable");
		}

		PersonSource source = options.getPersonSource() == null ? PersonSource.ANY : options.getPersonSource();
		PersonEvidence evidence = options.getPersonEvidence() == null ? PersonEvidence.CONFIRMED : options.getPersonEvidence();
		List<PersonMention> present = counted(item.persons(), source, evidence);

		JsonObject detail = new JsonObject()
			.put("persons", describe(present))
			.put("personSource", source.name())
			.put("personEvidence", evidence.name());

		for (FilterBucket bucket : buckets) {
			if (matches(present, hints(bucket))) {
				return Classification.of(bucket.id(), confidence(present, bucket), detail.put("matched", bucket.id()));
			}
		}
		return Classification.of(Classification.OTHER, 1, detail);
	}

	/** The mentions this node is willing to count. */
	static List<PersonMention> counted(List<PersonMention> mentions, PersonSource source, PersonEvidence evidence) {
		return mentions.stream()
			.filter(m -> source == PersonSource.ANY || m.source() == source)
			.filter(m -> m.confirmed() || evidence == PersonEvidence.SUGGESTED)
			.toList();
	}

	/**
	 * Whether a bucket's hints hold for the persons present. Package-private so the tests can reach the rule directly.
	 */
	static boolean matches(List<PersonMention> present, List<String> hints) {
		boolean positiveSeen = false;
		boolean positiveHit = false;
		for (String hint : hints) {
			if (hint.startsWith(NEGATION)) {
				if (holds(present, hint.substring(1).trim())) {
					return false;
				}
				continue;
			}
			positiveSeen = true;
			positiveHit |= holds(present, hint);
		}
		return positiveSeen ? positiveHit : true;
	}

	private static boolean holds(List<PersonMention> present, String hint) {
		if (hint.isEmpty()) {
			return false;
		}
		if (NOBODY.equals(hint)) {
			return present.isEmpty();
		}
		if (ANYONE.equals(hint)) {
			return !present.isEmpty();
		}
		UUID uuid = uuidOrNull(hint);
		for (PersonMention mention : present) {
			if (uuid != null ? uuid.equals(mention.personUuid()) : mention.name() != null && mention.name().toLowerCase(Locale.ROOT).equals(hint)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * 1 for a verdict; for a bucket matched on suggestions only, the best score of a suggested person the bucket names. A hint-less match - "nobody",
	 * or only vetoes - is certain.
	 */
	private static double confidence(List<PersonMention> present, FilterBucket bucket) {
		double best = 0;
		boolean namedAny = false;
		for (String hint : hints(bucket)) {
			if (hint.startsWith(NEGATION) || NOBODY.equals(hint)) {
				continue;
			}
			for (PersonMention mention : present) {
				if (ANYONE.equals(hint) || holds(List.of(mention), hint)) {
					namedAny = true;
					best = Math.max(best, mention.confirmed() ? 1 : mention.score() == null ? 0 : mention.score());
				}
			}
		}
		return namedAny ? best : 1;
	}

	/** One entry per person, the strongest evidence kept: confirmed beats suggested, and a higher score beats a lower one. */
	private static JsonArray describe(List<PersonMention> present) {
		Map<UUID, JsonObject> byPerson = new LinkedHashMap<>();
		for (PersonMention mention : present) {
			JsonObject entry = byPerson.computeIfAbsent(mention.personUuid(), uuid -> new JsonObject()
				.put("personUuid", uuid.toString())
				.put("name", mention.name())
				.put("sources", new JsonArray())
				.put("confirmed", false));
			JsonArray sources = entry.getJsonArray("sources");
			if (!sources.contains(mention.source().name())) {
				sources.add(mention.source().name());
			}
			if (mention.confirmed()) {
				entry.put("confirmed", true);
			} else if (mention.score() != null && mention.score() > entry.getFloat("score", -1f)) {
				entry.put("score", mention.score());
			}
		}
		return new JsonArray(new ArrayList<>(byPerson.values()));
	}

	private static UUID uuidOrNull(String hint) {
		if (hint.length() != 36) {
			return null;
		}
		try {
			return UUID.fromString(hint);
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	private static List<String> hints(FilterBucket bucket) {
		String match = bucket.match() == null ? bucket.id() : bucket.match();
		if (match == null) {
			return List.of();
		}
		// Collapsed whitespace: "Anna  Meyer" is how somebody types the name, and the stored name has one space.
		return Arrays.stream(match.split(","))
			.map(hint -> hint.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT))
			.filter(hint -> !hint.isEmpty())
			.toList();
	}
}
