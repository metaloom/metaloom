package io.metaloom.loom.mcp.tool.impl.search;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.loom.db.dagger.DaoCollection;
import io.metaloom.loom.db.model.collection.Collection;
import io.metaloom.loom.db.model.library.Library;
import io.metaloom.loom.db.model.space.Space;
import io.metaloom.loom.db.model.tag.Tag;
import io.metaloom.loom.db.model.user.User;

/**
 * Turns the names a person uses into the uuids the query layer needs.
 *
 * <p>
 * <b>This is the load-bearing half of natural-language search, not the model.</b> {@code LoomFilterKey.CREATOR} and every scope field in
 * {@code SearchRequest} take a uuid, deliberately, because names are mutable and a stored query has to survive a rename. So "pete" and "project xyz"
 * are not values a model can emit - it has never seen them and would have to invent a uuid, which it will happily do. Resolving here means the model
 * passes through the word it read, and the uuid is produced by something that can actually look it up.
 * </p>
 *
 * <p>
 * <b>Ambiguity is reported, never broken arbitrarily.</b> Two users called Pete produce a {@link Match} carrying both, and the tool tells the model to
 * ask. Picking the first row would be indistinguishable from a correct answer at the point where it matters.
 * </p>
 *
 * <p>
 * <b>What this deliberately does not do.</b> It performs no permission check of its own. The tools that use it declare {@code READ_ASSET} and the
 * results they return are narrowed by the search path, but resolving a name does disclose that an entity of that name exists to a caller who could not
 * have listed it. That is a bounded and intentional trade: the feature is "show me pete's uploads", which cannot work without admitting that pete
 * exists. It is the reason the resolvers are package-scoped to the search tools rather than exposed as an MCP tool of their own - a general
 * name-to-uuid oracle over every entity type is a different, and much wider, disclosure than this.
 * </p>
 */
@Singleton
public class SearchVocabulary {

	private static final Logger log = LoggerFactory.getLogger(SearchVocabulary.class);

	/**
	 * How many rows a name lookup will scan before giving up. A miss on a large catalogue answers "not found, and the scan was capped" rather than
	 * walking the table: the tool is called on a chat turn, and an unbounded scan is a stall the user reads as the agent hanging.
	 */
	static final int MAX_SCAN = 2000;

	/** How many alternatives an ambiguous match reports. Enough to choose from, short enough to stay in a prompt. */
	static final int MAX_CANDIDATES = 5;

	private final DaoCollection daos;

	@Inject
	public SearchVocabulary(DaoCollection daos) {
		this.daos = daos;
	}

	/**
	 * The outcome of resolving one name.
	 *
	 * @param uuid
	 *            the resolved uuid, or null when nothing or too much matched
	 * @param label
	 *            the canonical name of what was resolved, for the "I applied this" report
	 * @param candidates
	 *            the alternatives when more than one matched; empty otherwise
	 */
	public record Match(UUID uuid, String label, List<String> candidates) {

		public Match {
			candidates = candidates == null ? List.of() : List.copyOf(candidates);
		}

		public static Match of(UUID uuid, String label) {
			return new Match(uuid, label, List.of());
		}

		public static Match none() {
			return new Match(null, null, List.of());
		}

		public static Match ambiguous(List<String> candidates) {
			return new Match(null, null, candidates);
		}

		public boolean isResolved() {
			return uuid != null;
		}

		public boolean isAmbiguous() {
			return uuid == null && !candidates.isEmpty();
		}
	}

	/**
	 * A user, by uuid, username, email, or full/partial real name.
	 */
	public Match resolveUser(String value) {
		UUID uuid = asUuid(value);
		if (uuid != null) {
			User user = daos.userDao().load(uuid);
			return user == null ? Match.none() : Match.of(user.getUuid(), displayName(user));
		}
		// The exact-username hit is a single indexed lookup, and is by far the common case.
		try {
			User exact = daos.userDao().loadByUsername(value.trim());
			if (exact != null) {
				return Match.of(exact.getUuid(), displayName(exact));
			}
		} catch (RuntimeException e) {
			log.debug("Username lookup for '{}' failed, falling back to a scan", value, e);
		}
		return scan(value, () -> daos.userDao().findAll(), user -> namesOf(user), SearchVocabulary::displayName);
	}

	public Match resolveSpace(String value) {
		UUID uuid = asUuid(value);
		if (uuid != null) {
			Space space = daos.spaceDao().load(uuid);
			return space == null ? Match.none() : Match.of(space.getUuid(), space.getName());
		}
		return scan(value, () -> daos.spaceDao().findAll(), space -> List.of(space.getName()), Space::getName);
	}

	public Match resolveLibrary(String value) {
		UUID uuid = asUuid(value);
		if (uuid != null) {
			Library library = daos.libraryDao().load(uuid);
			return library == null ? Match.none() : Match.of(library.getUuid(), library.getName());
		}
		return scan(value, () -> daos.libraryDao().findAll(), library -> List.of(library.getName()), Library::getName);
	}

	public Match resolveCollection(String value) {
		UUID uuid = asUuid(value);
		if (uuid != null) {
			Collection collection = daos.collectionDao().load(uuid);
			return collection == null ? Match.none() : Match.of(collection.getUuid(), collection.getName());
		}
		return scan(value, () -> daos.collectionDao().findAll(), collection -> List.of(collection.getName()), Collection::getName);
	}

	/**
	 * A tag, resolved to its <b>canonical name</b> rather than a uuid: {@code SearchRequest.tags} matches
	 * {@code search_document.tag_names}, so the name is what the query needs. Resolving is still worth doing - it fixes the case, and it is the only
	 * way to tell "no assets carry this tag" apart from "there is no such tag", which are very different answers.
	 *
	 * @return a match whose {@code label} is the canonical tag name; {@code uuid} is the tag's uuid and is informational
	 */
	public Match resolveTag(String value) {
		UUID uuid = asUuid(value);
		if (uuid != null) {
			Tag tag = daos.tagDao().load(uuid);
			return tag == null ? Match.none() : Match.of(tag.getUuid(), tag.getName());
		}
		return scan(value, () -> daos.tagDao().findAll(), tag -> List.of(tag.getName()), Tag::getName);
	}

	// ---------------------------------------------------------------------------------------------

	/**
	 * Exact match first across every alias of a row, then a prefix, then a substring - and each tier is resolved completely before the next is
	 * considered. A single exact hit therefore wins even when a dozen rows contain the word, which is what makes "pete" resolve when "peterson" also
	 * exists.
	 */
	private <T> Match scan(String value, Supplier<Stream<? extends T>> source, Function<T, List<String>> aliases, Function<T, String> label) {
		String needle = normalize(value);
		if (needle.isEmpty()) {
			return Match.none();
		}
		Map<UUID, String> exact = new LinkedHashMap<>();
		Map<UUID, String> prefix = new LinkedHashMap<>();
		Map<UUID, String> contains = new LinkedHashMap<>();
		int scanned = 0;

		try (Stream<? extends T> stream = source.get()) {
			Iterator<? extends T> elements = stream.limit(MAX_SCAN).iterator();
			while (elements.hasNext()) {
				T element = elements.next();
				scanned++;
				UUID uuid = uuidOf(element);
				if (uuid == null) {
					continue;
				}
				for (String alias : aliases.apply(element)) {
					String candidate = normalize(alias);
					if (candidate.isEmpty()) {
						continue;
					}
					if (candidate.equals(needle)) {
						exact.put(uuid, label.apply(element));
					} else if (candidate.startsWith(needle)) {
						prefix.putIfAbsent(uuid, label.apply(element));
					} else if (candidate.contains(needle)) {
						contains.putIfAbsent(uuid, label.apply(element));
					}
				}
			}
		} catch (RuntimeException e) {
			log.warn("Vocabulary scan for '{}' failed", value, e);
			return Match.none();
		}
		if (scanned >= MAX_SCAN) {
			log.info("Vocabulary scan for '{}' hit the {} row cap", value, MAX_SCAN);
		}

		for (Map<UUID, String> tier : List.of(exact, prefix, contains)) {
			if (tier.size() == 1) {
				Map.Entry<UUID, String> only = tier.entrySet().iterator().next();
				return Match.of(only.getKey(), only.getValue());
			}
			if (tier.size() > 1) {
				return Match.ambiguous(tier.values().stream().limit(MAX_CANDIDATES).toList());
			}
		}
		return Match.none();
	}

	private static List<String> namesOf(User user) {
		List<String> names = new ArrayList<>(4);
		names.add(user.getUsername());
		names.add(user.getEmail());
		String first = user.getFirstname();
		String last = user.getLastname();
		if (first != null && !first.isBlank()) {
			names.add(first);
		}
		if (last != null && !last.isBlank()) {
			names.add(last);
		}
		if (first != null && last != null && !first.isBlank() && !last.isBlank()) {
			names.add(first + " " + last);
		}
		return names;
	}

	private static String displayName(User user) {
		String first = user.getFirstname();
		String last = user.getLastname();
		if (first != null && !first.isBlank() && last != null && !last.isBlank()) {
			return first + " " + last + " (" + user.getUsername() + ")";
		}
		return user.getUsername();
	}

	private static UUID uuidOf(Object element) {
		if (element instanceof User user) {
			return user.getUuid();
		}
		if (element instanceof Space space) {
			return space.getUuid();
		}
		if (element instanceof Library library) {
			return library.getUuid();
		}
		if (element instanceof Collection collection) {
			return collection.getUuid();
		}
		if (element instanceof Tag tag) {
			return tag.getUuid();
		}
		return null;
	}

	static UUID asUuid(String value) {
		if (value == null) {
			return null;
		}
		try {
			return UUID.fromString(value.trim());
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	private static String normalize(String value) {
		return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
	}

}
